#! /usr/bin/env bash

func() {
    echo "Usage:"
    echo "    integration_prepare.sh -k tikv_sha1 -p pd_sha1 -d tidb_sha1 -w workspace"
    exit 1
}

while getopts 'hk:p:d:w:' OPT; do
    case $OPT in
        k) tikv_sha1="$OPTARG";;
        p) pd_sha1="$OPTARG";;
        d) tidb_sha1="$OPTARG";;
        w) workspace="$OPTARG";;
        h) func;;
        ?) func;;
    esac
done

tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
pd_url="${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
tidb_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb/pr/${tidb_sha1}/centos7/tidb-server.tar.gz"

while ! curl --output /dev/null --silent --head --fail "${tikv_url}"; do sleep 1; done
curl -C - --retry 3 -f "${tikv_url}" | tar xz bin

while ! curl --output /dev/null --silent --head --fail "${pd_url}"; do sleep 1; done
curl -C - --retry 3 -f "${pd_url}" | tar xz bin

mkdir -p ./tidb-src
curl -C - --retry 3 -f "${tidb_url}" | tar xz -C ./tidb-src
ln -s $(pwd)/tidb-src "${workspace}/go/src/github.com/pingcap/tidb"
mv tidb-src/bin/tidb-server ./bin/tidb-server