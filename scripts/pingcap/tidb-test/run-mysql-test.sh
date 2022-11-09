#! /bin/bash

test_suites_path=$1
echo "test path: ${test_suites_path}"

cd $test_suites_path
chmod +x test.sh
bash test.sh