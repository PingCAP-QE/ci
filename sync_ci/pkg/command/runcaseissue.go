package command

import (
	"github.com/pingcap/ci/sync_ci/pkg/model"
)

func RunCustomizedCaseIssue() {
	cfg := model.Config{
		Port:           "",
		Dsn:            "",
		GithubDsn:      "",
		CaseDsn:        "",
		LogPath:        "",
		GithubToken:    "",
		UpdateInterval: 3600 * 24 * 5,
	}
	RunCaseIssueRoutine(cfg, true)
}
