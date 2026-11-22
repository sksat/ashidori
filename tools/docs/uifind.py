"""Prints the centre "x y" of the first node in a uiautomator dump whose text
or content-desc matches.

    python3 uifind.py ui.xml TEXT [exact|prefix|contains|longest]

`longest`: of the nodes whose text or content-desc starts with TEXT, the one
showing the longest distance (km), e.g. the longest train leg of a day.
    python3 uifind.py ui.xml --max-km KM   exits 1 when a text shows a longer distance
"""
import re
import sys
import xml.etree.ElementTree as ET


def max_km(path: str, limit: float) -> None:
    """Exits 1 when a text shows a distance over `limit` km."""
    root = ET.parse(path).getroot()
    for n in root.iter("node"):
        for m in re.finditer(r"([\d,]+(?:\.\d+)?) ?km", n.get("text", "")):
            if float(m.group(1).replace(",", "")) > limit:
                print(f"implausible: {n.get('text')}", file=sys.stderr)
                sys.exit(1)


def main() -> None:
    path, want = sys.argv[1], sys.argv[2]
    if want == "--max-km":
        return max_km(path, float(sys.argv[3]))
    how = sys.argv[3] if len(sys.argv) > 3 else "exact"
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return
    if how == "longest":
        best = None
        for n in root.iter("node"):
            v = n.get("content-desc", "") or n.get("text", "")
            m = re.search(r"([\d,]+(?:\.\d+)?) ?km", v)
            if v.startswith(want) and m:
                km = float(m.group(1).replace(",", ""))
                if best is None or km > best[0]:
                    best = (km, n)
        nodes = [best[1]] if best else []
        how = "any"
    else:
        nodes = list(root.iter("node"))
    for n in nodes:
        for v in (n.get("text", ""), n.get("content-desc", "")):
            ok = True if how == "any" else (v == want) if how == "exact" else v.startswith(want) if how == "prefix" else (want in v)
            if v and ok:
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    print((x1 + x2) // 2, (y1 + y2) // 2)
                    return


main()
