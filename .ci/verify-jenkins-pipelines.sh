#!/bin/bash +x

# Linting via HTTP POST using curl
# curl (REST API)
# Assuming "anonymous read access" has been enabled on your Jenkins instance.
# JENKINS_URL=[root URL of Jenkins controller]
# JENKINS_CRUMB is needed if your Jenkins controller has CRSF protection enabled as it should

JENKINS_CRUMB=$(curl -fsS "$JENKINS_URL/crumbIssuer/api/json" | jq .crumb)
for f in $(find pipelines jenkins/pipelines/cd -name "*.groovy"); do
    echo -ne "validating $f:\\t"
    result="$(curl -fsS -X POST -H "$JENKINS_CRUMB" -F "jenkinsfile=<${f}" "$JENKINS_URL/pipeline-model-converter/validate")"
    echo "$result"
    echo "$result" | grep "successfully validated" >/dev/null || exit 1
done
