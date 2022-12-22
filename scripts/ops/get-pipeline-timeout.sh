#!/usr/bin/env bash
# -*- coding: utf-8 -*-

# param $1 pipeline dir path
function main() {
    local pipeline_dir="${1}"
    for f in $(find "${pipeline_dir}" -type f -name "*.groovy"); do
        echo -ne "$f:\t"
        grep 'timeout(' "$f" | head -1 | grep -Eo "\d+"
    done

    return 0
}

main "$@"
