#!/bin/bash


# =============================================================================
# Variables
# s1 pd_branch
# $2 tikv_branch

# usage:
# ./download-dependency-binary.sh <pd_branch> <tikv_branch>
# =============================================================================

# exit on error
# exit if no argument is provided
set -eu

PD_BRANCH=$1
TIKV_BRANCH=$2

echo "PD_BRANCH: $PD_BRANCH"
echo "TIKV_BRANCH: $TIKV_BRANCH"

echo "FILE_SERVER_URL: $FILE_SERVER_URL"

# FILE_SERVER_URL need to be set in the environment
FILE_SERVER_URL="http://fileserver.pingcap.net"

PD_SHA1_URL="${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1"
TIKV_SHA1_URL="${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1"

PD_SHA1=$(curl "$PD_SHA1_URL")
TIKV_SHA1=$(curl "$TIKV_SHA1_URL")

echo "PD_SHA1: ${PD_SHA1}"
echo "TIKV_SHA1: ${TIKV_SHA1}"


PD_DOWNLOAD_URL="${FILE_SERVER_URL}/download/builds/pingcap/pd/${PD_SHA1}/centos7/pd-server.tar.gz"
TIKV_DOWNLOAD_URL="${FILE_SERVER_URL}/download/builds/pingcap/tikv/${TIKV_SHA1}/centos7/tikv-server.tar.gz"


echo "PD_DOWNLOAD_URL: ${PD_DOWNLOAD_URL}"
echo "TIKV_DOWNLOAD_URL: ${TIKV_DOWNLOAD_URL}"


mkdir -p ./tmp/tikv-server
mkdir -p ./tmp/pd-server
echo "start to download tikv-server from fileserver"
curl ${TIKV_DOWNLOAD_URL} | tar -xz -C ./tmp/tikv-server
echo "start to download pd-server from fileserver"
curl ${PD_DOWNLOAD_URL} | tar -xz -C ./tmp/pd-server

mkdir -p bin
cp ./tmp/tikv-server/bin/tikv-server ./bin/tikv-server
cp ./tmp/pd-server/bin/pd-server ./bin/pd-server

# tikv-server and pd-server are placed in dir $(pwd)/bin
# Feel free to use them.