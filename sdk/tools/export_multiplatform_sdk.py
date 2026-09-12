#!/usr/bin/env python3
"""Export StableAR SDK into a clean standalone tree suitable for a new private repository."""
from pathlib import Path
import argparse, shutil
p=argparse.ArgumentParser();p.add_argument('destination',help='empty/new destination directory');p.add_argument('--repo-root',default=None);a=p.parse_args()
script=Path(__file__).resolve();repo=Path(a.repo_root).resolve() if a.repo_root else script.parents[2];src=repo/'sdk';dst=Path(a.destination).resolve()
if dst==repo or dst==src or repo in dst.parents and dst.name in {'sdk','.git'}: raise SystemExit('refusing unsafe destination')
if dst.exists() and any(dst.iterdir()): raise SystemExit('destination must be empty')
dst.mkdir(parents=True,exist_ok=True)
ignore=shutil.ignore_patterns('build','.gradle','local.properties','.stablear-dev-keys','*.pem','*.key','*.p12','*.keystore')
for item in src.iterdir():
    if item.name in {'build','.gradle','local.properties'}: continue
    target=dst/item.name
    if item.is_dir(): shutil.copytree(item,target,ignore=ignore)
    else: shutil.copy2(item,target)
workflow=repo/'.github/workflows/stablear-multiplatform.yml'
if workflow.exists():
    out=dst/'.github/workflows';out.mkdir(parents=True,exist_ok=True);shutil.copy2(workflow,out/workflow.name)
for forbidden in ('showme','android','server','research_sdk'):
    if (dst/forbidden).exists(): raise SystemExit(f'forbidden host product leaked: {forbidden}')
print(dst)
