// REF: https://www.jenkins.io/doc/book/pipeline/syntax/#declarative-pipeline
// Keep small than 400 lines: https://issues.jenkins.io/browse/JENKINS-37984
// should triggerd for master and latest releases branches
@Library('tipipeline') _

final K8S_NAMESPACE = "jenkins-tidb"
final GIT_FULL_REPO_NAME = 'pingcap/tidb'
final POD_TEMPLATE_FILE = 'pipelines/pingcap/tidb/latest/pod-canary-scan-security.yaml'
final REFS = readJSON(text: params.JOB_SPEC).refs

pipeline {
    agent {
        kubernetes {
            namespace K8S_NAMESPACE
            yamlFile POD_TEMPLATE_FILE
        }
    }
    options {
        timeout(time: 30, unit: 'MINUTES')
    }
    stages {        
        stage('Checkout') {
            // FIXME(wuhuizuo): catch AbortException and set the job abort status
            // REF: https://github.com/jenkinsci/git-plugin/blob/master/src/main/java/hudson/plugins/git/GitSCM.java#L1161
            steps {
                dir('tidb') {
                    cache(path: "./", filter: '**/*', key: "git/pingcap/tidb/rev-${REFS.pulls[0].sha}", restoreKeys: ['git/pingcap/tidb/rev-']) {
                        retry(2) {
                            script {
                                prow.checkoutRefs(REFS)
                            }
                        }
                    }
                }
            }
        }
        stage("Create and wait") {
            options {
                timeout(time: 20, unit: 'MINUTES')
            }
            steps {
                container('deno') {
                    sh script: """ deno run --allow-net --allow-write scripts/plugins/security-scan.ts
                    --gitRefs '${REFS}' \
                    --cacheKey "git/pingcap/tidb/rev-${REFS.pulls[0].sha}"
                    --serverBaseUrl http://sec-server.apps-sec.svc \
                    --token \$TOKEN \
                    --taskIdSaveFile task_id
                    --reportSaveFile report.md
                    """
                }
            }       
            post {
                always {
                    archiveArtifacts(artifacts: 'report.md', allowEmptyArchive: true)
                }
            }                 
        }       
    }
}
