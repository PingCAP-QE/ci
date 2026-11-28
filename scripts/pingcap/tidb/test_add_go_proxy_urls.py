import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location("go_proxy_urls", pathlib.Path(__file__).with_name("add-go-proxy-urls.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class GoProxyURLsTest(unittest.TestCase):
    def rule(self, prefix="golang.org/x/sync@v0.7.0"):
        return '''    go_repository(
        name = "dependency",
        importpath = "original/import/path",
        sha256 = "unchanged-checksum",
        strip_prefix = "%s",
        urls = [
            "https://storage.googleapis.com/pingcapmirror/gomod/dependency.zip",
        ],
    )
''' % prefix

    def test_canonical_url_and_idempotence(self):
        before = self.rule()
        after = module.add_proxy_urls(before)
        added = '            "https://proxy.golang.org/golang.org/x/sync/@v/v0.7.0.zip",\n'
        self.assertIn(added, after)
        self.assertEqual(before, after.replace(added, ""))
        self.assertEqual(after, module.add_proxy_urls(after))

    def test_replacement_module_and_case_escaping(self):
        after = module.add_proxy_urls(self.rule("github.com/Azure/SDK@v1.0.0-RC1"))
        self.assertIn("https://proxy.golang.org/github.com/!azure/!s!d!k/@v/v1.0.0-!r!c1.zip", after)

    def test_non_module_archives_unchanged(self):
        before = self.rule("archive-root-without-version")
        self.assertEqual(before, module.add_proxy_urls(before))
        before = self.rule().replace("storage.googleapis.com/pingcapmirror/gomod", "example.com/source")
        self.assertEqual(before, module.add_proxy_urls(before))

    def test_multiple_rules_and_missing_strip_prefix(self):
        before = self.rule() + self.rule("example.com/module/v2@v2.1.0")
        after = module.add_proxy_urls(before)
        self.assertEqual(2, after.count("https://proxy.golang.org/"))
        before = self.rule().replace('        strip_prefix = "golang.org/x/sync@v0.7.0",\n', "")
        self.assertEqual(before, module.add_proxy_urls(before))


if __name__ == "__main__":
    unittest.main()
