"""Unit tests for the auto-merge gate in auto-handle-tidb-test-pr.py.

Run with: python3 -m unittest scripts/pingcap/tidb/test_auto_handle_tidb_test_pr.py
"""

import importlib.util
import pathlib
import sys
import types
import unittest

# The script only needs `requests` for the network calls, which these tests do
# not make; stub it so the module can be imported without the dependency.
if "requests" not in sys.modules:
    sys.modules["requests"] = types.ModuleType("requests")

SCRIPT = pathlib.Path(__file__).with_name("auto-handle-tidb-test-pr.py")


def load_script():
    spec = importlib.util.spec_from_file_location("auto_handle_tidb_test_pr", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def pull_request(**overrides):
    info = {
        "state": "open",
        "draft": False,
        "labels": [{"name": "lgtm"}],
        "mergeable": True,
        "mergeable_state": "clean",
        "head": {"repo": {"full_name": "PingCAP-QE/tidb-test"}},
    }
    info.update(overrides)
    return info


class AutoMergeBlockersTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.module = load_script()

    def test_approved_clean_pr_in_this_repository_has_no_blockers(self):
        self.assertEqual(self.module.auto_merge_blockers(pull_request()), [])

    def test_approved_label_also_counts(self):
        pr = pull_request(labels=[{"name": "approved"}])
        self.assertEqual(self.module.auto_merge_blockers(pr), [])

    def test_missing_approval_blocks_the_merge(self):
        pr = pull_request(labels=[{"name": "size/M"}])
        blockers = self.module.auto_merge_blockers(pr)
        self.assertTrue(any("lgtm/approved" in blocker for blocker in blockers))

    def test_blocking_label_blocks_the_merge(self):
        pr = pull_request(labels=[{"name": "lgtm"}, {"name": "do-not-merge/hold"}])
        blockers = self.module.auto_merge_blockers(pr)
        self.assertTrue(any("blocking labels" in blocker for blocker in blockers))

    def test_draft_blocks_the_merge(self):
        blockers = self.module.auto_merge_blockers(pull_request(draft=True))
        self.assertTrue(any("draft" in blocker for blocker in blockers))

    def test_fork_head_blocks_the_merge(self):
        pr = pull_request(
            head={"repo": {"full_name": "attacker/tidb-test"}},
        )
        blockers = self.module.auto_merge_blockers(pr)
        self.assertTrue(any("head repository" in blocker for blocker in blockers))

    def test_unknown_mergeability_blocks_the_merge(self):
        blockers = self.module.auto_merge_blockers(pull_request(mergeable=None))
        self.assertTrue(any("mergeable=None" in blocker for blocker in blockers))

    def test_blocked_mergeable_state_blocks_the_merge(self):
        pr = pull_request(mergeable_state="blocked")
        blockers = self.module.auto_merge_blockers(pr)
        self.assertTrue(any("mergeable_state" in blocker for blocker in blockers))

    def test_dirty_mergeable_state_blocks_the_merge(self):
        pr = pull_request(mergeable_state="dirty")
        blockers = self.module.auto_merge_blockers(pr)
        self.assertTrue(any("mergeable_state" in blocker for blocker in blockers))


if __name__ == "__main__":
    unittest.main()
