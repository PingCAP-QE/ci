/*
* @GIT_REPO_SSH_URL
* @BRANCH
* @VERSION
*/

// https://cd.pingcap.net/job/release_tikv-java-client/

pipeline {
    agent any

    // 依赖工具
    tools {
        maven "Maven"
    }

    // 环境变量
    environment {
        // Nexus配置
        NEXUS_VERSION = "nexus2"
        NEXUS_PROTOCOL = "https"
        NEXUS_URL = "oss.sonatype.org"
        NEXUS_CREDENTIAL_ID = "ossrh"

        // Git配置
        GIT_CREDENTIAL_ID = "github-sre-bot-ssh"
    }

    // CD Pipeline
    stages {
        stage("Clone Code") {
            steps {
                script {
                    // Clone and Checkout Branch
                    git credentialsId: GIT_CREDENTIAL_ID, url: GIT_REPO_SSH_URL
                    sh "git branch -a" // List all branches.
                    sh "git checkout ${BRANCH}" // Checkout to a specific branch in your repo.
                    sh "ls -lart ./*"  // Just to view all the files if needed
                }
            }
        }
        stage("Maven Build") {
            steps {
                script {
                    if (VERSION != null && !VERSION.isEmpty()) {
                        sh "mvn versions:set -DnewVersion=${VERSION}"
                    }
                    sh "mvn clean package -DskipTests=true"
                }
            }
        }
        stage("Publish to Nexus Repository Manager") {
            steps {
                script {
                    // 获取jar包产物: target/*.jar
                    pom = readMavenPom file: "pom.xml";
                    filesByGlob = findFiles(glob: "target/*.${pom.packaging}");
                    echo "${filesByGlob[0].name} ${filesByGlob[0].path} ${filesByGlob[0].directory} ${filesByGlob[0].length} ${filesByGlob[0].lastModified}"

                    // 获取产物仓库
                    NEXUS_REPOSITORY = "snapshots";
                    if (!pom.version.contains("-SNAPSHOT")) {
                        NEXUS_REPOSITORY = "releases";
                    }

                    // 获取产物信息: 文件位置等
                    artifactPath = filesByGlob[0].path;
                    artifactExists = fileExists artifactPath;
                    version = pom.version;
                    if (VERSION != null && !VERSION.isEmpty()) {
                        version = VERSION;
                    }
                    echo "KeyLog: File: ${artifactPath}, group: ${pom.groupId}, packaging: ${pom.packaging}, version ${version}, nexus repo ${NEXUS_REPOSITORY}";

                    // 上传到中央Nexus仓库
                    if(artifactExists) {
                        nexusArtifactUploader(
                            nexusVersion: NEXUS_VERSION,
                            protocol: NEXUS_PROTOCOL,
                            nexusUrl: NEXUS_URL,
                            groupId: pom.groupId,
                            version: version,
                            repository: NEXUS_REPOSITORY,
                            credentialsId: NEXUS_CREDENTIAL_ID,
                            artifacts: [
                                [artifactId: pom.artifactId,
                                classifier: '',
                                file: artifactPath,
                                type: pom.packaging],
                                [artifactId: pom.artifactId,
                                classifier: '',
                                file: "pom.xml",
                                type: "pom"]
                            ]
                        );
                    } else {
                        error "*** File: ${artifactPath}, could not be found";
                    }
                }
            }
        }
    }
}
