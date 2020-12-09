package command

import (
	"github.com/pingcap/ci/sync_ci/pkg/model"
)

func RunCustomizedCaseIssue() {
	cfg := model.Config{
		Port:           "",
		Dsn:            "root:123456@tcp(172.16.5.219:3306)/sync_ci_data",
		GithubDsn:      "root:123456@tcp(172.16.5.219:3307)/github_info",
		CaseDsn:        "root:123456@tcp(172.16.5.219:3306)/sync_ci_data",
		LogPath:        "",
		GithubToken:    "",
		UpdateInterval: 3600 * 24 * 5,
	}
	RunCaseIssueRoutine(cfg, true)
}
