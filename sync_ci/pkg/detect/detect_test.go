package detect

import (
	"encoding/json"
	"github.com/pingcap/ci/sync_ci/pkg/db"
	"github.com/pingcap/ci/sync_ci/pkg/model"
	"github.com/pingcap/ci/sync_ci/pkg/util"
	"github.com/pingcap/log"
	"github.com/robfig/cron/v3"
	"github.com/stretchr/testify/assert"
	"testing"
	"time"
)

func TestGetCasesFromPR(t *testing.T) {
	cfg := model.Config{
		Port:           "",
		Dsn:            "root:123456@tcp(172.16.5.219:3306)/sync_ci_data",
		GithubDsn:      "root:123456@tcp(172.16.5.219:3307)/github_info",
		LogPath:        "log.txt",
		GithubToken:    "",
		UpdateInterval: 3600*9,
	}

	cases, err := GetCasesFromPR(cfg, time.Now().Add(- time.Duration(cfg.UpdateInterval) * time.Second),
		time.Now().AddDate(0, 0, -7), true)

	if err != nil {
		log.S().Error("get nightly cases failed", err)
	}

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
}


func TestGetNightlyCases(t *testing.T) {
	cfg := model.Config{
		Port:           "",
		Dsn:            "root:123456@tcp(172.16.5.219:3306)/sync_ci_data",
		GithubDsn:      "root:123456@tcp(172.16.5.219:3307)/github_info",
		LogPath:        "log.txt",
		GithubToken:    "",
		UpdateInterval: 3600*9,
	}

	cases, err := GetNightlyCases(cfg, time.Now().Add(- time.Duration(cfg.UpdateInterval) * time.Second),
		time.Now().AddDate(0, 0, -7), true)

	if err != nil {
		t.Error("get nightly cases failed", err)
	}

	if len(cases) == 0 {
		t.Log("No selected cases")
	}

	for _, c := range cases {
		var bts []byte
		bts, err = json.Marshal(c)
		if err != nil {
			log.S().Error(err)
		}
		log.S().Info("acquired new cases: ", string(bts))
	}
}

func TestMentionIssue(t *testing.T) {
	cfg := model.Config{
		Port:           "",
		Dsn:            "root:123456@tcp(172.16.5.219:3306)/sync_ci_data",
		GithubDsn:      "root:123456@tcp(172.16.5.219:3307)/github_info",
		LogPath:        "log.txt",
		GithubToken:    "d92be7fe85c09da5f8685f7f24327cbc3fe01850",
		UpdateInterval: 3600*9,
	}
	err := MentionIssue(cfg, "nothing/special", "12", "google.com", true)
	if err != nil {
		t.Error(err)
		assert.Fail(t, "Cannot create issue")
	}
}

func TestCreateIssueForCases(t *testing.T) {
	cfg := model.Config{
		Port:           "",
		Dsn:            "root:123456@tcp(172.16.5.219:3306)/sync_ci_data",
		GithubDsn:      "root:123456@tcp(172.16.5.219:3307)/github_info",
		LogPath:        "log.txt",
		GithubToken:    "d92be7fe85c09da5f8685f7f24327cbc3fe01850",
		UpdateInterval: 3600*9,
	}

	cases, err := GetCasesFromPR(cfg, time.Now().Add(- time.Duration(cfg.UpdateInterval) * time.Second),
		time.Now().AddDate(0, 0, -7), true)

	if err != nil {
		log.S().Error("get nightly cases failed", err)
	}

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
	err = CreateIssueForCases(cfg, cases, true)
	if err != nil{
		t.Error(err)
		t.Fail()
	}
}


func TestReportSigUnstableCasesBody(t *testing.T) {
	_ = util.InitLog("log.txt")
	cfg := model.Config {
		Port:           "",
		Dsn:            "root:123456@tcp(172.16.5.219:3306)/sync_ci_data",
		GithubDsn:      "root:123456@tcp(172.16.5.219:3307)/github_info",
		LogPath:        "log.txt",
		GithubToken:    "",
		WecomKey: "9f9548b2-54b9-404b-a4bb-57ed0b53ba2fabc",
		UpdateInterval: 3600*9,
	}
	db.InitDB(cfg)
	err := ReportSigUnstableCasesBody(cfg, 3)
	if err != nil{
		log.S().Info("")
	}
}


func TestSetupDB(t *testing.T) {
	s := cron.New()
	println("initiated")
	_, e := s.AddFunc("0 * * * *", func(){println("Yes")})
	if e != nil {
		println(e)
	}
	println(s.Location().String())
	s.Start()
	select {}
}
