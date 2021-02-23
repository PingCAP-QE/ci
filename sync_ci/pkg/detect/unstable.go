package detect

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"time"

	"github.com/pingcap/ci/sync_ci/pkg/db"
	"github.com/pingcap/ci/sync_ci/pkg/model"
	"github.com/pingcap/ci/sync_ci/pkg/parser"
	"github.com/pingcap/log"
	"github.com/robfig/cron/v3"
)

const Threshold = 3

const postTemplate = `
{
	"msgtype": "markdown",
	"markdown": {
		"content": "Today's job fail reasons: 

%s
` + "\n" + `"
	}
}
	`

func reportToGroupchat(wecomkey string, caseList map[string]int) error {
	url := "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=" + wecomkey
	content := ""

	var reasons []string
	for r := range caseList {
		reasons = append(reasons, r)
	}

	sort.Slice(reasons, func(i, j int) bool {
		return caseList[reasons[i]] > caseList[reasons[j]]
	})

	for _, item := range reasons {
		freq := caseList[item]
		if freq < Threshold {
			break
		}
		if matched, formatted := parser.MatchAndParseSQLStmtTest(item); matched {
			item = formatted
		}

		log.S().Info("Logged item: ", item)
		content += fmt.Sprintf("> (%d times)\n> `%s`\n", freq, item)
	}
	content = fmt.Sprintf(postTemplate, content)
	data := strings.NewReader(content)
	resp, err := http.Post(url, "application/json", data)
	if err == nil && resp.StatusCode == 200 {
		log.S().Info("Report to wecom successful: \n", content)
	}
	return err
}

func ScheduleUnstableReport(cfg model.Config) {
	scheduler := cron.New()
	cronSpecs := []string{
		"0 10-22/4 * * 1-5",
	}
	for _, spec := range cronSpecs {
		_, err := scheduler.AddFunc(spec, func() {
			err := ReportSigUnstableCasesBody(cfg, Threshold)
			if err != nil {
				log.S().Error("Error reporting significant issues", err)
			}
		})
		if err != nil {
			log.S().Error("Unstable report: schedule unsuccessful for cron spec ", spec)
		}
	}
	weeklyReportSpec := "0 8 * * 6"
	genTiflashSchrodingerTestReport(cfg)
	panic("The world!")
	_, err := scheduler.AddFunc(weeklyReportSpec, func() {
		err := genTiflashSchrodingerTestReport(cfg)
		if err != nil {
			log.S().Error("Error reporting tiflash schrodinger test ", err)
		}
	})
	if err != nil {
		log.S().Error("Unstable report: schedule unsuccessful for cron spec ", weeklyReportSpec)
	}
	scheduler.Start()
}

func genTiflashSchrodingerTestReport(cfg model.Config) error {
	cidb := db.DBWarehouse[db.CIDBName]
	now := time.Now()
	from := time.Now().Add(-time.Duration(1) * time.Hour * 24 * 7)
	rows, err := cidb.Raw(model.GetSchrodingerTestsWeekly, formatT(from), formatT(now)).Rows()
	if err != nil {
		log.S().Error("GroupChat service: failed to query db")
		return err
	}
	testCases := map[string]string{}
	for rows.Next() {
		var testCase string
		var job string
		var jobid string
		err := rows.Scan(&testCase, &job, &jobid)
		if err != nil {
			log.S().Error("error getting schrodinger tests", err)
			continue
		}
		url := fmt.Sprintf(baselink, job, jobid)
		testCases[testCase] = url
	}
	err = tiflashSchrodingerTestReportToWechat(cfg.WecomKey2, testCases)
	_ = rows.Close()
	return err
}

const tiflashSchrodingerTestReportTemplate = `
{
	"msgtype": "markdown",
	"markdown": {
		"content": "Failed tiflash schrodinger test this week: 

%s
` + "\n" + `"
	}
}
	`

