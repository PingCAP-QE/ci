package detect

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"github.com/pingcap/ci/sync_ci/pkg/model"
	"github.com/pingcap/ci/sync_ci/pkg/parser"
	"github.com/pingcap/ci/sync_ci/pkg/util"
	"github.com/pingcap/log"
	"github.com/robfig/cron"
	"net/http"
	"sort"
	"strings"
)

const Threshold = 3

const postTemplate = `
{
	"msgtype": "markdown",
	"markdown": {
		"content": "Today's job fail reasons: 

`+"" + `
%s
`+"\n" + `"
	}
}
	`

func reportToGroupchat(wecomkey string, caseList map[string] int) error {
	url := "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=" + wecomkey
	content := ""

	reasons := []string{}
	for r := range caseList {
		reasons = append(reasons, r)
	}

	sort.Slice(reasons, func(i, j int) bool {
		return caseList[reasons[i]] >  caseList[reasons[j]]
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
	println(content)
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
		err := scheduler.AddFunc(spec, func(){
			err := ReportSigUnstableCasesBody(cfg, Threshold)
			if err != nil {
				log.S().Error("Error reporting significant issues", err)
			}
		})
		if err != nil {
			log.S().Error("Unstable report: schedule unsuccessful for cron spec ", spec)
		}else{
			
		}
	}
	scheduler.Start()
}


func ReportSigUnstableCasesBody(cfg model.Config, threshold int) error {
	cidb, err := util.SetupDB(cfg.Dsn)
	if err != nil {
		log.S().Error("GroupChat service: failed to log db")
		return err
	}

	rows, err := cidb.Raw(model.GetCICasesToday).Rows()
	if err != nil {
		log.S().Error("GroupChat service: failed to log db")
		return err
	}

	caseFrequencies := map[string] int{}
	sigUnstableCases := []string {}
	sigUnstableCases = getUnstableCasesAndEnvs(rows, caseFrequencies, threshold, sigUnstableCases)

	rows, err = cidb.Raw(model.GetRerunCases, threshold + 1).Rows()
	if err != nil {
		log.S().Error("GroupChat service: failed to log db")
		return err
	}
	// Reruns are bound to fail. Duplicated
	// sigUnstableCases = getFrequentRerunCases(rows, caseFrequencies, threshold, sigUnstableCases)
	err = reportToGroupchat(cfg.WecomKey, caseFrequencies)
	return err
}


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
