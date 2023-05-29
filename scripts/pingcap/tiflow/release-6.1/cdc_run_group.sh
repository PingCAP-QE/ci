#!/bin/bash

# This script split the integration tests into 10 groups to support parallel group tests execution.
# all the integration tests are located in tests/integration_tests directory. only the directories
# containing run.sh will be considered as integration tests. the script will print the total # # # number

# usage: ./cdc_run_group.sh G0  to run the integration tests in group 0
# current supported groups are G0, G1, G2, G3, G4, G5, G6, G7, G8, G9


set -eo pipefail

# Step 1
directories=()
git_repo_dir=$(git rev-parse --show-toplevel)
for d in $(find ${git_repo_dir}/tests/integration_tests/* -maxdepth 0 -type d); do
    if [ -e "$d/run.sh" ]; then
        directories+=("$(basename $d)")
    fi
done

# Sort the directories
IFS=$'\n' directories=($(sort <<<"${directories[*]}"))
unset IFS

# Print the total number of directories
echo "Total number of directories: ${#directories[@]}"

# Step 2 & 3
split_length=$((${#directories[@]}/10))
remainder=$((${#directories[@]}%10))

grouped_directories=()
for ((i=0; i<10; i++)); do
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


CUR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

sink_type=$1
test_group=$2
group_index="${test_group:1}"
test_names=${grouped_directories[${group_index}]}
if [[ -n $test_names ]]; then
    echo ""
    echo "Running tests: $test_names"
    echo "Sink type: $sink_type"
	"${CUR}"/run.sh "${sink_type}" "${test_names}"
fi
