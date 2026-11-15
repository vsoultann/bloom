"""Recover protobuf schemas from protobuf-ts `internalBinaryWrite` functions.

Used for messages whose descriptor block the decompiler dropped. The writer
writes each field as: read `message.prop`, `writer.tag(N, WireType.X)`, then
either a scalar call (`.string`, `.int32`, ...) or `Type.internalBinaryWrite`.
The writer is tied to a message name through the module's export getters
(`'ChannelResponse'` -> `_closure0_slotK`) and the slot the instance lands in.

Usage: python3 extract_writers.py root.js schema.json > schema_full.json
"""
import json, re, sys

lines = open(sys.argv[1], encoding="utf-8", errors="replace").readlines()
base = json.load(open(sys.argv[2]))
have_short = {k.split(".")[-1] for k in base}
import os
FORCE = set(filter(None, os.environ.get("FORCE", "").split(",")))

SLOTRET = re.compile(r"r0 = (_closure0_slot\d+);")
EXPORTNAME = re.compile(r"^\s*r\d+ = '([A-Z][A-Za-z0-9_]*)';\s*$")
INSTSLOT = re.compile(r"var (_closure0_slot\d+) = r\d+;")
PROP = re.compile(r"r\d+ = r\d+\.([a-z][A-Za-z0-9]*);$")
NUM = re.compile(r"^\s*r0 = (\d+);")
TYPEREF = re.compile(r"r\d+ = r\d+\.([A-Z][A-Za-z0-9_]*);$")
SCALAR = re.compile(r"\.(string|bool|int32|int64|uint32|uint64|sint32|sint64|fixed32|fixed64|sfixed32|sfixed64|float|double|bytes);$")
SKIP_TYPES = {"WireType", "LengthDelimited", "Varint", "Bit32", "Bit64", "UnknownFieldHandler", "BinaryWriter", "Symbol"}

def camel_to_snake(s):
    return re.sub(r"(?<!^)([A-Z])", r"_\1", s).lower()

exports = {}          # slot -> exported name (current module)
pending_writer = None
out = {}
i = 0
while i < len(lines):
    line = lines[i]
    if "'__esModule'" in line:
        exports = {}
    # export getter: function(){ r0 = _closure0_slotK; return r0; } ... rX = 'Name'; defineProperty
    m = SLOTRET.search(line)
    if m and i + 6 < len(lines):
        for la in lines[i + 1:i + 6]:
            e = EXPORTNAME.match(la)
            if e:
                exports[m.group(1)] = e.group(1)
                break
    if "Original name: internalBinaryWrite" in line:
        fields, prop, typ, rep = [], None, None, False
        j = i + 1
        depth_end = None
        while j < len(lines) and j < i + 3000:
            l = lines[j].rstrip()
            if "Original name:" in l and "internalBinaryWrite" not in l:
                break
            p = PROP.search(l)
            if p and p.group(1) not in ("tag", "fork", "join", "length", "push", "bind", "typeName", "writeUnknownFields", "onWrite", "internalBinaryWrite", "internalBinaryRead", "call", "string", "bool", "int32", "int64", "uint32", "uint64", "float", "double", "bytes", "fixed64", "fixed32", "sint32", "sint64", "sfixed32", "sfixed64", "iterator", "next", "value", "done"):
                if p.group(1) != prop:
                    prop, typ, rep = p.group(1), None, False
            if prop and ".length;" in l:
                rep = True
            t = TYPEREF.search(l)
            if t and t.group(1) not in SKIP_TYPES:
                typ = t.group(1)
            n = NUM.match(l)
            if n and prop:
                no = int(n.group(1))
                # scalar type usually follows the number within a few lines
                stype = None
                for la in lines[j + 1:j + 5]:
                    s = SCALAR.search(la.rstrip())
                    if s:
                        stype = s.group(1)
                        break
                ftype = stype or typ or "unknown"
                if no > 0 and not any(f["no"] == no for f in fields):
                    fields.append({"no": no, "name": camel_to_snake(prop), "type": ftype,
                                   "kind": "scalar" if stype else "message", **({"repeat": 1} if rep else {})})
            if l.strip() == "};" and lines[j - 1].strip() == "}":
                depth_end = j
                break
            j += 1
        # find the instance slot this class lands in
        for la in lines[j:j + 400]:
            s = INSTSLOT.search(la)
            if s and s.group(1) in exports:
                name = exports[s.group(1)]
                if (name not in have_short or name in FORCE) and fields:
                    out[name] = sorted(fields, key=lambda f: f["no"])
                break
            if "Original name: internalBinaryWrite" in la:
                break
        i = j
    i += 1

merged = dict(base)
for name, flds in out.items():
    if name in FORCE:
        for k in [k for k in merged if k.split(".")[-1] == name]:
            merged[k] = flds
    else:
        merged.setdefault("root." + name, flds)
json.dump(merged, sys.stdout, indent=1, sort_keys=True)
print(f"recovered {len(out)} messages from writers", file=sys.stderr)
