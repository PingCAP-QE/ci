package command

import (
	"context"
	"encoding/json"
	"flag"
	_ "github.com/go-sql-driver/mysql"
	"github.com/google/subcommands"
	"github.com/pingcap/ci/sync_ci/pkg/detect"
	"github.com/pingcap/ci/sync_ci/pkg/model"
	"github.com/pingcap/ci/sync_ci/pkg/server"
	"github.com/pingcap/log"
	"time"
)

type SyncCICommand struct {
	model.Config
}

func (*SyncCICommand) Name() string { return "sync-ci" }

func (*SyncCICommand) Synopsis() string { return "sync CI data to mysql" }

func (*SyncCICommand) Usage() string {
	return `sync-ci`
}

func (s *SyncCICommand) SetFlags(f *flag.FlagSet) {
	f.StringVar(&s.Dsn, "dsn", "root:@tcp(127.0.0.1:3306)/sync_ci_data", "CI Database dsn")
	f.StringVar(&s.CaseDsn, "cs", "root:@tcp(127.0.0.1:3306)/issue_case", "Case-issues Database dsn")
	f.StringVar(&s.GithubDsn, "gh", "root:@tcp(127.0.0.1:3306)/issues", "Github Issues Database dsn")
	f.StringVar(&s.GithubToken, "tk", "", "Github token to automatically create issues")
	f.StringVar(&s.WecomKey, "wc", "", "WeCom key to send unstable case report")
	f.Int64Var(&s.UpdateInterval, "ui", 3600, "The interval (secs) to update")
	f.StringVar(&s.Port, "port", "36000", "http service port")
	f.StringVar(&s.LogPath, "lp", "log", "log path")
	f.StringVar(&s.RulePath, "rp", "pkg/parser/envrules.json", "env rule file path")
}

func (s *SyncCICommand) Execute(ctx context.Context, f *flag.FlagSet, _ ...interface{}) subcommands.ExitStatus {
	go RunCaseIssueRoutine(s.Config, false)
	detect.ScheduleUnstableReport(s.Config, 3)
	server.NewServer(&s.Config).Run()
	return subcommands.ExitSuccess
}

func RunCaseIssueRoutine(cfg model.Config, test bool) {
	if err := model.InitLog(cfg.LogPath); err != nil {
		log.S().Fatalf("init log error , [error]", err)
	}

	defer func() {
		if r := recover(); r != nil {
			log.S().Error("recovering from panic: ", r)
		}
	}()
	log.S().Info("RunCaseIssueRoutine initiated")
	for {
		inspectStart := time.Now().Add(-detect.PrInspectLimit).Add(detect.TimeDiffFix)
		recentStart := time.Now().Add(-time.Duration(cfg.UpdateInterval) * time.Second).Add(detect.TimeDiffFix)
		cases, err := detect.GetCasesFromPR(cfg, recentStart, inspectStart, false)
		if err != nil {
			log.S().Error("get cases failed", err)
		}

		nightlyCaseIssues, err := detect.GetNightlyCases(cfg, recentStart, time.Now().Add(detect.TimeDiffFix), test)
		if err != nil {
			log.S().Error("get nightly cases failed", err)
		}

		if cases != nil {
			if len(cases) == 0 {
				log.S().Info("No selected cases")
			}

			for _, c := range cases {
				var bts []byte
				bts, err = json.Marshal(c)
				if err != nil {
					log.S().Error(err)
				}
				log.S().Info("acquired new cases: ", string(bts))
			}

			err = detect.CreateIssueForCases(cfg, cases, test)
			if err != nil {
				log.S().Error(err)
			}
		}

		if nightlyCaseIssues != nil {
			err = detect.CreateIssueForCases(cfg, nightlyCaseIssues, test)
			if err != nil {
				log.S().Error(err)
			}
		}

		if test {
			break
		}
		time.Sleep(time.Duration(cfg.UpdateInterval) * time.Second)
	}
}
