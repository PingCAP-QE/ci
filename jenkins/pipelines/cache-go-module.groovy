node("ci-admin-utils") {

    def repoPathMap = [:]

    stage('Collect Info') {
        container("golang"){
            sh "ls -ld /nfs/cache/git"
            dir("/nfs/cache/git"){
                def files = findFiles()
                files.each{ f ->
                    if(f.directory && fileExists("${f.name}/.git/config") ) {
                        echo "This is dirctory: ${f.name}"
                        dir(f.name){
                            if(fileExists("./go.mod")){
                                echo "This is a valid go mod repo"
                                def workspace = pwd()
                                repoPathMap[f.name] = workspace
                            } else {
                                echo "This is invalid go repo. Skip..."
                            }
                        }
                        dir("${f.name}@tmp"){
                            deleteDir()
                        }
                    }
                }
            }
        }
    }

    stage('Cache') {
        container('golang'){
            dir("/nfs/cache/git"){
                repoPathMap.each{entry ->
                    dir(entry.key){
                        sh " GOPATH=/nfs/cache/gopath  go mod download "
                    }
                    dir("${entry.key}@tmp"){
                        deleteDir()
                    }
                }
            }
        }
    }

}
