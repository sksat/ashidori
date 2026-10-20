"""Sizes of row-oriented and database formats on the same final-schema
tables as measure.py (whole day per table, rows in arrival order). Prints
aggregate numbers only.

    python -I survey.py <dir from extract.py> <scratch dir for files>

Needs msgpack, fastavro, mcap, duckdb in addition to measure.py's packages.
"""
import os, sys, io, sqlite3, shutil, collections
import numpy as np, pyarrow as pa, pyarrow.feather as feather, zstandard, msgpack, fastavro, duckdb
from mcap.writer import Writer as McapWriter, CompressionType

D, W = sys.argv[1], sys.argv[2]
def fresh_dir(out, *inputs):
    """Empties `out` for this run. Refuses a symlink, and a target that (after
    resolving links) is, contains or lies inside an input or a protected path
    (DETAIL_BENCH_PROTECT: the database, set by run.sh)."""
    if os.path.islink(out):
        sys.exit(f"refusing to delete {out}: it is a symbolic link")
    real = os.path.realpath(out)
    protect = [p for p in os.environ.get("DETAIL_BENCH_PROTECT", "").split(os.pathsep) if p]
    for i in list(inputs) + protect:
        i = os.path.realpath(i)
        if os.path.commonpath([real, i]) in (real, i):
            sys.exit(f"refusing to delete {real}: it is, contains or lies inside {i}")
    if real in ("/", os.path.realpath(os.path.expanduser("~"))):
        sys.exit(f"refusing to delete {real}")
    shutil.rmtree(real, ignore_errors=True); os.makedirs(real)
fresh_dir(W, D)
NAMES = {"imu_accel": "Accel", "imu_gyro": "Gyro", "imu_mag": "Mag", "gnss_epoch": "gnss_epoch", "gnss_signal": "gnss_signal",
         "gnss_sat": "gnss_sat", "gnss_nav": "gnss_nav", "gnss_fix": "gnss_fix", "nmea": "nmea"}
res = collections.defaultdict(dict)
for name, f in NAMES.items():
    t = feather.read_table(os.path.join(D, f"{f}.arrow")).drop_columns(["frag"])
    rows = [list(r.values()) for r in t.to_pylist()]
    clean = [[None if isinstance(v, float) and v != v else v for v in r] for r in rows]
    # MessagePack rows in one zstd stream
    buf = io.BytesIO(); zw = zstandard.ZstdCompressor(level=3).stream_writer(buf, closefd=False)
    for r in clean: zw.write(msgpack.packb(r))
    zw.flush(zstandard.FLUSH_FRAME); res["msgpack rows + zstd3"][name] = len(buf.getvalue())
    # Avro, zstandard codec
    def avt(fl):
        ty = fl.type
        return ["null", "long" if pa.types.is_integer(ty) else "float" if ty == pa.float32() else "double" if ty == pa.float64()
                else "bytes" if pa.types.is_binary(ty) else "string"]
    sch = fastavro.parse_schema({"type": "record", "name": "r", "fields": [{"name": fl.name, "type": avt(fl)} for fl in t.schema]})
    p = os.path.join(W, name + ".avro")
    with open(p, "wb") as fo: fastavro.writer(fo, sch, ({fl.name: v for fl, v in zip(t.schema, r)} for r in clean), codec="zstandard")
    res["avro zstandard"][name] = os.path.getsize(p)
    # MCAP: one message per row (MessagePack payload), zstd chunks of 1 MiB
    p = os.path.join(W, name + ".mcap")
    with open(p, "wb") as fo:
        w = McapWriter(fo, chunk_size=1 << 20, compression=CompressionType.ZSTD); w.start()
        sid = w.register_schema(name=name, encoding="", data=b""); cid = w.register_channel(topic=name, message_encoding="msgpack", schema_id=sid)
        for r in clean:
            ts = (r[0] or 0) * 1_000_000
            w.add_message(channel_id=cid, log_time=ts, publish_time=ts, data=msgpack.packb(r))
        w.finish()
    res["mcap row/message zstd"][name] = os.path.getsize(p)
    # DuckDB
    p = os.path.join(W, name + ".duckdb")
    con = duckdb.connect(p); con.register("src", t); con.execute("CREATE TABLE x AS SELECT * FROM src"); con.execute("CHECKPOINT"); con.close()
    res["duckdb"][name] = os.path.getsize(p)
    # SQLite typed table with a time index, WAL, batched inserts
    p = os.path.join(W, name + ".sqlite")
    con = sqlite3.connect(p); con.execute("PRAGMA journal_mode=WAL")
    decl = ",".join(f'"{fl.name}" ' + ("INTEGER" if pa.types.is_integer(fl.type) else "REAL" if pa.types.is_floating(fl.type) else "BLOB" if pa.types.is_binary(fl.type) else "TEXT") for fl in t.schema)
    con.execute(f"CREATE TABLE x({decl})"); con.execute('CREATE INDEX i ON x("time")')
    q = f"INSERT INTO x VALUES({','.join('?' * t.num_columns)})"
    for i in range(0, len(clean), 2000):
        con.executemany(q, clean[i:i + 2000]); con.commit()
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)"); con.close()
    res["sqlite typed + time index"][name] = os.path.getsize(p)
    print(name, {k: round(v[name] / 1e6, 2) for k, v in res.items()}, flush=True)
for k, v in res.items():
    print(f"{k}: {sum(v.values()) / 1e6:.2f} MB")
