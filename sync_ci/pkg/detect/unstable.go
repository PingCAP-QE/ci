package detect

import (
	"database/sql"
	"encoding/json"
	"github.com/pingcap/ci/sync_ci/pkg/model"
	"github.com/pingcap/log"
	"github.com/robfig/cron"
	"net/http"
	"strings"
)

const Threshold = 3

func reportToGroupchat(wecomkey string, caseList []string) error {
	url := "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=" + wecomkey
	content := "Today's unstabled cases: "
	for _, item := range caseList {
		content += item + ", "
	}
	content = `
{
	"msgtype": "text",
	"text": {
		"content": "` + content + `"
	}
}
	`
	data := strings.NewReader(content)
	_, err := http.Post(url, "application/json", data)
	if err == nil {
		log.S().Info("Report to wecom successful: \n", content)
	}
	return err
}


func ScheduleUnstableReport(cfg model.Config) {
	scheduler := cron.New()
	cronSpecs := []string{
		"0 10 * * 1-5",
		"0 14 * * 1-5",
		"0 18 * * 1-5",
		"0 22 * * 1-5",
	}
	for _, spec := range cronSpecs {
		_, err := scheduler.AddFunc(spec, func(){
			err := reportSigUnstableCasesBody(cfg, Threshold)
			if err != nil {
				log.S().Error("Error reporting significant issues", err)
			}
		})
		if err != nil {
			log.S().Error("Unstable report: schedule unsuccessful for cron spec ", spec)
		}
	}
	scheduler.Start()
}


func reportSigUnstableCasesBody(cfg model.Config, threshold int) error {
	cidb, err := SetupDB(cfg.Dsn)
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
	sigUnstableCases = getFrequentRerunCases(rows, caseFrequencies, threshold, sigUnstableCases)

	err = reportToGroupchat(cfg.WecomKey, sigUnstableCases)
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
