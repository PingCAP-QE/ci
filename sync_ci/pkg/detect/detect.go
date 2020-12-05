package detect

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"github.com/asmcos/requests"
	"github.com/pingcap/ci/sync_ci/pkg/model"
	"strconv"
	"time"
)

const searchIssueIntervalStr = "168h"
const PrInspectLimit = time.Hour * 24 * 7

func GetCasesFromPR(cfg model.Config, startTime time.Time, inspectStartTime time.Time) ([]*model.CaseIssue, error) {
	cidb, err := SetupCIDB(cfg)
	if err != nil {
		return nil, err
	}

	// Get failed cases from CI data
	now := time.Now()
	rows, err := cidb.Raw(model.GetCICaseSql, formatT(inspectStartTime), formatT(now)).Rows()
	if err != nil {
		return nil, err
	}
	recentRows, err := cidb.Raw(model.GetCICaseSql, formatT(startTime), formatT(now)).Rows()
	if err != nil {
		return nil, err
	}

	caseSet := map[string]map[string]bool{}
	repoPrCases := map[string]map[string]string{} // repo -> pr -> case
	for rows.Next() {
		var rawCase []byte
		var cases []string
		var pr string
		var repo string
		_ = rows.Scan(&repo, &pr, &rawCase)
		_ = json.Unmarshal(rawCase, &cases)
		for _, c := range cases {
			if _, ok := repoPrCases[repo]; !ok {
				repoPrCases[repo] = map[string]string{}
			}
			if _, ok := repoPrCases[repo][c]; !ok {
				repoPrCases[repo][c] = pr
			} else {
				if _, ok = caseSet[repo]; !ok {
					caseSet[repo] = map[string]bool{}
				}
				caseSet[repo][c] = true
			}
		}
	}

	recentCaseSet := map[string]map[string]bool{}
	for recentRows.Next() {
		var rawCase []byte
		var cases []string
		var pr string
		var repo string
		_ = recentRows.Scan(&repo, &pr, &rawCase)
		_ = json.Unmarshal(rawCase, &cases)

		for _, c := range cases {
			if _, ok := caseSet[repo]; !ok {
				continue
			}
			if _, ok := caseSet[repo][c]; !ok {
				continue
			}
			if _, ok := recentCaseSet[repo]; !ok {
				recentCaseSet[repo] = map[string]bool{}
			}
			recentCaseSet[repo][c] = true
		}
	}

	// Validate repo cases
	dbIssueCase, err := SetupCaseIssueDB(cfg)
	if err != nil {
		return nil, err
	}
	dbGithub, err := SetupGHDB(cfg)
	if err != nil {
		return nil, err
	}
	// assumed `cases` param has no reps
	issueCases := []*model.CaseIssue{}
	for repo, repoCases := range recentCaseSet {
		for c, _ := range repoCases {
			existedCases, err := dbIssueCase.Raw(model.IfValidIssuesExistSql, c, repo).Rows()
			if err != nil {
				return nil, err
			}
			if !existedCases.Next() {
				issueCase := model.CaseIssue{
					IssueNo:   0,
					Repo:      repo,
					IssueLink: sql.NullString{},
					Case:      sql.NullString{c, true},
				}
				issueCases = append(issueCases, &issueCase)
			} else { // already nexted
				var issueNumStr string
				_ = existedCases.Scan(&issueNumStr)
				stillValidIssues, err := dbGithub.Raw(model.CheckClosedTimeSql, issueNumStr, repo, searchIssueIntervalStr).Rows()
				if err != nil {
					return nil, err
				}
				if !stillValidIssues.Next() {
					issueCase := model.CaseIssue{
						IssueNo:   0,
						Repo:      repo,
						IssueLink: sql.NullString{},
						Case:      sql.NullString{c, true},
					}
					issueCases = append(issueCases, &issueCase)
				}
			}

		}
	}
	return issueCases, nil
}

func CreateIssueForCases(cfg model.Config, issues []*model.CaseIssue) error {
	req := requests.Requests()
	req.SetTimeout(10 * time.Second)
	req.Header.Set("Authorization", "token "+cfg.GithubToken)
	dbIssueCase, err := SetupCaseIssueDB(cfg)
	if err != nil {
		return err
	}
	// todo: set request header
	for _, issue := range issues {
		var url = fmt.Sprint("https://api.github.com/repos/%s/issues", issue.Repo)
		var resp requests.Response
		for i := 0; i < 3; i++ {
			resp, err := req.PostJson(url, map[string]string{
				"title":  issue.Case.String + " failed",
				"body":   "", // todo: fill content templates
				"labels": "component/test",
			})
			if err != nil {
				return err
			} else {
				if resp.R.StatusCode != 201 {
					return fmt.Errorf("Create issue failed with %d", resp.R.StatusCode)
				} else {
					break
				}
			}
		}
		responseDict := map[string]string{}
		_ = resp.Json(&responseDict)
		if num, ok1 := responseDict["number"]; ok1 {
			if link, ok2 := responseDict["link"]; ok2 {
				if _, ok3 := responseDict["created_at"]; ok3 {
					issue.IssueNo, err = strconv.ParseInt(num, 10, 32)
					if err != nil {
						return err
					}
					issue.IssueLink = sql.NullString{
						String: link,
						Valid:  true,
					}

					//log db
					dbIssueCase.Create(issue)
				}
			}
		}
	}
	return fmt.Errorf("Unable to log db with issue creation response")
}

func formatT(t time.Time) string {
	return t.Format("%Y-%m-%d %H:%M:%S")
}
