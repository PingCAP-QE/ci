#! /usr/bin/env bash

function main() {
    cleanup
    rm -rf /tmp/tidb

    log_level=debug \
    UPGRADE_PART="tikv" \
    TIKV_PATH="${TIKV_PATH:-bin/tikv-server}" \
    TIDB_PATH="${TIDB_PATH:-bin/tidb-server}" \
    PD_PATH="${PD_PATH:-bin/pd-server}" \
    OLD_BINARY="${OLD_BINARY:-bin/tikv-server-old}" \
    NEW_BINARY="${TIKV_PATH:-bin/tikv-server}" \
    ./test.sh 2>&1
}

function cleanup() {
    killall -9 -r -q tidb-server
    killall -9 -r -q tikv-server
    killall -9 -r -q pd-server
}

exit_code=0
{ # try block
    main "$@"
} || { # catch block
   exit_code="$?"  # exit code of last command which is 44
}
# finally block:
cleanup

if [[ "$exit_code" != '0' ]]; then
   exit ${exit_code}
fi
