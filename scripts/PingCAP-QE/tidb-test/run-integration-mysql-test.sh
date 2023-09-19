#! /usr/bin/env bash

echo "Script executed from: ${PWD}"

BASEDIR=$(dirname $0)
echo "Script location: ${BASEDIR}"

test_suites_path=$1
echo "test path: ${test_suites_path}"

bash ${BASEDIR}/scripts/ci/start_tikv.sh

cd $test_suites_path
chmod +x test.sh
bash test.sh -backlist=1
