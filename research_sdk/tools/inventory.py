#!/usr/bin/env python3
"""Read-only inventory of all remote branch heads; never executes branch code."""
import collections
import json
import pathlib
import subprocess
BASE = '4763e0fa2845b5b3e443fb9e298b7b2964a3067f'
OUT = pathlib.Path('research_sdk/generated')
EXT = {'.kt', '.java', '.cpp', '.h', '.cs', '.py', '.ts', '.js', '.mjs'}
def git(*args):
    return subprocess.check_output(['git', *args], text=True, encoding='utf-8')
def tree(ref):
    out = {}
    for rec in git('ls-tree', '-r', '-z', ref).split('\0'):
        if rec:
            meta, path = rec.split('\t', 1)
            mode, kind, oid = meta.split()
            if kind == 'blob': out[path] = oid
    return out
def main():
    OUT.mkdir(parents=True, exist_ok=True)
    refs = git('for-each-ref', '--format=%(refname:short)', 'refs/remotes/origin').splitlines()
    refs = [r for r in refs if r not in {'origin/HEAD','origin/research_sdk'}]
    baseline = tree(BASE)
    rows = []
    versions = collections.defaultdict(lambda: collections.defaultdict(list))
    for ref in sorted(refs):
        branch = ref.removeprefix('origin/')
        files = tree(ref)
        sources = {p:s for p,s in files.items() if pathlib.Path(p).suffix in EXT}
        for p,s in sources.items(): versions[p][s].append(branch)
        changes = sorted(p for p in set(files)|set(baseline) if files.get(p)!=baseline.get(p))
        rows.append(dict(branch=branch,sha=git('rev-parse',ref).strip(),files=len(files),source_files=len(sources),different_paths=changes,source_tree=sources))
    data = dict(baseline=BASE,method='Recursive blob inventory of all fetched remote heads, not a line-by-line semantic audit.',branches=rows,source_versions=versions)
    (OUT/'branch_inventory.json').write_text(json.dumps(data,indent=2,sort_keys=True)+'\n')
    lines = ['# All-branch inventory','', 'Baseline: `'+BASE+'`.','', 'Structural coverage only; semantic review and hardware validation are separate.','', '| Branch | Head | Files | Source files | Changed paths |','|---|---|---:|---:|---:|']
    for r in rows:
        lines.append('| `'+r['branch']+'` | `'+r['sha'][:12]+'` | '+str(r['files'])+' | '+str(r['source_files'])+' | '+str(len(r['different_paths']))+' |')
    lines += ['','## Tracking-related source versions','']
    terms = ('align','track','pose','depth','surface','fusion','capture','gnss','ranger','vio','anchor','geometry','render')
    for p, blobs in sorted(versions.items()):
        if any(t in p.lower() for t in terms):
            lines.append('- `'+p+'`: '+str(len(blobs))+' distinct versions')
    (OUT/'branch_inventory.md').write_text('\n'.join(lines)+'\n')
    print('Inventoried',len(rows),'remote branches and',len(versions),'source paths')
if __name__=='__main__': main()
