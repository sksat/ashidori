"""Cuts recordings into a looping GIF with an optimised palette (ffmpeg).

    python3 mkgif.py OUT.gif [--width 360] [--fps 12] SEG [SEG ...]

SEG is FILE[@START..END][*SPEED]: a part of a recording, optionally sped up,
e.g. `day.webm@1.5..7.2` or `dl.webm@0..20*4`. START and END are seconds or
marks of FILE's .marks file (written by capture-lib.sh `mark`) with an offset,
e.g. `day.webm@open-0.3..tap+1.5`; END may be empty for the end. A PNG is shown
still for END-START seconds (`map.png@0..1.5`).
Times a scene marked as cutNa .. cutNb (the UI being read, nothing moving) are
left out of every part. The parts are joined in order.

The emulator's recordings have frames only when the screen changes: each is made
constant-rate first (the last frame held), so a cut keeps the still time in it.
Unchanged pixels are left transparent in each GIF frame, so still parts cost little.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path


def at(f: str, t: str):
    """A time: seconds, or a mark of f's .marks file with an optional +/- offset."""
    if not t:
        return None
    m = re.fullmatch(r"([A-Za-z_]\w*)?([+-]?[\d.]+)?", t)
    if not m or not (m.group(1) or m.group(2)):
        sys.exit(f"bad time: {t}")
    base = 0.0
    if m.group(1):
        marks = dict(line.split() for line in Path(f).with_suffix(".marks").read_text().splitlines() if line.strip())
        if m.group(1) not in marks:
            sys.exit(f"{f}: no mark {m.group(1)}")
        base = float(marks[m.group(1)])
    return base + float(m.group(2) or 0)


def cuts(f: str):
    """The intervals marked cutNa .. cutNb in f's .marks file."""
    p = Path(f).with_suffix(".marks")
    if not p.exists():
        return []
    marks = dict(line.split() for line in p.read_text().splitlines() if line.strip())
    out = []
    for k, v in marks.items():
        m = re.fullmatch(r"cut(\d+)a", k)
        if m and f"cut{m.group(1)}b" in marks:
            out.append((float(v), float(marks[f"cut{m.group(1)}b"])))
    return sorted(out)


def pieces(f: str, a: float, b):
    """[a, b] without the cut intervals (a short margin kept on each side)."""
    out, t = [], a
    for ca, cb in cuts(f):
        ca, cb = ca + 0.15, cb - 0.15
        if cb <= ca or cb <= t or (b is not None and ca >= b):
            continue
        if ca > t:
            out.append((t, ca))
        t = cb
    out.append((t, b))
    return [(x, y) for x, y in out if y is None or y - x > 0.05]


def length(f: str) -> float:
    """A recording's length in seconds."""
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", f],
                         check=True, capture_output=True, text=True).stdout
    return float(out)


def parse(seg: str):
    m = re.fullmatch(r"(.+?)(?:@([\w.+-]*)\.\.([\w.+-]*))?(?:\*([\d.]+))?", seg)
    if not m:
        sys.exit(f"bad segment: {seg}")
    f, a, b, sp = m.groups()
    return f, (at(f, a) or 0.0) if a else 0.0, at(f, b) if b else None, float(sp) if sp else 1.0


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("segs", nargs="+")
    ap.add_argument("--width", type=int, default=360)
    ap.add_argument("--fps", type=int, default=12)
    ap.add_argument("--colors", type=int, default=128)
    ap.add_argument("--dither", default="none")
    ap.add_argument("--hi", type=int, default=768, help="mpdecimate: an 8x8 block differing more is a change")
    o = ap.parse_args()

    inputs, chains, parts = [], [], []
    for s in o.segs:
        f, a, b, sp = parse(s)
        if f.endswith(".png"):
            parts.append((f, a, b, sp, True))
        else:
            # A part may end after the recording did (it is held there).
            b = None if b is not None and b >= length(f) else b
            parts += [(f, x, y, sp, False) for x, y in pieces(f, a, b)]
    for i, (f, a, b, sp, still) in enumerate(parts):
        if still:
            inputs += ["-loop", "1", "-t", str((b or 1.0) - a), "-i", f]
            trim = ""
        else:
            inputs += ["-i", f]
            pad = "tpad=stop_mode=clone:stop_duration=30," if b is not None else ""
            trim = f"{pad}fps={o.fps},trim=start={a}" + (f":end={b}" if b is not None else "") + \
                ",setpts=PTS-STARTPTS,"
        speed = f"setpts=PTS/{sp}," if sp != 1.0 else ""
        chains.append(
            f"[{i}:v]{trim}{speed}fps={o.fps},scale={o.width}:-2:flags=lanczos,setsar=1,format=rgb24[s{i}]")
    n = len(parts)
    joined = "".join(f"[s{i}]" for i in range(n)) + f"concat=n={n}:v=1:a=0[cat]"
    # Frames that barely change (the recordings' compression noise on a still screen) are
    # dropped: the frame before is shown longer. One palette for the whole clip.
    tail = f"[cat]mpdecimate=hi={o.hi}:lo={o.hi // 2}:frac=0.33,split[a][b];" \
        f"[a]palettegen=max_colors={o.colors}:stats_mode=diff:reserve_transparent=0[p];" \
        f"[b][p]paletteuse=dither={o.dither}:diff_mode=rectangle[g]"
    graph = ";".join(chains + [joined, tail])

    def run(extra):
        subprocess.run(["ffmpeg", "-v", "error", "-y", *inputs, "-filter_complex", graph, "-map", "[g]",
                        "-fps_mode", "vfr", "-loop", "0", *extra, o.out], check=True)

    run([])
    # A still end is dropped with the frames that do not change: the last frame is held
    # for the rest of the clip's length.
    total = sum(((b if b is not None else length(f)) - a) / sp for f, a, b, sp, _ in parts)
    last = float(subprocess.run(["ffprobe", "-v", "error", "-show_entries", "packet=pts_time", "-of", "csv=p=0",
                                 o.out], check=True, capture_output=True, text=True).stdout.split()[-1])
    if total - last > 0.1:
        run(["-final_delay", str(round((total - last) * 100))])
    size = Path(o.out).stat().st_size
    print(f"{o.out}: {size / 1e6:.2f} MB")


main()
