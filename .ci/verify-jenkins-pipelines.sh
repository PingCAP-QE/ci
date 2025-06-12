#!/usr/bin/env bash

set -eo pipefail

# Linting via HTTP POST using curl
# curl (REST API)
# Assuming "anonymous read access" has been enabled on your Jenkins instance.
# JENKINS_URL=[root URL of Jenkins controller]
# JENKINS_CRUMB is needed if your Jenkins controller has CRSF protection enabled as it should

JENKINS_CRUMB=$(curl -fsS "$JENKINS_URL/crumbIssuer/api/json" | jq .crumb)
SCRIPT_DIR="$(realpath $(dirname "${BASH_SOURCE[0]}"))"

if command -v parallel > /dev/null; then
    find pipelines -name "*.groovy" | parallel -j+0 "$SCRIPT_DIR/verify-jenkins-pipeline-file.sh"
else
    for f in $(find pipelines -name "*.groovy"); do
        "$SCRIPT_DIR/verify-jenkins-pipeline-file.sh" "$f"
    done
fi
