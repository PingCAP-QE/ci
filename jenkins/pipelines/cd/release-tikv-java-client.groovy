/*
* @BRANCH
*/

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
        // NEXUS_REPOSITORY = "Releases"
        NEXUS_REPOSITORY = "snapshots"
        NEXUS_CREDENTIAL_ID = "ossrh"

        // Git配置
        GIT_CREDENTIAL_ID = "github-sre-bot-ssh"
        GIT_REPO_SSH_URL = "git@github.com:tikv/client-java.git"
    }
    
    // CD Pipeline
    stages {
        stage("Clone Code") {
            steps {
                script {
                    git(
                        branch: BRANCH,
                        credentialsId: GIT_CREDENTIAL_ID,
                        url: GIT_REPO_SSH_URL
                    );
                }
            }
        }
        stage("Maven Build") {
            steps {
                script {
                    sh "mvn package -DskipTests=true"
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
                    
                    // 获取产物信息: 文件位置等
                    artifactPath = filesByGlob[0].path;
                    artifactExists = fileExists artifactPath;
                    if(artifactExists) {
                        echo "*** File: ${artifactPath}, group: ${pom.groupId}, packaging: ${pom.packaging}, version ${pom.version}";

                        // 上传到中央Nexus仓库
                        nexusArtifactUploader(
                            nexusVersion: NEXUS_VERSION,
                            protocol: NEXUS_PROTOCOL,
                            nexusUrl: NEXUS_URL,
                            groupId: pom.groupId,
                            version: pom.version,
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