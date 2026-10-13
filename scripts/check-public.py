#!/usr/bin/env python3
"""Check tracked source for private artifacts and recognizable credentials."""
import pathlib
import re
import subprocess
import sys

root = pathlib.Path(__file__).resolve().parents[1]
names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
private_suffixes = {'.db', '.sqlite', '.sqlite3', '.jks', '.keystore', '.p12', '.pem', '.key', '.apk', '.aab', '.comsbak'}
private_dirs = {'private-data', 'backups', 'exports', 'logs', '.omx', '.omc', 'node_modules', '.tmp'}
credential_patterns = [
    re.compile(rb'gh[pousr]_[A-Za-z0-9]{20,}'),
    re.compile(rb'(?:AKIA|ASIA)[A-Z0-9]{16}'),
    re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
]
errors = []
for name in filter(None, names):
    path = pathlib.PurePosixPath(name)
    if path.suffix in private_suffixes or private_dirs.intersection(path.parts):
        errors.append(f'Private artifact: {name}')
    if (path.name.startswith('.env') and path.name != '.env.example') or path.name in {'local.properties', 'keystore.properties'}:
        errors.append(f'Private configuration: {name}')
    absolute = root / name
    if absolute.is_file():
        data = absolute.read_bytes()
        if any(pattern.search(data) for pattern in credential_patterns):
            errors.append(f'Credential-like value: {name}')
if errors:
    print('\n'.join(errors), file=sys.stderr)
    raise SystemExit(1)
print(f'Public source check passed for {sum(bool(name) for name in names)} tracked files. Review human data separately.')
