"""Rebuild protobuf message schemas from a hermes-dec decompiled protobuf-ts bundle.

protobuf-ts emits, per message, field descriptors like
    {'no': 10, 'name': 'user_communities', 'kind': 'message', 'repeat': 1, 'T': null}
whose T is resolved by a closure that either reads `rX.TypeName` from an import
or returns a module slot (`_closure0_slot2`) holding a message defined earlier
in the same module. The type name string ('root.CommunityMemberListResponse')
precedes the MessageType construction, after which the instance is stored in
a slot (`var _closure0_slot6 = r7;`).

Usage: python3 extract_schema.py root.js > schema.json
"""
import json, re, sys
from bisect import bisect_right

FIELD = re.compile(r"=\s*(\{'no': \d+, 'name': '[^']*'.*?\});\s*$")
TYPENAME = re.compile(r"^\s*r\d+ = '([a-z][a-z0-9_]*(?:\.[a-z0-9_]+)*\.[A-Z][A-Za-z0-9_]*)';\s*$")
TREF = re.compile(r"r\d+ = r\d+\.([A-Z][A-Za-z0-9_]*);")
SLOTREAD = re.compile(r"r0 = (_closure\d+_slot\d+);")
SLOTSET = re.compile(r"var (_closure\d+_slot\d+) = r\d+;")
ENUMNAME = re.compile(r"'([a-z][a-z0-9_.]*\.[A-Z][A-Za-z0-9_]*)'")
SCALARS = {1: "double", 2: "float", 3: "int64", 4: "uint64", 5: "int32", 6: "fixed64", 7: "fixed32",
           8: "bool", 9: "string", 12: "bytes", 13: "uint32", 15: "sfixed32", 16: "sfixed64", 17: "sint32", 18: "sint64"}


def parse_field(src):
    d = {}
    for k, v in re.findall(r"'(\w+)': ('[^']*'|-?\d+|true|false|null)", src):
        d[k] = v.strip("'") if v.startswith("'") else (True if v == "true" else False if v == "false" else None if v == "null" else int(v))
    return d


def main(path):
    lines = open(path, encoding="utf-8", errors="replace").readlines()
    msgs, fields = {}, []
    slot_events = {}  # slot -> sorted [(line, typename)]
    unresolved = []   # (msgname, field dict, line, slot)
    for i, line in enumerate(lines):
        m = FIELD.search(line)
        if m:
            fld = parse_field(m.group(1))
            kind = fld.get("kind")
            if kind == "scalar" and isinstance(fld.get("T"), int):
                fld["type"] = SCALARS.get(fld["T"], f"scalar{fld['T']}")
            elif kind == "map":
                fld["type"] = "map"
            else:
                for la in lines[i + 1:i + 12]:
                    if kind == "enum":
                        e = ENUMNAME.search(la)
                        if e:
                            fld["type"] = e.group(1).split(".")[-1]
                            fld["enum"] = True
                            break
                    t = TREF.search(la)
                    if t:
                        fld["type"] = t.group(1)
                        break
                    s = SLOTREAD.search(la)
                    if s:
                        fld["_slot"] = s.group(1)
                        fld["_line"] = i
                        break
            fields.append(fld)
            continue
        t = TYPENAME.match(line)
        if t and any("CallWithNewTarget" in la for la in lines[i + 1:i + 6]):
            name = t.group(1)
            seen, out = set(), []
            for fl in fields:
                if fl.get("no") not in seen:
                    seen.add(fl.get("no"))
                    out.append(fl)
            msgs.setdefault(name, out)
            fields = []
            for la_i in range(i + 1, min(i + 10, len(lines))):
                s = SLOTSET.search(lines[la_i])
                if s:
                    slot_events.setdefault(s.group(1), []).append((la_i, name))
                    break
    # resolve slot references to the nearest preceding (else following) definition
    for evs in slot_events.values():
        evs.sort()
    for name, flds in msgs.items():
        for f in flds:
            slot = f.pop("_slot", None)
            ln = f.pop("_line", None)
            if slot and "type" not in f:
                evs = slot_events.get(slot, [])
                if evs:
                    idx = bisect_right([e[0] for e in evs], ln) - 1
                    pick = evs[idx] if idx >= 0 else evs[0]
                    f["type"] = pick[1].split(".")[-1]
    json.dump(msgs, sys.stdout, indent=1, sort_keys=True)


main(sys.argv[1])
