#!/usr/bin/env python3
"""Compare the pinned official Android release with the exact Maven native binaries.
Only licence text is extracted. No downloaded native code or build scripts run.
"""
from __future__ import annotations
import hashlib, json, pathlib, re, tempfile, urllib.request, zipfile

URL='https://github.com/opencv/opencv/releases/download/4.12.0/opencv-4.12.0-android-sdk.zip'
DIGEST='fd7f2332331b4eb8b67e55137281cfb16823c9399d90deb9cfa3476783b99e35'
ROOT=pathlib.Path(__file__).resolve().parents[1]

def file_digest(stream):
    digest=hashlib.sha256()
    while True:
        chunk=stream.read(1024*1024)
        if not chunk: return digest.hexdigest()
        digest.update(chunk)

def main():
    out=ROOT/'build/compliance'; out.mkdir(parents=True,exist_ok=True)
    folder=out/'opencv-official-notices'; folder.mkdir(exist_ok=True)
    report={'source':URL,'expectedSha256':DIGEST,'notices':[],'nativeComparisons':[],
        'meaning':'Matching bytes establish release provenance, not a patent opinion or commercial clearance.'}
    rows=json.loads((out/'resolved.json').read_text())
    aars={r['artifact'] for r in rows if r['group']=='org.opencv' and r['name']=='opencv' and r['version']=='4.12.0'}
    if not aars: raise RuntimeError('Expected resolved OpenCV 4.12.0 AAR not found')
    with tempfile.TemporaryDirectory() as temp:
        archive=pathlib.Path(temp)/'opencv.zip'
        digest=hashlib.sha256(); size=0
        with urllib.request.urlopen(URL,timeout=60) as source, archive.open('wb') as target:
            while True:
                chunk=source.read(1024*1024)
                if not chunk: break
                size+=len(chunk)
                if size>350_000_000: raise RuntimeError('Unexpectedly large release archive')
                digest.update(chunk); target.write(chunk)
        report['actualSha256']=digest.hexdigest()
        if report['actualSha256']!=DIGEST: raise RuntimeError('Official release checksum changed')
        native={}
        with zipfile.ZipFile(archive) as z:
            for item in z.infolist():
                if item.is_dir(): continue
                if item.filename.endswith('.so'):
                    parts=pathlib.PurePosixPath(item.filename).parts
                    abi=next((p for p in parts if p in {'arm64-v8a','armeabi-v7a','x86','x86_64'}),None)
                    if abi:
                        with z.open(item) as f: native[(abi,parts[-1])]=file_digest(f)
                elif re.search(r'(^|[/_.-])(licenses?|licences?|notices?|copying|copyright)([/_.-]|$)',item.filename,re.I):
                    if item.file_size>5_000_000: continue
                    content=z.read(item)
                    try: content.decode('utf-8')
                    except UnicodeDecodeError: continue
                    name=re.sub('[^a-zA-Z0-9_.-]','_',item.filename)
                    (folder/name).write_bytes(content)
                    report['notices'].append({'archivePath':item.filename,'file':name,'sha256':hashlib.sha256(content).hexdigest()})
        for aar in sorted(aars):
            with zipfile.ZipFile(aar) as z:
                for item in z.infolist():
                    if not item.filename.endswith('.so'): continue
                    parts=pathlib.PurePosixPath(item.filename).parts
                    abi=next((p for p in parts if p in {'arm64-v8a','armeabi-v7a','x86','x86_64'}),None)
                    with z.open(item) as f: actual=file_digest(f)
                    expected=native.get((abi,parts[-1]))
                    report['nativeComparisons'].append({'path':item.filename,'sha256':actual,
                        'officialSha256':expected,'matchesOfficialRelease':actual==expected if expected else False})
    assets=ROOT/'demo/src/main/assets/THIRD_PARTY_NOTICES.txt'
    with assets.open('a',encoding='utf-8') as output:
        output.write('\n===== Exact official OpenCV Android release notices =====\n')
        for notice in report['notices']:
            output.write('\n----- '+notice['archivePath']+' -----\n')
            output.write((folder/notice['file']).read_text())
    report['allNativeFilesMatch']=bool(report['nativeComparisons']) and all(c['matchesOfficialRelease'] for c in report['nativeComparisons'])
    (out/'native-provenance.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Official OpenCV release checksum verified;',len(report['notices']),'licence files;',
          len(report['nativeComparisons']),'native comparisons; all matched:',report['allNativeFilesMatch'])
    # Record a mismatch rather than silently asserting licence equivalence.
    if not report['notices']: raise RuntimeError('No release licence evidence was found')

if __name__=='__main__': main()
