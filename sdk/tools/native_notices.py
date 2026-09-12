#!/usr/bin/env python3
"""Reconcile exact Maven OpenCV bytes with the pinned official Android release.
Extract licence documents only; never execute upstream native code or scripts.
"""
from __future__ import annotations
import hashlib, json, pathlib, re, tempfile, urllib.request, zipfile

URL='https://github.com/opencv/opencv/releases/download/4.12.0/opencv-4.12.0-android-sdk.zip'
DIGEST='fd7f2332331b4eb8b67e55137281cfb16823c9399d90deb9cfa3476783b99e35'
ROOT=pathlib.Path(__file__).resolve().parents[1]
ABIS={'arm64-v8a','armeabi-v7a','x86','x86_64'}
SUPPLEMENTAL={
    'libwebp-COPYING.txt': ('https://raw.githubusercontent.com/opencv/opencv/4.12.0/3rdparty/libwebp/COPYING',
                          '7a6f99547d4d6b4c5e6d5321acfaeba313e08de3'),
}

def abi_from_path(name: str):
    for part in pathlib.PurePosixPath(name).parts:
        normalized=part.removeprefix('android.')
        if normalized in ABIS: return normalized
    return None

def is_notice(name: str) -> bool:
    path=pathlib.PurePosixPath(name)
    # Do not mistake detector data such as haarcascade_license_plate_*.xml for notices.
    if path.suffix.lower() in {'.xml','.bin','.onnx','.tflite','.ttf','.otf'}: return False
    if any(p.lower() in {'licenses','licences','notices'} for p in path.parts[:-1]): return True
    return re.fullmatch(r'(license|licence|notice|copying|copyright)(\.(txt|md|rst|html|htm))?',path.name,re.I) is not None

def file_digest(stream):
    digest=hashlib.sha256()
    while True:
        chunk=stream.read(1024*1024)
        if not chunk: return digest.hexdigest()
        digest.update(chunk)

def main():
    out=ROOT/'build/compliance'; out.mkdir(parents=True,exist_ok=True)
    folder=out/'opencv-official-notices'; folder.mkdir(exist_ok=True)
    # This directory is generated, never a user/source directory. Remove stale notices on reruns.
    for old in folder.iterdir():
        if old.is_file(): old.unlink()
    report={'source':URL,'expectedSha256':DIGEST,'notices':[],'supplementalNotices':[],
        'nativeComparisons':[],
        'meaning':'Byte equality establishes release provenance, not patent or commercial clearance.'}
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
                    abi=abi_from_path(item.filename)
                    if abi:
                        with z.open(item) as f: native[(abi,pathlib.PurePosixPath(item.filename).name)]=file_digest(f)
                elif is_notice(item.filename):
                    if item.file_size>5_000_000: raise RuntimeError('Unexpected notice size')
                    content=z.read(item)
                    content.decode('utf-8')
                    name=re.sub('[^a-zA-Z0-9_.-]','_',item.filename)
                    (folder/name).write_bytes(content)
                    report['notices'].append({'archivePath':item.filename,'file':name,'sha256':hashlib.sha256(content).hexdigest()})
        for aar in sorted(aars):
            with zipfile.ZipFile(aar) as z:
                for item in z.infolist():
                    if not item.filename.endswith('.so'): continue
                    abi=abi_from_path(item.filename); basename=pathlib.PurePosixPath(item.filename).name
                    with z.open(item) as f: actual=file_digest(f)
                    expected=native.get((abi,basename))
                    status=('MATCH' if actual==expected else 'MISMATCH') if expected else 'NO_RELEASE_COUNTERPART'
                    report['nativeComparisons'].append({'path':item.filename,'abi':abi,'sha256':actual,
                        'officialSha256':expected,'status':status,
                        'matchesOfficialRelease':actual==expected if expected else False})
    for name,(url,expected_git_blob) in SUPPLEMENTAL.items():
        with urllib.request.urlopen(url,timeout=30) as response: content=response.read(1_000_001)
        if len(content)>1_000_000: raise RuntimeError('Unexpected supplemental notice size')
        content.decode('utf-8')
        git_blob=hashlib.sha1(b'blob '+str(len(content)).encode()+b'\0'+content).hexdigest()
        if git_blob!=expected_git_blob: raise RuntimeError('Pinned supplemental notice changed: '+name)
        (folder/name).write_bytes(content)
        report['supplementalNotices'].append({'source':url,'file':name,'gitBlob':git_blob,
                                              'sha256':hashlib.sha256(content).hexdigest()})
    assets=ROOT/'demo/src/main/assets/THIRD_PARTY_NOTICES.txt'
    marker='\n===== Exact official OpenCV Android release notices =====\n'
    # Idempotent regeneration; never retain an accidentally matched non-notice from a previous run.
    base=assets.read_text(encoding='utf-8').split(marker,1)[0]
    with assets.open('w',encoding='utf-8') as output:
        output.write(base+marker)
        for notice in report['notices']+report['supplementalNotices']:
            output.write('\n----- '+notice.get('archivePath',notice.get('source',''))+' -----\n')
            output.write((folder/notice['file']).read_text())
    comparisons=report['nativeComparisons']
    cv=[c for c in comparisons if pathlib.PurePosixPath(c['path']).name=='libopencv_java4.so']
    report['opencvBinariesMatchOfficialRelease']=bool(cv) and all(c['status']=='MATCH' for c in cv)
    report['runtimeLibrariesWithoutReleaseCounterpart']=[c for c in comparisons if c['status']=='NO_RELEASE_COUNTERPART']
    report['allNativeFilesMatch']=bool(comparisons) and all(c['status']=='MATCH' for c in comparisons)
    report['commercialReleaseCleared']=False
    report['remainingReview']='libc++ runtime provenance/notices and complete statically linked subcomponent notice reconciliation remain open; see NATIVE_FINDINGS.md.'
    (out/'native-provenance.json').write_text(json.dumps(report,indent=2)+'\n')
    print('Official OpenCV ZIP checksum verified;',len(report['notices']),'release notices;',
          len(report['supplementalNotices']),'pinned supplemental notices;',len(cv),'OpenCV binary entries matched:',
          report['opencvBinariesMatchOfficialRelease'],'; runtime entries without counterpart:',
          len(report['runtimeLibrariesWithoutReleaseCounterpart']))
    if not report['notices'] or not report['opencvBinariesMatchOfficialRelease']:
        raise RuntimeError('Missing notices or unexpected OpenCV binary provenance')
    if any(c['status']=='MISMATCH' for c in comparisons): raise RuntimeError('Native binary mismatch')

if __name__=='__main__': main()
