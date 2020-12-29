def label = "github"
def access_token
def BRANCH = RELEASE_BRANCH

podTemplate(label: label, idleMinutes: 120, containers: [
        containerTemplate(name: 'github', instanceCap: 1, alwaysPullImage: false, image: 'hub.pingcap.net/qa/ci-toolkit:latest-addhub', ttyEnabled: true, command: 'cat'),
]) {
    node(label) {
        try {
            container("github") {
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
//                 stage("pingcap.github.io push commit") {
//                     dir("/home/jenkins/agent/git/pingcap.github.io") {
//                         deleteDir()
//                         checkout scm: [$class           : 'GitSCM',
//                                       branches         : [[name: "src"]],
//                                       extensions       : [[$class: 'LocalBranch']],
//                                       userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/pingcap.github.io.git']]]
//                         sh """
// 				           printf 'import json, os
// with open("data/tidb_download_pkg.json", "r+") as f:
//     data = json.load(f)
//     data["versions"].append("${RELEASE_TAG}")
//     f.seek(0)
//     f.truncate()
//     json.dump(data, f, indent=4)
//     f.write(os.linesep)
//     f.close()' > tmp.py
//                         """
//                         sh """
//                         echo change before
//                         cat data/tidb_download_pkg.json
//                         python tmp.py
//                         echo change after
//                         cat data/tidb_download_pkg.json
//                         """

//                         withCredentials([string(credentialsId: 'sre-bot-new-token', variable: 'TOKEN')]) {
//                             sh """
//                         git rev-parse HEAD
//                         git add data/tidb_download_pkg.json
//                         git config --global user.email sre-bot@pingcap.com
//                         git config --global user.name sre-bot
//                         git commit -m "release ${RELEASE_TAG}"
//                         git remote add sre-bot  https://sre-bot:${TOKEN}@github.com/pingcap/pingcap.github.io
//                         GITHUB_TOKEN=${TOKEN} hub push sre-bot src
//                     """
//                         }

//                     }
//                 }
                if (RELEASE_TAG<"4.0.90"){
                    dir("/home/jenkins/agent/git/tidb-ansible") {
                        stage("clone tidb-ansible") {
                            deleteDir()
                            checkout scm: [$class           : 'GitSCM',
                                           branches         : [[name: "${BRANCH}"]],
                                           extensions       : [[$class: 'LocalBranch']],
                                           userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tidb-ansible.git']]]
                        }

                        stage("Update inventory.ini and push commit") {
                            withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                                sh """
                        git rev-parse HEAD
                        sed -i -e "s/tidb_version.*/tidb_version = ${RELEASE_TAG}/g" inventory.ini
                        git add inventory.ini 
                        git config --global user.email sre-bot@pingcap.com
                        git config --global user.name sre-bot
                        git commit -m "release ${RELEASE_TAG}"
                        git remote add sre-bot  https://sre-bot:${TOKEN}@github.com/pingcap/tidb-ansible
                        GITHUB_TOKEN=${TOKEN} hub push sre-bot ${BRANCH}
                    """
                            }
                        }
                    }
                }

                container("github") {
                    dir("docs") {
                        deleteDir()
                        git credentialsId: 'github-sre-bot-ssh', url: "https://github.com/pingcap/docs", branch: "master"
                        // checkout scm: [$class: 'GitSCM',
                        // branches: [[name: "${BRANCH}"]],
                        // extensions: [[$class: 'LocalBranch']],
                        // userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'https://github.com/pingcap/docs']]]
                        def keyword = sh(returnStdout: true, script: "echo ${RELEASE_TAG} |sed 's/v//g'").trim()

                        withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                            sh """
                    export GITHUB_TOKEN=${TOKEN}

                    pr_number=`hub  pr list|grep ${keyword}|awk '{print \$1}'|cut -c2-` 
                    echo "[debug info] Target pr https://github.com/pingcap/docs/pull/\$pr_number"
                    hub pr checkout \$pr_number

                    set +e
                    cp dev/releases/${keyword}.md /tmp/${keyword}.md   2>/dev/null
                    cp releases/${keyword}.md /tmp/${keyword}.md       2>/dev/null
                    set -e

                    # cat /tmp/${keyword}.md
                    # grep -A 100 -E "## TiDB Ansible|+ TiDB Ansible" /tmp/${keyword}.md > /tmp/release_notes.md
                    # sed -i "1cv${keyword}" /tmp/release_notes.md
                    echo ${RELEASE_TAG} >  /tmp/release_notes.md
                    # wget http://fileserver.pingcap.net/download/script/release-notes/ansible/${RELEASE_TAG}.md -O /tmp/release_notes.md
                    """
                        }
                    }
                }

                stage("Create Release and Tag") {
                    if (RELEASE_TAG<"4.0.90") {
                        container("github") {
                            dir("/home/jenkins/agent/git/tidb-ansible") {
                                withCredentials([string(credentialsId: 'sre-bot-token', variable: 'TOKEN')]) {
                                    sh """
                            GITHUB_TOKEN=${TOKEN} hub release create -t ${BRANCH}  ${RELEASE_TAG}  -F /tmp/release_notes.md
                            """
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw e;
        }
    }
}