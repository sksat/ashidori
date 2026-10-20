"""Writes the IMU samples (from extract.py) in arrival order as flat binary
files for jvm/Proto.scala: imu_t.bin (wall ms, ern ns as int64 pairs),
imu_s.bin (sensor 0..2 as int8), imu_v.bin (x, y, z as float32).

    python -I imu_bin.py <dir from extract.py> <out dir>
"""
import sys, os, numpy as np, pyarrow.feather as feather
t_dir, out = sys.argv[1], sys.argv[2]
parts = []
for code, name in enumerate(("Accel", "Gyro", "Mag")):
    t = feather.read_table(os.path.join(t_dir, f"{name}.arrow"))
    parts.append((t["time"].to_numpy(), t["ern"].to_numpy(), np.full(t.num_rows, code, np.int8),
                  np.stack([t[c].to_numpy() for c in "xyz"], 1)))
tw = np.concatenate([p[0] for p in parts]); ern = np.concatenate([p[1] for p in parts])
s = np.concatenate([p[2] for p in parts]); v = np.concatenate([p[3] for p in parts])
o = np.lexsort((ern, tw))  # approximate arrival order: wall time, then sensor time
np.stack([tw[o], ern[o]], 1).astype("<i8").tofile(os.path.join(out, "imu_t.bin"))
s[o].tofile(os.path.join(out, "imu_s.bin"))
v[o].astype("<f4").tofile(os.path.join(out, "imu_v.bin"))
