#!/usr/bin/env bash

set -eo pipefail

# Linting via HTTP POST using curl
# curl (REST API)
# Assuming "anonymous read access" has been enabled on your Jenkins instance.
# JENKINS_URL=[root URL of Jenkins controller]
# JENKINS_CRUMB is needed if your Jenkins controller has CRSF protection enabled as it should

JENKINS_CRUMB=$(curl -fsS "$JENKINS_URL/crumbIssuer/api/json" | jq .crumb)
export JENKINS_CRUMB
SCRIPT_DIR="$(realpath "$(dirname "${BASH_SOURCE[0]}")")"

discover_pipelines() {
    # Legacy layout: pipelines/**/*.groovy
    if [ -d pipelines ]; then
        find pipelines -name "*.groovy"
    fi
    # New layout: jenkins/jobs/**/Jenkinsfile
    if [ -d jenkins/jobs ]; then
        find jenkins/jobs -name "Jenkinsfile"
    fi
}

if command -v parallel > /dev/null; then
    discover_pipelines | parallel -j4 "$SCRIPT_DIR/verify-jenkins-pipeline-file.sh"
else
    discover_pipelines | xargs -P 4 -n 1 "$SCRIPT_DIR/verify-jenkins-pipeline-file.sh"
fi