func tiflashSchrodingerTestReportToWechat(wecomkey string, caseList map[string]string) error {
	url := "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=" + wecomkey
	content := ""

	for testCase, url := range caseList {
		content += fmt.Sprintf("[%s](%s): %s\n", testCase, url, url)
		log.S().Info("Logged item: ", testCase, ':', url)
	}
	content = fmt.Sprintf(tiflashSchrodingerTestReportTemplate, content)
	data := strings.NewReader(content)
	resp, err := http.Post(url, "application/json", data)
	if err == nil && resp.StatusCode == 200 {
		log.S().Info("Report to wecom successful: \n", content)
	}
	return err
}

func ReportSigUnstableCasesBody(cfg model.Config, threshold int) error {
	cidb := db.DBWarehouse[db.CIDBName]
	rows, err := cidb.Raw(model.GetCICasesToday).Rows()
	if err != nil {
		log.S().Error("GroupChat service: failed to log db")
		return err
	}

	caseFrequencies := map[string]int{}
	var sigUnstableCases []string
	getUnstableCasesAndEnvs(rows, caseFrequencies, threshold, sigUnstableCases)

	rows, err = cidb.Raw(model.GetRerunCases, threshold+1).Rows()
	if err != nil {
		log.S().Error("GroupChat service: failed to log db")
		return err
	}
	// Reruns are bound to fail. Duplicated
	// sigUnstableCases = getFrequentRerunCases(rows, caseFrequencies, threshold, sigUnstableCases)
	err = reportToGroupchat(cfg.WecomKey, caseFrequencies)
	_ = rows.Close()
	return err
}

// Dead code
/*
func getFrequentRerunCases(rows *sql.Rows, caseFrequencies map[string]int, threshold int, sigUnstableCases []string) []string {
	for rows.Next() {
		var rawCase []byte
		var cases []string
		var pr string
		var repo string
		var jobid string
		var job string

		err := rows.Scan(&repo, &pr, &rawCase, &jobid, &job)
		if err != nil {
			log.S().Error("error getting history", err)
			continue
		}

		err = json.Unmarshal(rawCase, &cases)
		if err != nil {
			log.S().Error("error getting history", err)
			continue
		}

		for _, c := range cases {
			if _, ok := caseFrequencies[c]; !ok {
				caseFrequencies[c] = 1
			} else {
				caseFrequencies[c] += 1
			}

			if caseFrequencies[c] == threshold { // only log once
				sigUnstableCases = append(sigUnstableCases, c)
			}
		}
	}
	return sigUnstableCases
}
*/
func getUnstableCasesAndEnvs(rows *sql.Rows, caseFrequencies map[string]int, threshold int, sigUnstableCases []string) []string {
	for rows.Next() {
		var rawCase []byte
		var rawEnvs []byte
		var cases []string
		var envs []string
		var pr string
		var repo string
		var jobid string
		var job string

		err := rows.Scan(&repo, &pr, &rawCase, &rawEnvs, &jobid, &job)
		if err != nil {
			log.S().Error("error getting history", err)
			continue
		}

		err = json.Unmarshal(rawCase, &cases)
		if err != nil {
			log.S().Error("error getting history", err)
			continue
		}

		err = json.Unmarshal(rawEnvs, &envs)
		if err != nil {
			log.S().Error("error getting history", err)
			continue
		}

		for _, c := range cases {
			if _, ok := caseFrequencies[c]; !ok {
				caseFrequencies[c] = 1
			} else {
				caseFrequencies[c] += 1
			}

			if caseFrequencies[c] == threshold { // only log once
				sigUnstableCases = append(sigUnstableCases, c)
			}
		}

		for _, c := range envs {
			if _, ok := caseFrequencies[c]; !ok {
				caseFrequencies[c] = 1
			} else {
				caseFrequencies[c] += 1
			}

			if caseFrequencies[c] == threshold { // only log once
				sigUnstableCases = append(sigUnstableCases, c)
			}
		}
	}
	return sigUnstableCases
}
