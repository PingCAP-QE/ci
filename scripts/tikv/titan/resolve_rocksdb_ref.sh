#!/usr/bin/env bash
set -euo pipefail

branch="${1:-${PULL_BASE_REF:-}}"

case "${branch}" in
  tikv-7.5)
    rocksdb_repo="https://github.com/tikv/rocksdb.git"
    rocksdb_ref="6.29.tikv"
    rocksdb_sha="21be567683b709fc1e5dbe25394579ee45529212"
    ;;
  tikv-7.1)
    rocksdb_repo="https://github.com/tikv/rocksdb.git"
    rocksdb_ref="6.29.tikv"
    rocksdb_sha="21be567683b709fc1e5dbe25394579ee45529212"
    ;;
  tikv-6.5)
    rocksdb_repo="https://github.com/tikv/rocksdb.git"
    rocksdb_ref="tikv-6.5"
    rocksdb_sha="13e7fa00fde49656a4f96ee7b1f2342b0ff6fb70"
    ;;
  tikv-6.1)
    rocksdb_repo="https://github.com/tikv/rocksdb.git"
    rocksdb_ref="6.4.tikv"
    rocksdb_sha="9ca5afb9be6d437be7e17457cef5de5ddcfdac04"
    ;;
  tikv-5.2 | tikv-5.0 | tikv-4.x)
    rocksdb_repo="https://github.com/pingcap/rocksdb.git"
    rocksdb_ref="6.4.tikv"
    # 6.4.tikv kept moving after these Titan branches were cut. Pin to a
    # pre-revert commit that still provides the historical MultiBatchWrite API.
    rocksdb_sha="6b97c0567c4694ef1d06374d54bf582e541d215d"
    ;;
  *)
    echo "unsupported titan base branch: ${branch}" >&2
    exit 1
    ;;
esac

printf '%s\t%s\t%s\t%s\n' \
  "${branch}" \
  "${rocksdb_repo}" \
  "${rocksdb_ref}" \
  "${rocksdb_sha}"
