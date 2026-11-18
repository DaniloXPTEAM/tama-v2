"""SQLite execution and static wiring; not a Room or device test."""
import json
import re
import sqlite3
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

MAIN = Path(__file__).resolve().parents[1] / "android/src/android/app/src/main/java"
DAO = MAIN / "com/openminis/app/data/db/ChatDao.kt"
VM = MAIN / "com/openminis/app/ui/chat/ChatViewModel.kt"


class ParallelUsageOrder(unittest.TestCase):
    def test_usage_rows_follow_chat_order_and_remain_session_scoped(self):
        source = DAO.read_text()
        match = re.search(r'@Query\("([^"\n]+)"\)\s+suspend fun tokenUsagesInChatOrder', source)
        self.assertIsNotNone(match)
        db = sqlite3.connect(":memory:")
        self.addCleanup(db.close)
        db.execute("CREATE TABLE messages(id TEXT, session_id TEXT, sort_order INTEGER, created_at INTEGER, token_usage TEXT)")
        value = lambda n: json.dumps({"latestContextTokens": n})
        db.executemany("INSERT INTO messages VALUES (?, ?, ?, ?, ?)", [
            ("z", "one", 3, 100, value(300)),
            ("a", "one", 1, 900, value(100)),
            ("y", "other", 4, 999, value(999)),
            ("c", "one", 2, 200, None),
            ("b", "one", 2, 300, value(200)),
            ("x", "one", 3, 100, value(250)),
        ])
        rows = db.execute(match.group(1), {"sessionId": "one"}).fetchall()
        self.assertEqual([100, 200, 250, 300], [json.loads(row[0])["latestContextTokens"] for row in rows])
        self.assertEqual(6, db.execute("SELECT count(*) FROM messages").fetchone()[0])
        self.assertEqual([], db.execute(match.group(1), {"sessionId": "missing"}).fetchall())

    def test_only_parallel_usage_sheet_uses_the_ordered_query(self):
        source = VM.read_text().split("suspend fun loadSessionTokenStats()", 1)[1].split("// [T-android-split-chat]", 1)[0]
        self.assertIn('BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test"', source)
        self.assertIn('sessionTokenUsagesInChatOrder(sid) else chatRepository.sessionTokenUsages(sid)', source)
        self.assertIn('if (ctx > 0) context = ctx', source)
        self.assertIn('reportedInput != null && reportedOutput != null', source)

    def test_parallel_sheet_does_not_show_unknown_usage_as_zero(self):
        text = (MAIN / 'com/openminis/app/ui/chat/TokenUsageSheet.kt').read_text()
        self.assertIn('val usageKnown = !parallelTest || s?.hasReportedUsage == true', text)
        self.assertIn('if (parallelTest && (s == null || s.context <= 0)) "—"', text)
        self.assertIn('if (parallelTest && s == null) "—"', text)
        self.assertIn('R.string.nm_usage_reports_note', text)
        res = MAIN.parent / 'res'
        files = list(res.glob('values*/nm_strings.xml'))
        self.assertGreaterEqual(len(files), 17)
        for file in files:
            root = ET.parse(file).getroot()
            for name in ('nm_usage_reports_note', 'nm_loop_estimate_explanation'):
                self.assertEqual(1, len(root.findall(f"string[@name='{name}']")), file)


if __name__ == "__main__":
    unittest.main()
