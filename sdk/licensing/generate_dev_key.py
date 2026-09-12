#!/usr/bin/env python3
"""Generate a local P-256 keypair for StableAR development only."""
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization
import argparse
p=argparse.ArgumentParser(); p.add_argument('directory', nargs='?', default='.stablear-dev-keys'); a=p.parse_args()
out=Path(a.directory); out.mkdir(parents=True, exist_ok=True)
private=ec.generate_private_key(ec.SECP256R1())
(out/'private.pem').write_bytes(private.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
(out/'public.pem').write_bytes(private.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo))
print(out/'private.pem'); print(out/'public.pem')
