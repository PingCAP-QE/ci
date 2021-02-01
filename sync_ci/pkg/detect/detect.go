package detect

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"reflect"
	"regexp"
	"strings"
	"time"

	"github.com/asmcos/requests"
	"github.com/google/go-github/github"
	"github.com/pingcap/ci/sync_ci/pkg/db"
	"github.com/pingcap/ci/sync_ci/pkg/model"
	"github.com/pingcap/ci/sync_ci/pkg/parser"
	"github.com/pingcap/log"
	"gorm.io/gorm"
)

const searchIssueIntervalStr = "178h"
const PrInspectLimit = time.Hour * 24 * 7
const baselink = "https://internal.pingcap.net/idc-jenkins/job/%s/%s/display/redirect" // job_name, job_id
const FirstCaseOnly = true

func GetCasesFromPR(cfg model.Config, startTime time.Time, inspectStartTime time.Time, test bool) ([]*model.CaseIssue, error) {
	cidb := db.DBWarehouse[db.CIDBName]

	// Get failed cases from CI data
	now := time.Now()

	rows, err := cidb.Raw(model.GetCICaseSql, formatT(inspectStartTime), formatT(startTime)).Rows()
	if err != nil {
		return nil, err
	}
	caseSet := map[string]map[string][]string{}
	getHistoryCases(rows, caseSet, baselink)

	recentRows, err := cidb.Raw(model.GetCICaseSql, formatT(startTime), formatT(now)).Rows()
	if err != nil {
		return nil, err
	}
	DupRecentCaseSet := map[string]map[string][]string{}
	allRecentCases := getDuplicatesFromHistory(recentRows, caseSet, DupRecentCaseSet)

	// Validate repo cases
	dbIssueCase := db.DBWarehouse[db.CIDBName]
	dbGithub := db.DBWarehouse[db.GithubDBName]
	// assumed `cases` param has no reps
	_, err = handleCasesIfIssueExists(cfg, allRecentCases, dbIssueCase, dbGithub, true, test)
	issuesToCreate, err := handleCasesIfIssueExists(cfg, DupRecentCaseSet, dbIssueCase, dbGithub, false, test)
	if err != nil {
		return nil, err
	}

	_ = rows.Close()
	_ = recentRows.Close()
	return issuesToCreate, nil
}

func handleCasesIfIssueExists(cfg model.Config, recentCaseSet map[string]map[string][]string, dbIssueCase *gorm.DB, dbGithub *gorm.DB, mentionExisted, test bool) ([]*model.CaseIssue, error) {
	issueCases := []*model.CaseIssue{}
	for repo, repoCases := range recentCaseSet {
		for c, v := range repoCases {
			existedCases, err := dbIssueCase.Raw(model.IfValidIssuesExistSql, c, repo).Rows()
			if err != nil {
				log.S().Error("failed to check existing [case, repo]: ", c, repo)
				continue
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
					continue
				}

				issueCases, err = handleCaseIfHistoryExists(cfg, dbGithub, issueNumStr, repo, c, v, issueCases, mentionExisted, test)
				if err != nil {
					log.S().Error("failed to respond to issueCases", err)
					continue
				}
			}
		}
	}
	return issueCases, nil
}

func handleCaseIfHistoryExists(cfg model.Config, dbGithub *gorm.DB, issueNumStr string, repo string, caseName string, joblinks []string, issueCases []*model.CaseIssue, mentionExisted, test bool) ([]*model.CaseIssue, error) {
	issueNumberLike := "%/" + issueNumStr
	repoLike := "%/" + repo + "/%"
	stillValidIssues, err := dbGithub.Raw(model.CheckClosedTimeSql, issueNumberLike, repoLike, searchIssueIntervalStr).Rows()
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
	} else if mentionExisted { // mention existing issue
		var url string
		err = stillValidIssues.Scan(&url)
		if err != nil {
			log.S().Error("failed to extract existing issue url", err)
			return nil, err
		}
		log.S().Info("Mentioning issue located at ", url)
		issueId := strings.Split(url, "/issues/")[1]
		err = MentionIssue(cfg, repo, issueId, joblinks[0], test)
		if err != nil {
			log.S().Error(err)
			return nil, err
		}
	}
	return issueCases, nil
}

