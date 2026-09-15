#!/usr/bin/env python3
"""Bounded AV-039 UI captures on the dedicated synthetic AVD; no review actions."""
import argparse
import json
import re
import subprocess
from pathlib import Path
import xml.etree.ElementTree as ET

ADB = Path.home() / 'Library/Android/sdk/platform-tools/adb'
SERIAL = 'emulator-5584'
EVIDENCE = Path(__file__).resolve().parents[2] / 'docs/testing/av039/evidence'


def adb(*args):
    return subprocess.check_output([str(ADB), '-s', SERIAL, *args])


def tree():
    raw = adb('exec-out', 'uiautomator', 'dump', '/dev/tty').decode()
    return raw[raw.index('<?xml'):raw.index('</hierarchy>') + len('</hierarchy>')]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['capture', 'tap', 'long-tap', 'show'])
    parser.add_argument('value', nargs='?')
    args = parser.parse_args()
    if adb('emu', 'avd', 'name').decode().splitlines()[0] != 'AnkiVoice_AV039':
        raise SystemExit('Refusing any AVD other than AnkiVoice_AV039')
    xml = tree()
    root = ET.fromstring(xml)
    if args.command == 'capture':
        if not args.value or not re.fullmatch(r'[a-z0-9-]+', args.value):
            raise SystemExit('Capture label must be lowercase letters, digits and hyphens')
        EVIDENCE.mkdir(parents=True, exist_ok=True)
        target = EVIDENCE / (args.value + '.xml')
        if target.exists():
            raise SystemExit(f'Refusing to overwrite {target}')
        target.write_text(xml + '\n')
        target.with_suffix('.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    elif args.command in ('tap', 'long-tap'):
        parents = {child: parent for parent in root.iter() for child in parent}
        nodes = []
        matches = [n for n in root.iter('node') if n.get('text') == args.value]
        if not matches:
            matches = [n for n in root.iter('node') if n.get('content-desc') == args.value]
        for node in matches:
            while node.get('clickable') != 'true' and node in parents:
                node = parents[node]
            if node.get('clickable') == 'true' and node.get('enabled') == 'true' and node not in nodes:
                nodes.append(node)
        if not nodes:
            nodes = [n for n in matches if n.get('enabled') == 'true']
        if len(nodes) != 1:
            raise SystemExit(f'Expected one enabled {args.value!r}; found {len(nodes)}. Inspect/scroll and retry.')
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', nodes[0].get('bounds')))
        x, y = str((x1+x2)//2), str((y1+y2)//2)
        if args.command == 'long-tap':
            adb('shell', 'input', 'swipe', x, y, x, y, '900')
        else:
            adb('shell', 'input', 'tap', x, y)
    for node in root.iter('node'):
        if node.get('text') or node.get('content-desc'):
            print(json.dumps({k: node.get(k) for k in ['text', 'content-desc', 'bounds', 'enabled', 'checked']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
