/*
* @TIDB_TAG
* @TIKV_TAG
* @PD_TAG
* @BINLOG_TAG
* @TIFLASH_TAG
* @LIGHTNING_TAG
* @TOOLS_TAG
* @BR_TAG
* @CDC_TAG
* @RELEASE_TAG
*/
properties([
        parameters([
                string(
                        defaultValue: '',
                        name: 'RELEASE_TAG',
                        description: '', 
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'PRE_RELEASE',
                        description: '', 
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIDB_HASH',
                        description: '', 
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIKV_HASH',
                        description: '', 
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'PD_HASH',
                        description: '', 
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIFLASH_HASH',
                        description: '', 
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'PLUGIN_HASH',
                        description: '', 
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIDB_TAG',
                        description: '如果选择了 PRE_RELEASE，需要输入 tag 之前的封板 githash，下同。\n
                            如果没有选择 PRE_RELEASE，则认为是正式发版，需要输入 repo 上打好的对应 tag 名称', 
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIKV_TAG',
                        description: '', 
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'PD_TAG',
                        description: '', 
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'BINLOG_TAG',
                        description: '', 
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TIFLASH_TAG',
                        description: '', 
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'LIGHTNING_TAG',
                        description: '', 
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'IMPORTER_TAG',
                        description: '访问 https://api.github.com/repos/tikv/importer/git/refs/tags/{TIKV_IMPORTER_VERSION}  确认版本信息正确', 
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'TOOLS_TAG',
                        description: '', 
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'CDC_TAG',
                        description: '', 
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'DUMPLING_TAG',
                        description: '', 
                        trim: true
                ),
                string(
                        defaultValue: '',
                        name: 'CDC_TAG',
                        description: '', 
                        trim: true
                ),
                booleanParam(
                        defaultValue: true,
                        name: 'FORCE_REBUILD',
                        description: ''
                ),
        ])
])



def libs

def os = "linux"
def arch = "amd64"
def platform = "centos7"

catchError {
    stage('Prepare') {
        node('delivery') {
            container('delivery') {
                dir ('centos7') {
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"               
                    checkout scm
                    libs = load "jenkins/pipelines/cd/optimization-libs.groovy"
                }
            }
        }
    }

    node('delivery') {
        container("delivery") {

            def builds = [:]

            builds["Push tidb Docker"] = {
                libs.release_tidb_online_image("tidb", TIDB_HASH, PLUGIN_HASH, arch,  os , platform,RELEASE_TAG, true, PRE_RELEASE)
            }

            builds["Push tikv Docker"] = {
                libs.release_online_image("tikv", TIKV_HASH, arch,  os , platform,RELEASE_TAG, true, PRE_RELEASE)
            }

            builds["Push pd Docker"] = {
                libs.release_online_image("pd", PD_HASH, arch,  os , platform,RELEASE_TAG, true, PRE_RELEASE)
            }

            builds["Push tiflash Docker"] = {
                libs.release_online_image("tiflash", TIFLASH_HASH, arch,  os , platform,RELEASE_TAG, true, PRE_RELEASE)
            }

            builds["Push lightning Docker"] = {
                libs.retag_enterprise_docker("tidb-lightning", RELEASE_TAG, PRE_RELEASE)
            }

            builds["Push tidb-binlog Docker"] = {
                libs.retag_enterprise_docker("tidb-binlog", RELEASE_TAG, PRE_RELEASE)
            }

            builds["Push cdc Docker"] = {
                libs.retag_enterprise_docker("ticdc", RELEASE_TAG, PRE_RELEASE)
            }

            builds["Push br Docker"] = {
                libs.retag_enterprise_docker("br", RELEASE_TAG, PRE_RELEASE)
            }

            builds["Push dumpling Docker"] = {
                libs.retag_enterprise_docker("dumpling", RELEASE_TAG, PRE_RELEASE)
            }

            builds["Push NG monitoring Docker"] = {
                libs.retag_enterprise_docker("ng-monitoring", RELEASE_TAG, PRE_RELEASE)
            }

            stage("Push tarbll/image") {
                parallel builds
            }

        }
    }

    currentBuild.result = "SUCCESS"
}