func GetNightlyCases(cfg model.Config, filterStartTime, now time.Time, test bool) ([]*model.CaseIssue, error) {
	cidb := db.DBWarehouse[db.CIDBName]
	ghdb := db.DBWarehouse[db.GithubDBName]
	csdb := db.DBWarehouse[db.CIDBName]
	rows, err := cidb.Raw(model.GetCINightlyCase, formatT(filterStartTime), formatT(now)).Rows()
	RepoNightlyCase := map[string]map[string][]string{}
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
				RepoNightlyCase[repo] = map[string][]string{}
			}
			if _, ok := RepoNightlyCase[repo][c]; !ok {
				link := fmt.Sprintf(baselink, job, jobid)
				RepoNightlyCase[repo][c] = []string{link}

				issueCase := model.CaseIssue{
					IssueNo:   0,
					Repo:      repo,
					IssueLink: sql.NullString{},
					Case:      sql.NullString{c, true},
					JobLink:   sql.NullString{link, true},
				}
				issueCases = append(issueCases, &issueCase)
			}
			if FirstCaseOnly {
				break
			}
		}
	}
	issueCases, err = handleCasesIfIssueExists(cfg, RepoNightlyCase, csdb, ghdb, true, test)
	if err != nil {
		log.S().Error("Failed to handle existing issue case", err)
	}

	return issueCases, nil
}

func extractRepoFromJobName(job string) string {
	tidb_regex := regexp.MustCompile("^tidb_ghpr")
	tikv_regex := regexp.MustCompile("^tikv_ghpr")
	pd_regex := regexp.MustCompile("^pd_ghpr")
	if tidb_regex.MatchString(job) {
		return "pingcap/tidb"
	}
	if tikv_regex.MatchString(job) {
		return "tikv/tikv"
	}
	if pd_regex.MatchString(job) {
		return "tikv/pd"
	}
	return "others"
}

func getDuplicatesFromHistory(recentRows *sql.Rows, caseSet map[string]map[string][]string, recentCaseSet map[string]map[string][]string) map[string]map[string][]string {
	allRecentCases := map[string]map[string][]string{}
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
			if _, ok := allRecentCases[repo]; !ok {
				allRecentCases[repo] = map[string][]string{}
			}
			if _, ok := allRecentCases[repo][c]; !ok {
				allRecentCases[repo][c] = []string{}
			}
			jobLink := fmt.Sprintf(baselink, job, jobid)
			allRecentCases[repo][c] = append(allRecentCases[repo][c], jobLink)
			if _, ok := caseSet[repo]; !ok {
				caseSet[repo] = map[string][]string{}
			}
			if _, ok := caseSet[repo][c]; ok {
				if _, ok := recentCaseSet[repo]; !ok {
					recentCaseSet[repo] = map[string][]string{}
				}
				if matched, name := parser.MatchAndParseSQLStmtTest(c); matched {
					recentCaseSet[repo][name] = append([]string{jobLink}, caseSet[repo][c]...)
				} else {
					recentCaseSet[repo][c] = append([]string{jobLink}, caseSet[repo][c]...)
				}
			}
			if FirstCaseOnly {
				break
			}
		}
	}
	return allRecentCases
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
			if FirstCaseOnly {
				break
			}
		}
	}
}

