#!/usr/bin/env bash

set -eo pipefail

# Linting via HTTP POST using curl
# curl (REST API)
# Assuming "anonymous read access" has been enabled on your Jenkins instance.
# JENKINS_URL=[root URL of Jenkins controller]
# JENKINS_CRUMB is needed if your Jenkins controller has CRSF protection enabled as it should

function main() {
    local f="$1"
    printf "validating %s:\t" "$f"
    result="$(curl -fsS -X POST -H "$JENKINS_CRUMB" -F "jenkinsfile=<${f}" "$JENKINS_URL/pipeline-model-converter/validate")"
    echo "$result"
    echo "$result" | grep "successfully validated" >/dev/null || exit 1
}

main "$@"
