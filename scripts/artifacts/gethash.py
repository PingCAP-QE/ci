#!/usr/bin/env python3

"""
This script is used to get git hash from GitHub API for devbuild pipeline.
It provides functions to:
1. Parse organization and repository name from input
2. Get git hash by tag or branch name
3. Handle GitHub API authentication and requests

The script is primarily used in devbuild pipeline to:
- Resolve exact commit hashes for building specific versions
- Map repository names to their correct organizations
- Support both tag-based and branch-based versioning

Example usage in devbuild:
- Get hash for a specific tag: python gethash.py -repo tidb -version v5.0.0
- Get hash for a specific tag: python gethash.py -repo tidb -version tag/v5.0.0
- Get hash for a branch: python gethash.py -repo tikv -version master
- Get hash for a pull request: python gethash.py -repo tidb -version pull/12345
- Get hash for a commit: python gethash.py -repo tidb -version 10e4318eb8b3337ae493b8220ff296b544603c3e
- Get hash for a commit: python gethash.py -repo tidb -version commit/10e4318eb8b3337ae493b8220ff296b544603c3e
"""


import urllib.request as urllib2
import argparse
import json
import re
import os

token = os.environ.get('GHTOKEN')


def org_repo_parse(repo):
    if match := re.match(r'([\w-]+)/([\w-]+)', repo):
        groups = match.groups()
        return (groups[0], groups[1])
    else:
        repo_org_mapping = {
            "tikv": "tikv",
            "importer": "tikv",
            "pd": "tikv",
            "TiBigData": "tidb-incubator"
        }
        org = repo_org_mapping.get(repo, "pingcap")
        return (org, repo)


def base_url(repo):
    org, repo = org_repo_parse(repo)
    return "https://api.github.com/repos/" + org + '/' + repo


def gh_http_get(urlstr):
    req = urllib2.Request(urlstr)
    if not token:
        raise Exception("no github token")
    req.add_header('Authorization', 'token %s' % token)
    response = urllib2.urlopen(req)
    data = json.load(response)
    return data


def get_hash_by_tag(repo, tag):
    urlstr = base_url(repo) + "/git/refs/tags/" + tag
    data = gh_http_get(urlstr)
    if data["object"]["type"] == "commit":
        return data["object"]["sha"].strip()
    tag_data = gh_http_get(data["object"]["url"])
    return tag_data["object"]["sha"].strip()


def get_hash_by_branch_from_github(repo, branch):
    urlstr = base_url(repo) + "/git/refs/heads/" + branch
    data = gh_http_get(urlstr)
    return data["object"]["sha"].strip()


def get_hash_by_pr_from_github(repo, pr):
    urlstr = base_url(repo) + "/pulls/{}".format(pr)
    data = gh_http_get(urlstr)
    return data["head"]["sha"].strip()


def get_hash_by_branch_from_fileserver(repo, branch, fileserver):
    urlstr = fileserver + "/download/refs/pingcap/" + repo + "/" + branch + "/sha1"
    req = urllib2.Request(urlstr)
    response = urllib2.urlopen(req)
    return response.read().decode().strip()


def get_hash_main(args):
    if re.match(r'[0-9a-fA-F]{40}', args.version):
        hash = args.version
    elif args.version.startswith("commit/"):
        hash = args.version[7:]
    elif args.version[0] == 'v':
        hash = get_hash_by_tag(args.repo, args.version)
    elif args.version.startswith("tag/"):
        hash = get_hash_by_tag(args.repo, args.version[4:])
    elif args.version.startswith("pull/"):
        hash = get_hash_by_pr_from_github(args.repo, args.version[5:])
    elif args.version.startswith("branch/"):
        hash = get_hash_by_branch_from_github(args.repo, args.version[7:])
    elif args.version.startswith("tag/"):
        hash = get_hash_by_branch_from_github(args.repo, args.version[4:])
    else:
        hash = get_hash_by_branch_from_github(args.repo, args.version)
    return hash


def main(args):
    if args.repo is None:
        raise ValueError("repo parameter is required")
    if args.version is None:
        raise ValueError("version parameter is required")
    hash = get_hash_main(args)
    print(hash)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Get git hash from GitHub API for devbuild pipeline",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s -repo tidb -version v8.0.0                    # Get hash for tag
  %(prog)s -repo tikv -version master                    # Get hash for branch
  %(prog)s -repo tidb -version pull/12345                # Get hash for PR
  %(prog)s -repo pd -version branch/release-8.0          # Get hash for specific branch
  %(prog)s -repo tidb -version commit/abc123def456       # Use specific commit hash(40 characters)
  %(prog)s -repo pingcap/tidb -version v8.0.0            # Specify org/repo explicitly
        """)
    parser.add_argument("-repo", type=str,
                        help="GitHub repository name (e.g., 'tidb', 'tikv', 'pingcap/tidb')")
    parser.add_argument("-version", type=str,
                        help="Version specification: tag (v8.0.0), branch (master), PR (pull/123), or commit hash")
    args = parser.parse_args()
    main(args)
