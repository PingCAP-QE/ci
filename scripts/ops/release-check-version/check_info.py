import subprocess
import sys
import re
import argparse


def check_version(version_info, expected_version, expected_edition, expected_commit_hash, check_version=True,
                  check_edition=True, check_commit_hash=True):
    output = version_info

    # 初始化检查结果
    # 一些 binary 可能不会输出所有信息，所以默认为 True
    check_results = {
        'Release Version': (False if check_version else True, '', expected_version),
        'Edition': (False if check_edition else True, '', expected_edition),
        'Git Commit Hash': (False if check_commit_hash else True, '', expected_commit_hash)
    }
    print("need to check version: ", check_version)
    print("need to check edition: ", check_edition)
    print("need to check commit hash: ", check_commit_hash)

    # 有的 binary version 是这种格式：v8.0.0，有的是这种格式：8.0.0
    # 所以这里需要统一格式，只要是符合 semver 规范的版本号，就认为是相同的版本
    def normalize(ver):
        return ver.strip().lstrip('v')

    # 解析输出并检查版本信息
    # 使用正则表达式匹配 Release Version, Edition, 和 Git Commit Hash，忽略大小写
    # dumpling -V 输出的版本信息中，大小写不标准，所以这里忽略大小写
    version_match = re.search(r'(?i)release\s+version:\s*v?(\d+\.\d+\.\d+)', output)
    edition_match = re.search(r'(?i)edition:\s*(\w+)', output)
    commit_hash_match = re.search(r'(?i)git\s+commit\s+hash:\s*([0-9a-fA-F]+)', output)

    # 处理 Release Version
    if version_match:
        actual_version = normalize(version_match.group(1))
        check_results['Release Version'] = (
            actual_version == normalize(expected_version) if check_version else True, actual_version, expected_version)

    # 处理 Edition
    if edition_match:
        actual_edition = edition_match.group(1)
        check_results['Edition'] = (
            actual_edition.lower() == expected_edition.lower() if check_edition else True, actual_edition,
            expected_edition)

    # 处理 Git Commit Hash
    if commit_hash_match:
        actual_commit_hash = commit_hash_match.group(1)
        check_results['Git Commit Hash'] = (
            actual_commit_hash == expected_commit_hash if check_commit_hash else True, actual_commit_hash,
            expected_commit_hash)

    # 检查结果汇总和输出
    all_match = True
    for key, (match, actual, expected) in check_results.items():
        if not match:
            all_match = False
            print(f"{key} does not match. Expected: '{expected}', Actual: '{actual}'")

    if all_match:
        print("All version information matches the expected values.")
    else:
        print("Some version information does not match the expected values.")

    return all_match


if __name__ == "__main__":
    info = """
    TiKV
    Release Version:   8.0.0-alpha
    Edition:           Community
    Git Commit Hash:   e4e273f758c289df9ddf47b73371185bf867b2cd
    Git Commit Branch: heads/refs/tags/v8.0.0-alpha
    UTC Build Time:    2024-02-06 11:42:45
    Rust Version:      rustc 1.77.0-nightly (89e2160c4 2023-12-27)
    Enable Features:   pprof-fp jemalloc mem-profiling portable sse test-engine-kv-rocksdb test-engine-raft-raft-engine cloud-aws cloud-gcp cloud-azure trace-async-tasks openssl-vendored
    Profile:           dist_release
    """
    expected_version = "v8.0.0-alpha"
    expected_edition = "Community"
    expected_commit = "e4e273f758c289df9ddf47b73371185bf867b2cd"

    check_version(info, expected_version, expected_edition, expected_commit, check_version=True, check_edition=True,
                  check_commit_hash=True)
