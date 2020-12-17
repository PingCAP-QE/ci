package detect

import (
	"encoding/json"
	"github.com/pingcap/ci/sync_ci/pkg/model"
    "github.com/robfig/cron"
    "github.com/pingcap/log"
	"net/http"
	"strings"
)

func reportToGroupchat(wecomkey string, caseList []string) error {
	url := "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=" + wecomkey
	content := "Today's unstabled cases: "
	for _, item := range caseList {
		content += item + ", "
	}
	data := strings.NewReader(`
{
	"msgtype": "text",
	"text": {
		"content": "`+ content +`"
	}
}
	`)
	_, err := http.Post(url, "application/json", data)
	return err
}

func ScheduleUnstableReport(cfg model.Config, threshold int) {
	scheduler := cron.New()
	cronSpecs := []string{
		"0 10 * * 1-5",
		"0 14 * * 1-5",
		"0 18 * * 1-5",
		"0 22 * * 1-5",
	}
	for _, spec := range cronSpecs {
		_, err := scheduler.AddFunc(spec, func(){
			err := reportSigUnstableCasesBody(cfg, threshold)
			if err != nil {
				log.S().Error("Error reporting significant issues", err)
			}
		})
		if err != nil {
			log.S().Error("Unstable report: schedule unsuccessful for cron spec ", spec)
		}
	}
}


func reportSigUnstableCasesBody(cfg model.Config, threshold int) error {
	cidb, err := SetupDB(cfg.Dsn)
	if err != nil {
		log.S().Error("GroupChat service: failed to log db")
		return err
	}

	rows, err := cidb.Raw(model.GetCICaseSql).Rows()
	if err != nil {
		log.S().Error("GroupChat service: failed to log db")
		return err
	}

	caseFrequencies := map[string] int{}
	sigUnstableCases := []string {}
	for rows.Next(){
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

			if caseFrequencies[c] == threshold {  // only log once
				sigUnstableCases = append(sigUnstableCases, c)
			}
		}
	}

	rows, err = cidb.Raw(model.GetRerunCases).Rows()
	if err != nil {
		log.S().Error("GroupChat service: failed to log db")
		return err
	}
	for rows.Next(){
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

			if caseFrequencies[c] == threshold {  // only log once
				sigUnstableCases = append(sigUnstableCases, c)
			}
		}
	}

	err = reportToGroupchat(cfg.WecomKey, sigUnstableCases)
	return err
}
