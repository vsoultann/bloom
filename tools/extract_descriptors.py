"""Extract embedded protobuf FileDescriptorProtos from a native library.

Finds `\\x0a<len><name>.proto` starts, walks valid FileDescriptorProto fields
(tags 1-14) to find each blob's end, parses it, and writes a .proto-ish dump.
Usage: python extract_descriptors.py libX.so outdir
"""
import os, re, sys
from google.protobuf import descriptor_pb2

data = open(sys.argv[1], 'rb').read()
out = sys.argv[2]
os.makedirs(out, exist_ok=True)

def varint(b, i):
    r = s = 0
    while True:
        if i >= len(b): raise ValueError
        x = b[i]; i += 1
        r |= (x & 0x7f) << s; s += 7
        if not x & 0x80: return r, i

VALID = {1: 2, 2: 2, 3: 2, 4: 2, 5: 2, 6: 2, 7: 2, 8: 2, 9: 2, 10: 0, 11: 0, 12: 2, 14: 0}
seen = {}
for m in re.finditer(rb'\x0a([\x05-\x7f])([a-z0-9_/]+\.proto)', data):
    start = m.start()
    if len(m.group(2)) != m.group(1)[0]: continue
    i = start
    try:
        while i < len(data):
            key, j = varint(data, i)
            tag, wt = key >> 3, key & 7
            if tag not in VALID or VALID[tag] != wt: break
            if wt == 0: _, j = varint(data, j)
            else:
                ln, j = varint(data, j); j += ln
            i = j
        fd = descriptor_pb2.FileDescriptorProto.FromString(data[start:i])
    except Exception:
        continue
    if fd.name and (fd.message_type or fd.enum_type or fd.service) and fd.name not in seen:
        seen[fd.name] = fd

TYPES = {1:'double',2:'float',3:'int64',4:'uint64',5:'int32',6:'fixed64',7:'fixed32',8:'bool',9:'string',12:'bytes',13:'uint32',15:'sfixed32',16:'sfixed64',17:'sint32',18:'sint64'}
def dump_msg(m, ind=''):
    s = f"{ind}message {m.name} {{\n"
    for e in m.enum_type: s += dump_enum(e, ind + '  ')
    for n in m.nested_type: s += dump_msg(n, ind + '  ')
    for f in m.field:
        t = f.type_name.lstrip('.') if f.type in (11, 14) else TYPES.get(f.type, str(f.type))
        lab = 'repeated ' if f.label == 3 else ''
        one = f"  // oneof {m.oneof_decl[f.oneof_index].name}" if f.HasField('oneof_index') and not f.proto3_optional else ''
        s += f"{ind}  {lab}{t} {f.name} = {f.number};{one}\n"
    return s + f"{ind}}}\n"
def dump_enum(e, ind=''):
    return f"{ind}enum {e.name} {{\n" + ''.join(f"{ind}  {v.name} = {v.number};\n" for v in e.value) + f"{ind}}}\n"
for name, fd in seen.items():
    s = f'// {fd.name}\npackage {fd.package};\n'
    for e in fd.enum_type: s += dump_enum(e)
    for m in fd.message_type: s += dump_msg(m)
    for sv in fd.service:
        s += f"service {sv.name} {{\n" + ''.join(f"  rpc {r.name}({r.input_type.lstrip('.')}) returns ({'stream ' if r.server_streaming else ''}{r.output_type.lstrip('.')});\n" for r in sv.method) + "}\n"
    path = os.path.join(out, name.replace('/', '__'))
    open(path, 'w').write(s)
print(len(seen), 'proto files')
