properties([
    parameters([
        string(name: 'FORK', defaultValue: 'pingcap', description: '', trim: true),
        string(name: 'BRANCH', defaultValue: 'master', description: '', trim: true),
        string(name: 'TIUP_MIRRORS', defaultValue: 'http://172.16.4.71:31888', description: '', trim: true),
        string(name: 'VERSION', defaultValue: '', description: '', trim: true),
        booleanParam(name: 'tikv', defaultValue:true, description:''),
        booleanParam(name: 'pd', defaultValue:true, description:''),
        booleanParam(name: 'tidb', defaultValue:true, description:''),
        booleanParam(name: 'cdc', defaultValue:true, description:''),
        booleanParam(name: 'binlog', defaultValue:true, description:''),
        booleanParam(name: 'tiflash', defaultValue:true, description:''),
        booleanParam(name: 'monitor', defaultValue:true, description:''),
    ])
])

catchError {
    def ok = true
    stage('Build') {
        try {
            parallel(
                'tikv': {
                    if (params.tikv) {
                    build(job: 'build-tikv', parameters: [booleanParam(name: 'TIUP_PUBLISH', value: true), string(name: 'FORK', value: params.FORK), string(name: 'BRANCH', value: params.BRANCH), string(name: 'TIUP_MIRRORS', value: params.TIUP_MIRRORS), string(name: 'VERSION', value: params.VERSION)])
                    }
                },
                'pd': {
                    if (params.pd) {
                    build(job: 'build-pd', parameters: [booleanParam(name: 'TIUP_PUBLISH', value: true), string(name: 'FORK', value: params.FORK), string(name: 'BRANCH', value: params.BRANCH), string(name: 'TIUP_MIRRORS', value: params.TIUP_MIRRORS), string(name: 'VERSION', value: params.VERSION)])
                    }
                },
                'tidb': {
                    if (params.tidb) {
                    build(job: 'build-tidb', parameters: [booleanParam(name: 'TIUP_PUBLISH', value: true), string(name: 'FORK', value: params.FORK), string(name: 'BRANCH', value: params.BRANCH), string(name: 'TIUP_MIRRORS', value: params.TIUP_MIRRORS), string(name: 'VERSION', value: params.VERSION)])
                    }
                },
                'cdc': {
                    if (params.cdc) {
                    build(job: 'build-ticdc', parameters: [booleanParam(name: 'TIUP_PUBLISH', value: true), string(name: 'FORK', value: params.FORK), string(name: 'BRANCH', value: params.BRANCH), string(name: 'TIUP_MIRRORS', value: params.TIUP_MIRRORS), string(name: 'VERSION', value: params.VERSION)])
                    }
                },
                'binlog': {
                    if (params.binlog) {
                    build(job: 'build-binlog', parameters: [booleanParam(name: 'TIUP_PUBLISH', value: true), string(name: 'FORK', value: params.FORK), string(name: 'BRANCH', value: params.BRANCH), string(name: 'TIUP_MIRRORS', value: params.TIUP_MIRRORS), string(name: 'VERSION', value: params.VERSION)])
                    }
                },
                'tiflash': {
                    if (params.tiflash) {
                    build(job: 'build-tiflash', parameters: [string(name: 'FORK', value: params.FORK), string(name: 'BRANCH', value: params.BRANCH), string(name: 'TIUP_MIRRORS', value: params.TIUP_MIRRORS), string(name: 'VERSION', value: params.VERSION)])
                    }
                },
                'monitor': {
                    if (params.monitor) {
                    build(job: 'build-monitor', parameters: [string(name: 'FORK', value: params.FORK), string(name: 'BRANCH', value: params.BRANCH), string(name: 'TIUP_MIRRORS', value: params.TIUP_MIRRORS), string(name: 'VERSION', value: params.VERSION)])
                    }
                },
            )
            } catch (e) { ok = false }
        }

    assert ok
    }
