#!/usr/bin/env python3
"""Prints "x y text" (center of bounds) for every node of a uiautomator dump
whose resource-id or content-desc matches a regex.

  ui_nodes.py <window_dump.xml> <regex> [--text]

With --text, only nodes with a non-empty text.

Throwaway prototype for docs/design/outbound-and-offline-prototypes.md.
"""
import re
import sys
import xml.etree.ElementTree as ET

dump, pattern = sys.argv[1], re.compile(sys.argv[2])
text_only = "--text" in sys.argv[3:]  # only nodes with a non-empty text
for n in ET.parse(dump).iter("node"):
    ident = n.get("resource-id", "") + " " + n.get("content-desc", "")
    if pattern.search(ident) and (n.get("text") or not text_only):
        x0, y0, x1, y1 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x0 + x1) // 2, (y0 + y1) // 2, n.get("text", "").replace("\n", " "))
