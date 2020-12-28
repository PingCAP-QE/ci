package detect

import (
	"fmt"
	"github.com/asmcos/requests"
	"github.com/pingcap/ci/sync_ci/pkg/model"
	"github.com/pingcap/log"
	"time"
)

func RemindMergePr(cfg model.Config, repo string, issueId string, failedCase string, closedIssueLink string, test bool) error {
	req := requests.Requests()
	req.SetTimeout(10 * time.Second)
	req.Header.Set("Authorization", "token "+cfg.GithubToken)
	baseComment := `The failed test case <code>%s</code> might be resolved in this issue <a href="%s">%s</a> with available PR or workarounds.`
	var url string
	if !test {
		url = fmt.Sprintf("https://api.github.com/repos/%s/issues/%s/comments", repo, issueId)
	} else {
		url = fmt.Sprintf("https://api.github.com/repos/kivenchen/klego/issues/1/comments")
	}

	for i := 0; i < 3; i++ {
		log.S().Info("Posting to ", url)
		resp, err := req.PostJson(url, map[string]string{
			"body": fmt.Sprintf(baseComment, failedCase, closedIssueLink, closedIssueLink),
		})
		if err != nil {
			log.S().Error("Error reminding PR merge '", url, "'; Error: ", err, "; Retry")
		} else {
			if resp.R.StatusCode != 201 {
				log.S().Error("Error reminding PR merge ", url, ". Retry")
			} else {
				log.S().Infof("Created PR merge reminder %s/#%s mentioning %s", repo, issueId, closedIssueLink)
				return nil
			}
		}
	}
	return fmt.Errorf("failed to create PR merge reminder at %s for issue %s", url, closedIssueLink)
}
