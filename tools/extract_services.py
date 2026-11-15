"""Rebuild protobuf-ts service definitions (method -> input/output types).

Pattern per method:  {'name': 'ListMine', 'options': null, 'I': null, 'O': null}
followed by `rX = rY.InputType;` then `rX = rY.OutputType;`, and the service
name string ('root.CommunityGrpcService') shortly after the method array.
Usage: python3 extract_services.py root.js > services.json
"""
import json, re, sys

METHOD = re.compile(r"\{'name': '([A-Z][A-Za-z0-9]*)', 'options': null, 'I': null, 'O': null\}")
TREF = re.compile(r"r\d+ = r\d+\.([A-Z][A-Za-z0-9_]*);")
SVC = re.compile(r"'((?:connect|root(?:\.v2)?)\.[A-Za-z]+(?:Service|GrpcService))'")

lines = open(sys.argv[1], encoding="utf-8", errors="replace").readlines()
services, pending = {}, []
for i, line in enumerate(lines):
    m = METHOD.search(line)
    if m:
        refs = []
        for la in lines[i + 1:i + 14]:
            t = TREF.search(la)
            if t:
                refs.append(t.group(1))
            if len(refs) == 2:
                break
        if len(refs) == 2:
            pending.append({"method": m.group(1), "in": refs[0], "out": refs[1]})
        continue
    s = SVC.search(line)
    if s and pending:
        services.setdefault(s.group(1), pending)
        pending = []
json.dump(services, sys.stdout, indent=1, sort_keys=True)
