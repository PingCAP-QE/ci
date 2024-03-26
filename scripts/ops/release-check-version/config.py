
COMPONENT_META = {
    "pd": {
        "entrypoints": ["/pd-server"],
        "version_cmd": "-V",
        "image_name": "pd",
        "tiup_components": ["pd"],
        "version_check": {
            "version": True,
            "edition": True,
            "git_commit_hash": True
        }
    },
    "tikv": {
        "entrypoints": ["/tikv-server"],
        "version_cmd": "-V",
        "image_name": "tikv",
        "tiup_components": ["tikv"],
        "version_check": {
            "version": True,
            "edition": True,
            "git_commit_hash": True
        }
    },
    "tidb": {
        "entrypoints": ["/tidb-server"],
        "version_cmd": "-V",
        "image_name": "tidb",
        "tiup_components": ["tidb"],
        "version_check": {
            "version": True,
            "edition": True,
            "git_commit_hash": True
        }
    },
    "tiflash": {
        "entrypoints": ["/tiflash/tiflash"],
        "version_cmd": "version",
        "image_name": "tiflash",
        "tiup_components": ["tiflash"],
        "version_check": {
            "version": True,
            "edition": True,
            "git_commit_hash": True
        }
    },
    "br": {
        "entrypoints": ["/br"],
        "version_cmd": "-V",
        "image_name": "br",
        "tiup_components": ["br"],
        "version_check": {
            "version": True,
            "edition": False,
            "git_commit_hash": True
        }
    },
    "dumpling": {
        "entrypoints": ["/dumpling"],
        "version_cmd": "-V",
        "image_name": "dumpling",
        "tiup_components": ["dumpling"],
        "version_check": {
            "version": True,
            "edition": False,
            "git_commit_hash": True
        }
    },
    "binlog": {
        "entrypoints": ["/pump", "/drainer", "/binlogctl", "/reparo"],
        "version_cmd": "-V",
        "image_name": "tidb-binlog",
        "tiup_components": ["pump", "drainer"],
        "version_check": {
            "version": True,
            "edition": False,
            "git_commit_hash": True
        }
    },
    "ticdc": {
        "entrypoints": ["/cdc"],
        "version_cmd": "version",
        "image_name": "ticdc",
        "tiup_components": ["cdc"],
        "version_check": {
            "version": True,
            "edition": False,
            "git_commit_hash": True
        }
    },
    "dm": {
        "entrypoints": ["/dm-master", "/dm-worker", "/dmctl"],
        "version_cmd": "-V",
        "image_name": "dm",
        "tiup_components": ["dm-master", "dm-worker", "dmctl"],
        "version_check": {
            "version": True,
            "edition": False,
            "git_commit_hash": True
        }
    },
    "lightning": {
        "entrypoints": ["/tidb-lightning", "tidb-lightning-ctl"],
        "version_cmd": "-V",
        "image_name": "tidb-lightning",
        "tiup_components": ["tidb-lightning"],
        "version_check": {
            "version": True,
            "edition": False,
            "git_commit_hash": True
        }
    },
    "ng-monitoring": {
        "entrypoints": ["/ng-monitoring-server"],
        "version_cmd": "-V",
        "image_name": "ng-monitoring",
        "tiup_components": [],
        "version_check": {
            "version": False,
            "edition": False,
            "git_commit_hash": True
        }
    },
    "tidb-dashboard": {
        "entrypoints": ["/tidb-dashboard"],
        "version_cmd": "-V",
        "image_name": "tidb-dashboard",
        "tiup_components": ["tidb-dashboard"],
        "version_check": {
            "version": False,
            "edition": False,
            "git_commit_hash": True
        }
    }
    # TODO: how to check the image tidb-monitor-initializer?
}


TIUP_PACKAGE_DEFAULT_EDITION = "Community"
TIUP_MIRRORS = {
    "prod-pingcap": "https://tiup-mirrors.pingcap.com",
    "staging-internal": "http://tiup.pingcap.net:8988"
}
