import argparse
import json
import os
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path

JS_TRIM = '\t\n\v\f\r \u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff'


def fetch_roster(container):
    query = """BEGIN READ ONLY;
SELECT COALESCE(json_agg(json_build_object(
  'sourceId', id::text,
  'studentId', COALESCE(btrim(student_id), ''),
  'name', COALESCE(btrim(name), ''),
  'phone', COALESCE(btrim(phone), ''),
  'generation', COALESCE(btrim(generation), '')
) ORDER BY id), '[]'::json) FROM eligible_members;
ROLLBACK;
"""
    result = subprocess.run(
        ['docker', 'exec', '-i', container, 'sh', '-c',
         'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
        input=query, text=True, capture_output=True, timeout=30, check=True,
    )
    members = json.loads(result.stdout)
    return {
        'source': 'coms-eligible-members',
        'updatedAt': datetime.now(timezone.utc).isoformat(timespec='seconds').replace('+00:00', 'Z'),
        'members': members,
    }


def save_snapshot(snapshot, destination):
    members = snapshot['members']
    if not isinstance(members, list) or len(members) > 5000:
        raise ValueError('Roster size is invalid')
    source_ids = set()
    limits = {'sourceId': 64, 'studentId': 64, 'name': 100, 'phone': 40, 'generation': 40}
    for member in members:
        if set(member) != set(limits) or any(
            not isinstance(member[key], str) for key in member
        ) or not member['sourceId'] or not member['name'].strip(JS_TRIM) or member['sourceId'] != member['sourceId'].strip(JS_TRIM) or member['studentId'] != member['studentId'].strip(JS_TRIM) or member['sourceId'] in source_ids:
            raise ValueError('Roster has invalid required fields')
        if any(len(member[key].encode('utf-16-le', errors='surrogatepass')) // 2 > limit
               or any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in member[key])
               for key, limit in limits.items()):
            raise ValueError('Roster field exceeds the API contract')
        source_ids.add(member['sourceId'])
    encoded = json.dumps(snapshot, ensure_ascii=False).encode('utf-8')
    if len(encoded) > 1024 * 1024:
        raise ValueError('Roster snapshot exceeds 1 MiB')
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, prefix='.roster-', delete=False) as stream:
            temporary = Path(stream.name)
            os.chmod(temporary, 0o600)
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, destination)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--container', default='coms-website-db-1')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    snapshot = fetch_roster(args.container)
    save_snapshot(snapshot, args.output)
    print(f"COMS roster snapshot refreshed: {len(snapshot['members'])} records")
