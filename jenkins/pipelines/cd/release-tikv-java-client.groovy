/*
* @VERSION
*/

pipeline {
    agent any

    // 依赖工具
    tools {
        maven "Maven"
    }

    // 变量
    environment {
        NEXUS_VERSION = "nexus2"
        NEXUS_PROTOCOL = "https"
        NEXUS_URL = "oss.sonatype.org"
        NEXUS_REPOSITORY = "Releases"
        NEXUS_CREDENTIAL_ID = "ossrh"
    }
    
    // CD Pipeline
    stages {
        stage("Clone Code") {
            steps {
                script {
                    git 'https://github.com/tikv/client-java.git';
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
                    
                    // 获取产物
                    artifactPath = filesByGlob[0].path;
                    artifactExists = fileExists artifactPath;
                    if(artifactExists) {
                        echo "*** File: ${artifactPath}, group: ${pom.groupId}, packaging: ${pom.packaging}, version ${pom.version}";
                        // nexusArtifactUploader(
                        //     nexusVersion: NEXUS_VERSION,
                        //     protocol: NEXUS_PROTOCOL,
                        //     nexusUrl: NEXUS_URL,
                        //     groupId: pom.groupId,
                        //     version: pom.version,
                        //     repository: NEXUS_REPOSITORY,
                        //     credentialsId: NEXUS_CREDENTIAL_ID,
                        //     artifacts: [
                        //         [artifactId: pom.artifactId,
                        //         classifier: '',
                        //         file: artifactPath,
                        //         type: pom.packaging],
                        //         [artifactId: pom.artifactId,
                        //         classifier: '',
                        //         file: "pom.xml",
                        //         type: "pom"]
                        //     ]
                        // );
                    } else {
                        error "*** File: ${artifactPath}, could not be found";
                    }
                }
            }
        }
    }
}