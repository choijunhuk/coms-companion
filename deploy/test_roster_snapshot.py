import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('collector', Path(__file__).with_name('refresh-roster.py'))
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


class SnapshotTest(unittest.TestCase):
    def test_private_atomic_snapshot_and_invalid_refresh_preserve_previous(self):
        member = {'sourceId': 'eligible-1', 'studentId': 'TEST-1', 'name': '가상 회원', 'phone': '', 'generation': '예시'}
        snapshot = {'source': 'coms-eligible-members', 'updatedAt': '2026-10-14T00:00:00Z', 'members': [member]}
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'roster.json'
            collector.save_snapshot(snapshot, target)
            original = target.read_bytes()
            self.assertEqual(json.loads(original), snapshot)
            self.assertEqual(target.stat().st_mode & 0o777, 0o600)
            invalid = [
                [member, member],
                [{**member, 'name': 'x' * 101}],
                [{**member, 'generation': 'bad\nvalue'}],
                [{**member, 'generation': '😀' * 21}],
                [{**member, 'generation': 'A\u0085B'}],
                [{**member, 'studentId': '\u00a0TEST-1'}],
                [{**member, 'name': '\ufeff'}],
            ]
            for rows in invalid:
                with self.subTest(rows=rows):
                    with self.assertRaises(ValueError):
                        collector.save_snapshot({**snapshot, 'members': rows}, target)
                    self.assertEqual(target.read_bytes(), original)
                    self.assertFalse(list(target.parent.glob('.roster-*')))

    def test_missing_student_ids_preserve_distinct_source_records(self):
        rows = [{'sourceId': str(index), 'studentId': '', 'name': '가상 회원', 'phone': '', 'generation': ''}
                for index in [1, 2]]
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'roster.json'
            snapshot = {'source': 'coms-eligible-members', 'updatedAt': '2026-10-14T00:00:00Z', 'members': rows}
            collector.save_snapshot(snapshot, target)
            self.assertEqual(json.loads(target.read_bytes())['members'], rows)


if __name__ == '__main__':
    unittest.main()