func MentionIssue(cfg model.Config, repo string, issueId string, joblink string, test bool) error {
	req := requests.Requests()
	req.SetTimeout(10 * time.Second)
	req.Header.Set("Authorization", "token "+cfg.GithubToken)
	baseComment := `Yet another case failure: <a href="%s">%s</a>`
	var url string
	if !test {
		url = fmt.Sprintf("https://api.github.com/repos/%s/issues/%s/comments", repo, issueId)
	} else {
		url = fmt.Sprintf("https://api.github.com/repos/%s/issues/%s/comments", repo, issueId)
	}

	for i := 0; i < 3; i++ {
		log.S().Info("Posting to ", url)
		resp, err := req.PostJson(url, map[string]string{
			"body": fmt.Sprintf(baseComment, joblink, joblink),
		})
		if err != nil {
			log.S().Error("Error commenting issue '", url, "'; Error: ", err, "; Retry")
		} else {
			if resp.R.StatusCode != 201 {
				log.S().Error("Error commenting issue ", url, ". Retry")
			} else {
				log.S().Infof("Created comment %s/#%s mentioning %s", repo, issueId, joblink)
				return nil
			}
		}
	}
	return fmt.Errorf("failed to mention existing issue at %s for job at %s", url, joblink)
}

func CreateIssueForCases(cfg model.Config, issues []*model.CaseIssue, test bool) error {
	req := requests.Requests()
	req.SetTimeout(10 * time.Second)
	req.Header.Set("Authorization", "token "+cfg.GithubToken)
	dbIssueCase := db.DBWarehouse[db.CIDBName]
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
			log.S().Info("Posting to ", url)
			resp, err := req.PostJson(url, map[string]interface{}{
				"title":  issue.Case.String + " failed",
				"body":   "Latest build: <a href=\"" + issue.JobLink.String + "\">" + issue.JobLink.String + "</a>", // todo: fill content templates
				"labels": []string{"component/test"},
			})
			if err != nil {
				log.S().Error("Error creating issue '", url, "'; Error: ", err, "; Retry")
			} else {
				if resp.R.StatusCode != 201 {
					log.S().Error("Error creating issue ", url, ". Retry")
					log.S().Error("Create issue failed: ", string(resp.Content()))
				} else {
					log.S().Info("create issue success for job", issue.JobLink.String)
					break
				}
			}
		}

		if resp == nil || resp.R.StatusCode != 201 {
			log.S().Error("Error commenting issue ", url, ". Skipped")
			log.S().Error("Create issue failed: ", resp.R.StatusCode, string(resp.Content()))
			continue
		}

		responseDict := github.Issue{}
		err := resp.Json(&responseDict)
		if err != nil {
			log.S().Error("parse response failed", err)
			continue
		}

		num := responseDict.Number
		link := reflect.ValueOf(responseDict.URL).Elem().String()
		issue.IssueNo = reflect.ValueOf(num).Elem().Int()
		issue.IssueLink = sql.NullString{
			String: link,
			Valid:  true,
		}
		//log db
		dbIssueCase.Create(issue)
		if dbIssueCase.Error != nil {
			log.S().Error("Log issue_case db failed", dbIssueCase.Error)
		}
		dbIssueCase.Commit()
		if dbIssueCase.Error != nil {
			log.S().Error("Log issue_case db commit failed", dbIssueCase.Error)
		}
	}
	return nil
}

func formatT(t time.Time) string {
	return t.Format("2006-01-02 15:04:05")
}

