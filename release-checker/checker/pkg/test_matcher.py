from .matcher import Matcher


def test_matcher_pass():
    component = "tidb"
    version_string = """Verbose: Initialize repository finished in 1.131569ms\nStarting component `tidb`: /home/lob/.tiup/components/tidb/v4.0.10/tidb-server -V\nRelease Version: v4.0.10\nEdition: Community\nGit Commit Hash: dbade8cda4c5a329037746e171449e0a1dfdb8b3\nGit Branch: heads/refs/tags/v4.0.10\nUTC Build Time: 2021-01-15 02:59:27\nGoVersion: go1.13\nRace Enabled: false\nTiKV Min Version: v3.0.0-60965b006877ca7234adaced7890d7b029ed1306\nCheck Table Before Drop: false\n"""
    version = "4.0.10"
    hash = "dbade8cda4c5a329037746e171449e0a1dfdb8b3"
    edition = "community"

    matcher = Matcher(component, version_string, version, hash, edition)
    matcher.match()
    print(matcher.result)
    assert len(matcher.result) == 0
