package detect

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"github.com/asmcos/requests"
	"github.com/google/go-github/github"
	"github.com/pingcap/ci/sync_ci/pkg/model"
	"github.com/pingcap/ci/sync_ci/pkg/parser"
	"github.com/pingcap/log"
	"gorm.io/gorm"
	"reflect"
	"strings"
	"time"
)

const searchIssueIntervalStr = "178h"
const PrInspectLimit = time.Hour * 24 * 7
const baselink = "https://internal.pingcap.net/idc-jenkins/job/%s/%s/display/redirect" // job_name, job_id

func GetCasesFromPR(cfg model.Config, startTime time.Time, inspectStartTime time.Time, test bool) ([]*model.CaseIssue, error) {
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
	caseSet := map[string]map[string][]string{}
	getHistoryCases(rows, caseSet, baselink)

	recentRows, err := cidb.Raw(model.GetCICaseSql, formatT(startTime), formatT(now)).Rows()
	if err != nil {
		return nil, err
	}
	recentCaseSet := map[string]map[string][]string{}
	getDuplicatesFromHistory(recentRows, caseSet, recentCaseSet)

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
	issueCases, err := handleCasesIfIssueExists(cfg, recentCaseSet, dbIssueCase, dbGithub, test)
	if err != nil {
		return nil, err
	}
	return issueCases, nil
}

func handleCasesIfIssueExists(cfg model.Config, recentCaseSet map[string]map[string][]string, dbIssueCase *gorm.DB, dbGithub *gorm.DB, test bool) ([]*model.CaseIssue, error) {
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
				err = existedCases.Scan(&issueNumStr)
				if err != nil {
					log.S().Error("failed to obtain issue num", err)
				}
				issueCases, err = handleCaseIfHistoryExists(cfg, dbGithub, issueNumStr, repo, c, v, issueCases, test)
			}

		}
	}
	return issueCases, nil
}

func handleCaseIfHistoryExists(cfg model.Config, dbGithub *gorm.DB, issueNumStr string, repo string, caseName string, joblinks []string, issueCases []*model.CaseIssue, test bool) ([]*model.CaseIssue, error) {
	stillValidIssues, err := dbGithub.Raw(model.CheckClosedTimeSql, issueNumStr, repo, searchIssueIntervalStr).Rows()
	if err != nil {
		return nil, err
	}
	if !stillValidIssues.Next() {
		issueCase := model.CaseIssue{
			IssueNo:   0,
			Repo:      repo,
			IssueLink: sql.NullString{},
			Case:      sql.NullString{caseName, true},
			JobLink:   sql.NullString{joblinks[0], true},
		}
		issueCases = append(issueCases, &issueCase)
	} else { // mention existing issue
		var url string
		err = stillValidIssues.Scan(&url)
		if err != nil {
			log.S().Error("failed to extract existing issue url", err)
		}

		issueId := strings.Split(url, "/issues/")[1]
		err = MentionIssue(cfg, repo, issueId, joblinks[0], test)
		if err != nil {
			log.S().Error(err)
		}
	}
	return issueCases, nil
}

func GetNightlyCases(cfg model.Config, filterStartTime, now time.Time) ([]*model.CaseIssue, error) {
	cidb, err := SetupCIDB(cfg)
	if err != nil {
		return nil, err
	}

	rows, err := cidb.Raw(model.GetCINightlyCase, formatT(filterStartTime), formatT(now)).Rows()
	RepoNightlyCase := map[string]map[string]bool{}
	issueCases := []*model.CaseIssue{}

	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var rawCase []byte
		var cases []string
		var jobid string
		var job string
		err = rows.Scan(&rawCase, &jobid, &job)
		if err != nil {
			log.S().Error(err)
			continue
		}
		err = json.Unmarshal(rawCase, &cases)
		if err != nil {
			log.S().Error(err)
			continue
		}
		repo := extractRepoFromJobName(job)
		if repo == "others" { // mixed unknown repos
			continue
		}

		for _, c := range cases {
			if _, ok := RepoNightlyCase[repo]; !ok {
				RepoNightlyCase[repo] = map[string]bool{}
			}
			if _, ok := RepoNightlyCase[repo][c]; !ok {
				RepoNightlyCase[repo][c] = true
				issueCase := model.CaseIssue{
					IssueNo:   0,
					Repo:      repo,
					IssueLink: sql.NullString{},
					Case:      sql.NullString{c, true},
					JobLink:   sql.NullString{fmt.Sprintf(baselink, job, jobid), true},
				}
				issueCases = append(issueCases, &issueCase)
			}
		}
	}

	return issueCases, nil
}

func extractRepoFromJobName(job string) string {
	if strings.Contains(job, "tidb") {
		return "pingcap/tidb"
	}
	if strings.Contains(job, "tikv") {
		return "pingcap/tidb"
	}
	if strings.Contains(job, "pd") {
		return "tikv/pd"
	}
	return "others"
}

func getDuplicatesFromHistory(recentRows *sql.Rows, caseSet map[string]map[string][]string, recentCaseSet map[string]map[string][]string) {
	for recentRows.Next() {
		var rawCase []byte
		var cases []string
		var pr string
		var repo string
		var jobid string
		var job string
		err := recentRows.Scan(&repo, &pr, &rawCase, &jobid, &job)
		if err != nil {
			log.S().Error(err)
			continue
		}
		err = json.Unmarshal(rawCase, &cases)
		if err != nil {
			log.S().Error(err)
			continue
		}

		for _, c := range cases {
			if _, ok := caseSet[repo]; !ok {
				caseSet[repo] = map[string][]string{}
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
}

func getHistoryCases(rows *sql.Rows, caseSet map[string]map[string][]string, baselink string) {
	repoPrCases := map[string]map[string]string{} // repo -> pr -> case
	for rows.Next() {
		var rawCase []byte
		var cases []string
		var pr string
		var repo string
		var jobid string
		var job string
		_ = rows.Scan(&repo, &pr, &rawCase, &jobid, &job)
		if pr == "0" {

		}
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
}

func MentionIssue(cfg model.Config, repo string, issueId string, joblink string, test bool) error {
	req := requests.Requests()
	req.SetTimeout(10 * time.Second)
	req.Header.Set("Authorization", "token "+cfg.GithubToken)
	var url string
	if !test {
		url = fmt.Sprintf("https://api.github.com/repos/%s/issues/%s/comments", repo, issueId)
	} else {
		url = "https://api.github.com/repos/kivenchen/klego/issues/1/comments"
	}

	for i := 0; i < 3; i++ {
		println("Posting to ", url)
		resp, err := req.PostJson(url, map[string]string{
			"body": "#" + issueId + ": new failure !(jenkins link)[" + joblink + "]", // todo: fill content templates
		})
		if err != nil {
			return err
		} else {
			if resp.R.StatusCode != 201 {
				return fmt.Errorf("create comment failed")
			} else {
				log.S().Info("Created comment %s/#%s mentioning %s", repo, issueId, joblink)
				break
			}
		}
	}

	return nil
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
				"labels": "component/test",
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
