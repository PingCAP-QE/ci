#!/bin/bash

# This script split the integration tests into 16 groups to support parallel group tests execution.
# all the integration tests are located in br/tests directory. only the directories
# containing run.sh will be considered as integration tests. the script will print the total cases number

# usage: ./br-lightning_run_group.sh G0  to run the integration tests in group 0
# current supported groups are G0, G1, G2, G3, G4, G5, G6, G7, G8, G9, G10, G11, G12, G13, G14, G15


set -eo pipefail

# Step 1
directories=()
CUR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# filter br and lightning directories
for d in $(find ${CUR}/* -maxdepth 0 -type d | grep -Ev "docker_compatible_s3|docker_compatible_gcs"); do
    if [ -e "$d/run.sh" ]; then
        directories+=("$(basename $d)")
    fi
done

# Sort the directories
IFS=$'\n' directories=($(sort <<<"${directories[*]}"))
unset IFS

# Print the total number of directories
echo "Total number of valid directories: ${#directories[@]}"

# Step 2 & 3
split_length=$((${#directories[@]}/16))
remainder=$((${#directories[@]}%16))

grouped_directories=()
for ((i=0; i<16; i++)); do
    start_index=$((i*split_length))
    if [[ $i -lt $remainder ]]; then
        start_index=$((start_index+i))
        length=$((split_length+1))
    else
        start_index=$((start_index+remainder))
        length=$split_length
    fi
    group=(${directories[@]:start_index:length})
    grouped_directories+=("${group[*]}")
done

# Step 4 & 5
for ((i=0; i<${#grouped_directories[@]}; i++)); do
    group=${grouped_directories[i]}
    # Convert the group string back to an array to get the count
    IFS=' ' read -r -a array <<< "$group"
    echo "Number of directories in group$(($i)): ${#array[@]}"
    echo "$group"
done


test_group=$1
group_index="${test_group:1}"
test_names=${grouped_directories[${group_index}]}
if [[ -n $test_names ]]; then
    echo ""
    echo "Running tests in group${group_index}: $test_names"

    for case_name in $test_names; do
        echo "==== Run case: ${case_name} ===="
        rm -rf /tmp/backup_restore_test
        mkdir -p /tmp/backup_restore_test
        # lightning_examples test need mv file from examples pkg.
        # so we should overwrite the path.
        export EXAMPLES_PATH=br/pkg/lightning/mydump/examples
        TEST_NAME=${case_name} ${CUR}/run.sh
    done
fi
