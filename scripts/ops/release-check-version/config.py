COMPONENT_META = {
    "pd": {
        "entrypoints": ["/pd-server"],
        "version_cmd": "-V",
        "image_name": "pd",
        "tiup_components": ["pd"],
        "image_edition": {
            "enterprise": True,
            "community": True
        },
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
        "image_edition": {
            "enterprise": True,
            "community": True
        },
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
        "image_edition": {
            "enterprise": True,
            "community": True
        },
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
        "image_edition": {
            "enterprise": True,
            "community": True
        },
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
        "image_edition": {
            "enterprise": True,
            "community": True
        },
        "tiup_components": ["br"],
        "version_check": {
            "version": True,
            "edition": False,
            "git_commit_hash": True
        }
    },
    # TODO: tiup dumpling:v7.5.1 -V, all output default to stderr, need to fix
    "dumpling": {
        "entrypoints": ["/dumpling"],
        "version_cmd": "-V",
        "image_name": "dumpling",
        "image_edition": {
            "enterprise": True,
            "community": True
        },
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
        "image_edition": {
            "enterprise": True,
            "community": True
        },
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
        "image_edition": {
            "enterprise": True,
            "community": True
        },
        "tiup_components": ["cdc"],
        "version_check": {
            "version": True,
            "edition": False,
            "git_commit_hash": True
        }
    },
    # TODO: tiup dmctl:v7.5.1 -V, all output default to stderr, need to fix
    "dm": {
        "entrypoints": ["/dm-master", "/dm-worker", "/dmctl"],
        "version_cmd": "-V",
        "image_name": "dm",
        "image_edition": {
            "enterprise": True,
            "community": True
        },
        "tiup_components": ["dm-master", "dm-worker", "dmctl"],
        "version_check": {
            "version": True,
            "edition": False,
            "git_commit_hash": True
        }
    },
    "lightning": {
        "entrypoints": ["/tidb-lightning", "/tidb-lightning-ctl"],
        "version_cmd": "-V",
        "image_name": "tidb-lightning",
        "image_edition": {
            "enterprise": True,
            "community": True
        },
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
        "image_edition": {
            "enterprise": True,
            "community": True
        },
        "tiup_components": [],
        "version_check": {
            "version": False,
            "edition": False,
            "git_commit_hash": True
        }
    },
    "tidb-dashboard": {
        "entrypoints": ["/tidb-dashboard"],
        "version_cmd": "--version",
        "image_name": "tidb-dashboard",
        "image_edition": {
            "enterprise": False,
            "community": True
        },
        "tiup_components": ["tidb-dashboard"],
        "version_check": {
            "version": False,
            "edition": False,
            "git_commit_hash": True
        }
    },
    "grafana": {
        "entrypoints": [],
        "version_cmd": "",
        "image_name": "",
        "tiup_components": ["grafana"],
        "version_check": {
            "version": False,
            "edition": False,
            "git_commit_hash": False
        }
    },
    "prometheus": {
        "entrypoints": [],
        "version_cmd": "",
        "image_name": "",
        "tiup_components": ["prometheus"],
        "version_check": {
            "version": False,
            "edition": False,
            "git_commit_hash": False
        }
    },
    "ctl": {
        "entrypoints": [],
        "version_cmd": "-V",
        "image_name": "",
        "tiup_components": ["ctl"],
        "version_check": {
            "version": True,
            "edition": False,
            "git_commit_hash": True
        }
    },
    "enterprise-plugin": {
        "entrypoints": [],
        "version_cmd": "",
        "image_name": "",
        "tiup_components": [],
        "version_check": {
            "version": False,
            "edition": False,
            "git_commit_hash": False
        }
    }
    # TODO: how to check the image tidb-monitor-initializer?
}

TIUP_PACKAGE_DEFAULT_EDITION = "Community"
TIUP_MIRRORS = {
    "prod-pingcap": "https://tiup-mirrors.pingcap.com",
    "staging-internal": "http://tiup.pingcap.net:8988"
}
