#!/usr/bin/env python3
"""Issue a STABLEAR1 ECDSA-P256/SHA-256 entitlement. Keep private keys out of client repos."""
import argparse, base64, re, time
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
TOKEN_RE=re.compile(r'^[A-Za-z0-9._:/+@-]{1,192}$')
def b64u(data: bytes)->str: return base64.urlsafe_b64encode(data).decode('ascii').rstrip('=')
def checked(name,value):
    if not TOKEN_RE.fullmatch(value): raise SystemExit(f'invalid {name}')
    return value
p=argparse.ArgumentParser(); p.add_argument('--private-key',required=True); p.add_argument('--product-id',default='stablear'); p.add_argument('--customer-id',required=True); p.add_argument('--app-id',required=True); p.add_argument('--platform',required=True,choices=['android','ios','openxr','unity']); p.add_argument('--features',default='tracking'); p.add_argument('--not-before',type=int,default=None); p.add_argument('--expires',type=int,required=True); p.add_argument('--grace',type=int,default=7*24*3600); a=p.parse_args()
nbf=int(time.time()) if a.not_before is None else a.not_before
if nbf<0 or a.expires<=nbf or a.grace<0 or a.grace>31*24*3600: raise SystemExit('invalid time bounds')
features=a.features.split(',')
if not features or any(not TOKEN_RE.fullmatch(x) for x in features) or len(a.features)>512: raise SystemExit('invalid features')
claims=[('product_id',checked('product_id',a.product_id)),('customer_id',checked('customer_id',a.customer_id)),('app_id',checked('app_id',a.app_id)),('platform',checked('platform',a.platform)),('features',a.features),('nbf',str(nbf)),('exp',str(a.expires)),('grace',str(a.grace))]
payload=''.join(f'{k}={v}\n' for k,v in claims).encode(); signing_input=f'STABLEAR1.{b64u(payload)}'
key=serialization.load_pem_private_key(Path(a.private_key).read_bytes(),password=None)
if not isinstance(key,ec.EllipticCurvePrivateKey) or not isinstance(key.curve,ec.SECP256R1): raise SystemExit('private key must be P-256')
sig=key.sign(signing_input.encode('ascii'),ec.ECDSA(hashes.SHA256())); print(f'{signing_input}.{b64u(sig)}')