func GetNewCasesFromPR(cfg model.Config, startTime time.Time, inspectStartTime time.Time, test bool) ([]*model.NewCase, error) {
	if test { //TODO delete test
		newCases := []*model.NewCase{}
		newCase := model.NewCase{
			Repo:     "",
			PR:       "",
			CaseInfo: "test test",
			JobLink:  "www.github.com",
		}
		newCases = append(newCases, &newCase)
		return newCases, nil
	}
	cidb := db.DBWarehouse[db.CIDBName]

	// Get failed cases from CI data
	now := time.Now()

	//rows, err := cidb.Raw(model.GetCICaseSql, formatT(inspectStartTime), formatT(startTime)).Rows()
	rows, err := cidb.Raw(model.GetCICaseSql, time.Now().Add(-time.Duration(cfg.UpdateInterval)*time.Second*24*36500), formatT(now)).Rows()
	if err != nil {
		return nil, err
	}
	// Repo -> case -> available
	caseSet := map[string]map[string]bool{}
	getEncounteredCases(rows, caseSet, baselink)

	//recentRows, err := cidb.Raw(model.GetCICaseSql, formatT(startTime), formatT(now)).Rows()
	recentRows, err := cidb.Raw(model.GetCICaseSql, time.Now().Add(-time.Duration(cfg.UpdateInterval)*time.Second*24*36500), formatT(now)).Rows()
	if err != nil {
		return nil, err
	}
	// Repo -> pr -> firstcase
	// test
	caseSet = map[string]map[string]bool{}
	unknownCases := removeDuplicatesCasesReferingHistory(recentRows, caseSet)
	newCases := []*model.NewCase{}
	for repo, repoCases := range unknownCases {
		for pr, info := range repoCases {
			newCase := model.NewCase{
				Repo:     repo,
				PR:       pr,
				CaseInfo: info.Case.String,
				JobLink:  info.JobLink.String,
			}
			newCases = append(newCases, &newCase)
			if len(newCases) > 5 {
				break
			}
		}
		if len(newCases) > 5 {
			break
		}
	}
	_ = rows.Close()
	_ = recentRows.Close()
	return newCases, nil
}

func getEncounteredCases(rows *sql.Rows, caseSet map[string]map[string]bool, baselink string) {
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
			if _, ok := caseSet[repo]; !ok {
				caseSet[repo] = map[string]bool{}
			}
			if _, ok := caseSet[repo][c]; !ok {
				caseSet[repo][c] = true
			}
		}
	}
}

func removeDuplicatesCasesReferingHistory(recentRows *sql.Rows, caseSet map[string]map[string]bool) map[string]map[string]model.CaseIssue {
	newCases := map[string]map[string]model.CaseIssue{}
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
				caseSet[repo] = map[string]bool{}
			}
			if _, ok := caseSet[repo][c]; !ok {
				if _, ok := newCases[repo]; !ok {
					newCases[repo] = map[string]model.CaseIssue{}
				}
				if _, ok := newCases[repo][pr]; !ok {
					link := fmt.Sprintf(baselink, job, jobid)
					newCases[repo][pr] = model.CaseIssue{
						Case:    sql.NullString{c, true},
						JobLink: sql.NullString{link, true},
					}
				}
			}
		}
	}
	return newCases
}

func CreateCommentForNewCases(cfg model.Config, newCases []*model.NewCase, test bool) error {
	req := requests.Requests()
	req.SetTimeout(10 * time.Second)
	req.Header.Set("Authorization", "token "+cfg.GithubToken)
	// todo: set request header
	for _, newCase := range newCases {
		var url string
		if !test {
			url = fmt.Sprintf("https://api.github.com/repos/%s/issues/%s/comments", newCase.Repo, newCase.PR)
		} else {
			url = "https://api.github.com/repos/AsterNighT/ci/issues/1/comments"
		}
		var resp *requests.Response
		for i := 0; i < 3; i++ {
			log.S().Info("Posting to ", url)
			resp, err := req.PostJson(url, map[string]interface{}{
				"body": newCase.CaseInfo + " failed.\n This is a new failure in our record.\nLatest build: <a href=\"" + newCase.JobLink + "\">" + newCase.JobLink + "</a>", // todo: fill content templates
			})
			if err != nil {
				log.S().Error("Error creating comment '", url, "'; Error: ", err, "; Retry")
			} else {
				if resp.R.StatusCode != 201 {
					log.S().Error("Error creating comment ", url, ". Retry")
					log.S().Error("Create comment failed: ", string(resp.Content()))
				} else {
					log.S().Info("create comment success for job", newCase.JobLink)
					break
				}
			}
		}

		if resp == nil || resp.R.StatusCode != 201 {
			log.S().Error("Error commenting issue ", url, ". Skipped")
			log.S().Error("Create comment failed: ", resp.R.StatusCode, string(resp.Content()))
			continue
		}

		responseDict := github.Issue{}
		err := resp.Json(&responseDict)
		if err != nil {
			log.S().Error("parse response failed", err)
			continue
		}
	}
	return nil
}
