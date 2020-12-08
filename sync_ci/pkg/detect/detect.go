package detect

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"github.com/asmcos/requests"
	"github.com/google/go-github/github"
	"github.com/pingcap/ci/sync_ci/pkg/model"
	"github.com/pingcap/ci/sync_ci/pkg/parser"
	"reflect"
	"time"
)

const searchIssueIntervalStr = "178h"
const PrInspectLimit = time.Hour * 24 * 7

func GetCasesFromPR(cfg model.Config, startTime time.Time, inspectStartTime time.Time) ([]*model.CaseIssue, error) {
	cidb, err := SetupCIDB(cfg)
	baselink := "https://internal.pingcap.net/idc-jenkins/job/%s/%s/display/redirect" // job name / job id
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

	caseSet := map[string]map[string][]string{}
	repoPrCases := map[string]map[string]string{} // repo -> pr -> case
	//caseBuildLinks := map[string] []string {}
	for rows.Next() {
		var rawCase []byte
		var cases []string
		var pr string
		var repo string
		var jobid string
		var job string
		_ = rows.Scan(&repo, &pr, &rawCase, &jobid, &job)
		_ = json.Unmarshal(rawCase, &cases)
		for _, c := range cases {
			if _, ok := repoPrCases[repo]; !ok {
				repoPrCases[repo] = map[string]string{}
			}
			if _, ok := repoPrCases[repo][c]; !ok {
				repoPrCases[repo][c] = pr
			} else {
				if _, ok = caseSet[repo]; !ok {
					caseSet[repo] = map[string][]string{}
				}
				caseSet[repo][c] = append(caseSet[repo][c], fmt.Sprintf(baselink, job, jobid))
			}
		}
	}

	recentCaseSet := map[string]map[string][]string{}
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
				recentCaseSet[repo] = map[string][]string{}
			}
			if matched, name := parser.MatchAndParseSQLStmtTest(c); matched {
				recentCaseSet[repo][name] = caseSet[repo][c]
			} else {
				recentCaseSet[repo][c] = caseSet[repo][c]
			}
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
		for c, v := range repoCases {
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
					JobLink:   sql.NullString{v[0], true},
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
						JobLink:   sql.NullString{v[0], true},
					}
					issueCases = append(issueCases, &issueCase)
				}
			}

		}
	}
	return issueCases, nil
}

func CreateIssueForCases(cfg model.Config, issues []*model.CaseIssue, test bool) error {
	req := requests.Requests()
	req.SetTimeout(10 * time.Second)
	req.Header.Set("Authorization", "token "+cfg.GithubToken)
	dbIssueCase, err := SetupCaseIssueDB(cfg)
	if err != nil {
		return err
	}
	// todo: set request header
	for _, issue := range issues {
		var url string
		if !test {
			url = fmt.Sprintf("https://api.github.com/repos/%s/issues", issue.Repo)
		} else {
			url = "https://api.github.com/repos/kivenchen/klego/issues"
		}
		var resp *requests.Response
		for i := 0; i < 3; i++ {
			println("Posting to ", url)
			resp, err = req.PostJson(url, map[string]string{
				"title": issue.Case.String + " failed",
				"body":  "Latest build: !(Jenkins)[" + issue.JobLink.String + "]", // todo: fill content templates
				//"labels": "component/test",
			})
			if err != nil {
				return err
			} else {
				if resp.R.StatusCode != 201 {
					return fmt.Errorf("Create issue failed")
				} else {
					println("Creation success")
					break
				}
			}
		}
		responseDict := github.Issue{}
		err = resp.Json(&responseDict)
		if err != nil {
			println("parse response failed", err)
		}

		num := responseDict.Number
		link := reflect.ValueOf(responseDict.URL).Elem().String()
		_ = responseDict.CreatedAt
		issue.IssueNo = reflect.ValueOf(num).Elem().Int()
		if err != nil {
			return err
		}
		issue.IssueLink = sql.NullString{
			String: link,
			Valid:  true,
		}
		//log db
		dbIssueCase.Create(issue)
		dbIssueCase.Commit()
	}
	return nil
}

func formatT(t time.Time) string {
	return t.Format("2006-01-02 15:04:05")
}
