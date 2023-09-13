def install_tiup = { os ->
    if(os == "linux") {
        sh """
        sudo rm -f /usr/local/bin/tiup
        """
    } else if(os == "darwin") {
        sh """
        rm -f /usr/local/bin/tiup
        """
    }

    sh """
    rm -rf ~/.tiup
    curl ${TIUP_MIRRORS}/install.sh | sh
    export PATH=${PATH}:~/.tiup/bin
    tiup --version
    """
}

def tiup_list = { comp ->
    sh """
    export PATH=${PATH}:~/.tiup/bin
    tiup list $comp
    """
}

def tiup_install = { comp, version ->
    if(version != "") {
        version = ":" + version
    }
    sh """
    export PATH=${PATH}:~/.tiup/bin
    tiup install ${comp}${version}
    """
}

def tiup_update_self = {
    sh """
    export PATH=${PATH}:~/.tiup/bin
    tiup update --self --force
    """
}

def tiup_update = { comp, version ->
    sh """
    export PATH=${PATH}:~/.tiup/bin
    tiup update --force $comp $version
    """
}

def test = { os ->
    deleteDir()
    install_tiup(os)

    // tiflash tiup problem now
    // def comps = ["tidb", "tikv", "pd", "tiflash", "cluster", "dm", "playground"]
    def comps = ["tidb", "tikv", "pd", "cluster", "dm", "playground"]

    tiup_list("")
    comps.each {
        tiup_list(it)
        tiup_install(it, "")
    }
    tiup_update_self()
    comps.each {
        tiup_update(it, "")
    }
}
try {
    stage("test on linux/amd64") {
        node("build_go1130") {
            container("golang") {
                test("linux")
            }
        }
    }

    stage("test on darwin/amd64") {
        node("mac") {
            test("darwin")
        }
    }

    currentBuild.result = "SUCCESS"
} catch (Exception e) {
    currentBuild.result = "FAILURE"
    echo "${e}"
}
