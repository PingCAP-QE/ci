import os

import requests
import sys
import json


token = os.environ.get("GITHUB_TOKEN")
if not token:
    print("GITHUB_TOKEN is not set.")
    sys.exit(1)

headers = {"Authorization": f"token {token}"} if token else {}


def parse_release_version(branch):
    prefix = "release-"
    if not branch.startswith(prefix):
        return None
    version_part = branch[len(prefix):].split("-", 1)[0]
    try:
        return tuple(int(part) for part in version_part.split("."))
    except ValueError:
        return None


def is_branch_before_release(branch, target_version):
    version = parse_release_version(branch)
    if version is None:
        return False
    max_len = max(len(version), len(target_version))
    padded_version = version + (0,) * (max_len - len(version))
    padded_target = target_version + (0,) * (max_len - len(target_version))
    return padded_version < padded_target


def get_latest_commit_hash(repo, branch):
    url = f"https://api.github.com/repos/{repo}/commits/{branch}"
    response = requests.get(url, headers=headers)
    if response.status_code == 200:
        return response.json()['sha']
    else:
        print(f"Error fetching {repo} branch {branch}: {response.status_code}")
        return None


def main(branch, version):
    ticdc_repo = "pingcap/ticdc"
    if is_branch_before_release(branch, (8, 5)):
        ticdc_repo = "pingcap/tiflow"

    repos = {
        "binlog": "pingcap/tidb-binlog",
        "br": "pingcap/tidb",
        "tidb": "pingcap/tidb",
        "tikv": "tikv/tikv",
        "pd": "tikv/pd",
        "tiflash": "pingcap/tiflash",
        "ticdc": ticdc_repo,
        "dm": "pingcap/tiflow",
        "dumpling": "pingcap/tidb",
        "tidb-dashboard": "pingcap/tidb-dashboard",
        "lightning": "pingcap/tidb",
        "ng-monitoring": "pingcap/ng-monitoring",
        "enterprise-plugin": "pingcap-inc/enterprise-plugin"
    }

    output = {"docker_images": [], "tiup_packages": []}

    for name, repo in repos.items():
        commit_hash = get_latest_commit_hash(repo, branch)
        if commit_hash:
            entry = {"name": name, "version": version, "commit_hash": commit_hash}
            output["docker_images"].append(entry)
            output["tiup_packages"].append(entry)  # Assuming you want the same for tiup_packages

    with open(f"components-{version}.json", "w") as f:
        json.dump(output, f, indent=2)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: create_components_json.py <branch> <version>")
        sys.exit(1)
    branch, version = sys.argv[1], sys.argv[2]
    main(branch, version)
