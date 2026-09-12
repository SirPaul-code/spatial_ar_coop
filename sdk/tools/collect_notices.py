#!/usr/bin/env python3
"""Snapshot primary licence texts and include full notices in the research demo."""
import hashlib,json,pathlib,urllib.request
ROOT=pathlib.Path(__file__).resolve().parents[1]
SOURCES={
 'opencv-4.12.0':'https://raw.githubusercontent.com/opencv/opencv/4.12.0/LICENSE',
 'arcore-current-license':'https://raw.githubusercontent.com/google-ar/arcore-android-sdk/main/LICENSE',
 'kotlin-1.9.24':'https://raw.githubusercontent.com/JetBrains/kotlin/v1.9.24/license/LICENSE.txt',
 'jetbrains-annotations':'https://raw.githubusercontent.com/JetBrains/java-annotations/master/LICENSE.txt',
}
def main():
 out=ROOT/'build/compliance/primary'; out.mkdir(parents=True,exist_ok=True)
 index=[]; text=['StableAR research build. Third-party licence evidence; not commercial release clearance.\n']
 for name,url in SOURCES.items():
  with urllib.request.urlopen(url,timeout=30) as response: data=response.read()
  (out/(name+'.txt')).write_bytes(data)
  index.append(dict(component=name,url=url,sha256=hashlib.sha256(data).hexdigest()))
  text += ['\n===== '+name+' =====\nSource: '+url+'\n',data.decode('utf-8')]
 (out/'sources.json').write_text(json.dumps(index,indent=2)+'\n')
 assets=ROOT/'demo/src/main/assets'; assets.mkdir(parents=True,exist_ok=True)
 (assets/'THIRD_PARTY_NOTICES.txt').write_text('\n'.join(text))
if __name__=='__main__': main()
