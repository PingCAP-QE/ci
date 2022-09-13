#! /usr/bin/env bash

func() {
    echo "Usage:"
    echo "    integration_test.sh -n test_name -w workspace -t test_cmd"
    exit 1
}

while getopts 'hw:n:t:' OPT; do
    case $OPT in
        n) test_name="$OPTARG";;
        w) workspace="$OPTARG";;
        t) test_cmd="$OPTARG";;
        h) func;;
        ?) func;;
    esac
done

if [ -z "${test_name}" ]||[ -z "${workspace}" ]||[ -z "${test_cmd}" ];then
    echo "Missing options,exit"
    exit 1
fi

ps aux
set +e
killall -9 -r tidb-server
killall -9 -r tikv-server
killall -9 -r pd-server
rm -rf /tmp/tidb
rm -rf ./tikv ./pd
set -e

bin/pd-server --name=pd --data-dir=pd &>"pd_${test_name}.log" &
sleep 10
echo '[storage]\nreserve-space = "0MB"'> tikv_config.toml
bin/tikv-server -C tikv_config.toml --pd=127.0.0.1:2379 -s tikv --addr=0.0.0.0:20160 --advertise-addr=127.0.0.1:20160 &>"tikv_${test_name}.log" &
sleep 10
if [ -f test.sh ]; then awk 'NR==2 {print "set -x"} 1' test.sh > tmp && mv tmp test.sh && chmod +x test.sh; fi

export TIDB_SRC_PATH=${workspace}/go/src/github.com/pingcap/tidb
export log_level=debug
TIDB_SERVER_PATH=`pwd`/bin/tidb-server \
TIKV_PATH='127.0.0.1:2379' \
TIDB_TEST_STORE_NAME=tikv \
${test_cmd}