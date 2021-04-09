package detect

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"sort"
	"strings"

	"github.com/pingcap/ci/sync_ci/pkg/db"
	"github.com/pingcap/ci/sync_ci/pkg/model"
	"github.com/pingcap/ci/sync_ci/pkg/parser"
	"github.com/pingcap/log"
	"github.com/robfig/cron/v3"
)

const Threshold = 3

type FeiShuMsgContent struct {
	Tag  string `json:"tag"`
	Text string `json:"text"`
}
type FeiShuMsgZhCn struct {
	Title   string               `json:"title"`
	Content [][]FeiShuMsgContent `json:"content"`
}
type FeiShuMsg struct {
	MsgType string `json:"msg_type"`
	Content struct {
		Post struct {
			ZhCn FeiShuMsgZhCn `json:"zh_cn"`
		} `json:"post"`
	} `json:"content"`
}

func reportToGroupchat(wecomkey string, caseList map[string]int) error {
	url := "https://open.feishu.cn/open-apis/bot/v2/hook/" + wecomkey
	feishuMsg := FeiShuMsg{
		MsgType: "post",
		Content: struct {
			Post struct {
				ZhCn FeiShuMsgZhCn `json:"zh_cn"`
			} `json:"post"`
		}{Post: struct {
			ZhCn FeiShuMsgZhCn `json:"zh_cn"`
		}{ZhCn: FeiShuMsgZhCn{
			Title:   "Today's job fail reasons",
			Content: [][]FeiShuMsgContent{},
		}}},
	}
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
		content := fmt.Sprintf("(%d times): %s", freq, item)
		feishuMsg.Content.Post.ZhCn.Content = append(feishuMsg.Content.Post.ZhCn.Content, []FeiShuMsgContent{{
			Tag:  "text",
			Text: content,
		}})
	}
	feishuMsgBt, err := json.Marshal(feishuMsg)
	if err != nil {
		log.S().Fatal("json marshal error: ", err)
	}
	data := strings.NewReader(string(feishuMsgBt))
	resp, err := http.Post(url, "application/json", data)
	if err == nil && resp.StatusCode == 200 {
		log.S().Info("Report to feishu successful: \n", string(feishuMsgBt))
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
	scheduler.Start()
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
