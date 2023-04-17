// Set SSH authentication for git clone or other operations.
def setSshKey(String credentialsId, String gitHost = 'github.com') {
    withCredentials([sshUserPrivateKey(credentialsId: credentialsId, keyFileVariable: 'SSH_KEY')]) {
        sh label: 'Set git ssh key' script: """
            [ -d ~/.ssh ] || mkdir ~/.ssh && chmod 0700 ~/.ssh
            echo "\$SSH_KEY" > ~/.ssh/id_rsa
            chmod 400 ~/.ssh/id_rsa
            ssh-keyscan ${gitHost} >> ~/.ssh/known_hosts            
        """
    }
}
