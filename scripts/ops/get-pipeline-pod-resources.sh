#!/usr/bin/env bash
# -*- coding: utf-8 -*-

# param $1 yq tool binary path
# param $2 pipeline dir path
function main() {
    local yq_bin="$1"
    local pipeline_dir="${2}"
    for f in $(find "${pipeline_dir}" -type f -name "pod-*.yaml"); do
        echo "$f:"
        "${yq_bin}" '.spec.containers[0].resources' "$f"
        echo '-------------------'
    done

    return 0
}

main "$@"
