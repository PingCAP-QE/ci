package command

import (
	"github.com/pingcap/ci/sync_ci/pkg/model"
	"testing"
)

func TestRunCaseIssueRoutine(t *testing.T) {
	cfg := model.Config{
		Port:           "",
		Dsn:            "",
		GithubDsn:      "",
		CaseDsn:        "",
		LogPath:        "",
		GithubToken:    "",
		UpdateInterval: 3600*78,
	}
	RunCaseIssueRoutine(cfg, true)
}