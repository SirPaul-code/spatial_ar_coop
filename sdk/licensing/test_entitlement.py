#!/usr/bin/env python3
import base64, subprocess, sys, tempfile, time
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
def unb64(s): return base64.urlsafe_b64decode(s+'='*((4-len(s)%4)%4))
with tempfile.TemporaryDirectory() as td:
    td=Path(td); subprocess.check_call([sys.executable,str(Path(__file__).with_name('generate_dev_key.py')),str(td)],stdout=subprocess.DEVNULL); now=int(time.time())
    token=subprocess.check_output([sys.executable,str(Path(__file__).with_name('issue_entitlement.py')),'--private-key',str(td/'private.pem'),'--customer-id','test_customer','--app-id','com.example.app','--platform','android','--features','tracking,vision','--not-before',str(now-5),'--expires',str(now+3600),'--grace','600'],text=True).strip()
    prefix,payload64,sig64=token.split('.'); assert prefix=='STABLEAR1'; payload=unb64(payload64).decode(); assert 'product_id=stablear\n' in payload and 'features=tracking,vision\n' in payload
    public=serialization.load_pem_public_key((td/'public.pem').read_bytes()); public.verify(unb64(sig64),f'{prefix}.{payload64}'.encode(),ec.ECDSA(hashes.SHA256()))
    bad=bytearray(unb64(sig64)); bad[-1]^=1
    try: public.verify(bytes(bad),f'{prefix}.{payload64}'.encode(),ec.ECDSA(hashes.SHA256())); raise AssertionError('tamper accepted')
    except Exception as e:
        if isinstance(e,AssertionError): raise
    print('PASS: STABLEAR1 P-256 issuer/signature contract')
