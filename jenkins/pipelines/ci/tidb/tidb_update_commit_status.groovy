properties([
        parameters([
                choice(
                        choices: ['success', 'error', 'failure', 'pending'],
                        name: 'STATUS'
                ),
                string(
                        defaultValue: '',
                        name: 'TIDB_COMMIT_ID',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'CONTEXT',
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'DESCRIPTION',
                        trim: true,
                ),
                string(
                        defaultValue: '',
                        name: 'BUILD_URL',
                        trim: true
                )
        ])
])

node("github-status-updater") {
    stage("Print env") {
        echo "context = ${CONTEXT}"
        echo "tidb_commit_id = ${TIDB_COMMIT_ID}"
        echo "status = ${STATUS}"
        echo "description = ${DESCRIPTION}"
        echo "build_url = ${BUILD_URL}"
    }

    stage("Update commit status") {
        container("github-status-updater") {
            withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                sh '''
                set +x
                github-status-updater \
                    -action update_state \
                    -token ${TOKEN} \
                    -owner pingcap \
                    -repo tidb \
                    -ref  ${TIDB_COMMIT_ID} \
                    -state ${STATUS} \
                    -context "${CONTEXT}" \
                    -description "${DESCRIPTION}" \
                    -url "${BUILD_URL}"
                '''
            }
        }
    }
}
