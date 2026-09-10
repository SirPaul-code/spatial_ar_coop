#!/usr/bin/env python3
"""Evidence collection, not legal clearance. Native bundles and model weights need separate review."""
from __future__ import annotations
import argparse, hashlib, io, json, pathlib, re, urllib.request, urllib.error, zipfile
import xml.etree.ElementTree as ET

REPOS=('https://repo.maven.apache.org/maven2/','https://dl.google.com/dl/android/maven2/')
NS={'m':'http://maven.apache.org/POM/4.0.0'}
POLICY={
 'org.jetbrains.kotlin': ('Apache-2.0','https://github.com/JetBrains/kotlin/blob/v1.9.24/license/LICENSE.txt'),
 'org.jetbrains': ('Apache-2.0','https://github.com/JetBrains/java-annotations/blob/master/LICENSE.txt'),
 'org.opencv': ('Apache-2.0 AND LicenseRef-Native-Bundle-Review','https://opencv.org/license/'),
 'com.google.ar': ('LicenseRef-ARCore-Additional-Terms','https://developers.google.com/ar/develop/terms'),
 'com.google.protobuf': ('BSD-3-Clause','https://github.com/protocolbuffers/protobuf/blob/main/LICENSE'),
 'androidx.annotation': ('Apache-2.0','https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt'),
}

def sha(data: bytes)->str: return hashlib.sha256(data).hexdigest()
def download(url: str)->bytes:
    with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'StableAR-license-audit/0.1'}),timeout=20) as response:
        return response.read(10_000_000)

def pom(group: str,name: str,version: str,out: pathlib.Path,seen=None):
    seen=set() if seen is None else seen
    key=(group,name,version)
    if key in seen or len(seen)>8: return {'licenses':[],'error':'parent cycle or limit'}
    seen.add(key); suffix=group.replace('.','/')+'/'+name+'/'+version+'/'+name+'-'+version+'.pom'
    for repo in REPOS:
        try:
            data=download(repo+suffix); root=ET.fromstring(data)
            path=out/(group+'-'+name+'-'+version+'.pom'); path.write_bytes(data)
            licenses=[{'name':x.findtext('m:name',default='',namespaces=NS),
                       'url':x.findtext('m:url',default='',namespaces=NS)} for x in root.findall('m:licenses/m:license',NS)]
            parent=root.find('m:parent',NS); inherited=None
            if not licenses and parent is not None:
                values=[parent.findtext('m:'+s,default='',namespaces=NS) for s in ('groupId','artifactId','version')]
                if all(values) and not any('${' in v for v in values):
                    inherited=pom(*values,out,seen); licenses=inherited.get('licenses',[])
            return {'url':repo+suffix,'sha256':sha(data),'licenses':licenses,'inherited':inherited}
        except (OSError,ValueError,ET.ParseError): pass
    return {'licenses':[],'error':'POM retrieval failed','requested':suffix}

def archive_evidence(data: bytes,prefix: str,out: pathlib.Path):
    notices=[]; native=[]
    if not zipfile.is_zipfile(io.BytesIO(data)): return notices,native
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for item in z.infolist():
            if item.is_dir(): continue
            if item.filename.endswith('.so'):
                native.append({'path':item.filename,'sha256':sha(z.read(item))})
            elif re.search(r'(^|[/_.-])(license|licence|notice|copying|copyright)',item.filename,re.I):
                content=z.read(item)
                target=prefix+'-'+re.sub('[^a-zA-Z0-9_.-]','_',item.filename)
                (out/target).write_bytes(content)
                notices.append({'archivePath':item.filename,'file':target,'sha256':sha(content)})
            elif item.filename=='classes.jar':
                a,b=archive_evidence(z.read(item),prefix+'-classes',out); notices+=a; native+=b
    return notices,native

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--resolved',required=True); ap.add_argument('--out',required=True)
    args=ap.parse_args(); out=pathlib.Path(args.out); out.mkdir(parents=True,exist_ok=True)
    evidence=out/'evidence'; evidence.mkdir(exist_ok=True)
    rows=json.loads(pathlib.Path(args.resolved).read_text()); grouped={}
    for row in rows:
        key=(row['group'],row['name'],row['version'],row['artifact'])
        grouped.setdefault(key,[]).append(row['scope'])
    components=[]; unknown=[]
    for (group,name,version,artifact),scopes in sorted(grouped.items()):
        path=pathlib.Path(artifact)
        entry={'group':group,'name':name,'version':version,'scopes':sorted(set(scopes)),
               'purl':f'pkg:maven/{group}/{name}@{version}'}
        if group.startswith('com.sirpaul.stablear'):
            entry.update(policy='LicenseRef-Owner-Commercial-Terms-Pending',source='repository original code; no public grant added')
        else:
            entry['pom']=pom(group,name,version,evidence)
            policy=POLICY.get(group)
            if policy: entry.update(policy=policy[0],primarySource=policy[1])
            else: entry['policy']='REVIEW_REQUIRED'; unknown.append(entry['purl'])
        if path.exists():
            data=path.read_bytes(); entry['sha256']=sha(data)
            entry['embeddedNotices'],entry['nativeLibraries']=archive_evidence(data,group+'-'+name+'-'+version,evidence)
        else: entry['artifactError']='Artifact not built/present'; unknown.append(entry['purl'])
        components.append(entry)
    report={'schema':'stablear-license-evidence-1','components':components,'unknown':unknown,
        'commercialReleaseCleared':False,
        'releaseBlockers':['Owner commercial licence/EULA not finalized',
            'ARCore host terms acceptance, user disclosure and independent-value review',
            'Native OpenCV and ARCore subcomponent provenance must be reconciled with exact binary notices'],
        'scope':'Actual resolved SDK/demo runtime and compile artifacts; build tools are documented separately. No model weights included.'}
    (out/'license-report.json').write_text(json.dumps(report,indent=2)+'\n')
    bom={'bomFormat':'CycloneDX','specVersion':'1.5','version':1,'components':[
        {'type':'library','group':c['group'],'name':c['name'],'version':c['version'],'purl':c['purl'],
         **({'hashes':[{'alg':'SHA-256','content':c['sha256']}]} if 'sha256' in c else {})} for c in components]}
    (out/'sbom.cdx.json').write_text(json.dumps(bom,indent=2)+'\n')
    print(f'Inventoried {len(components)} resolved artifacts; {len(unknown)} unknown/unavailable. Commercial clearance remains blocked.')
    if unknown: raise SystemExit('Unreviewed dependency: '+', '.join(unknown))
if __name__=='__main__': main()
