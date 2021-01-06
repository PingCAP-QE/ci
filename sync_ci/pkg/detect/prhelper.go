package detect

import (
	"fmt"
	"github.com/asmcos/requests"
	"github.com/pingcap/ci/sync_ci/pkg/model"
	"github.com/pingcap/log"
	"time"
)

func RemindMergePr(cfg model.Config, repo string, prId string, failedCase string, closedIssueLink string, test bool) error {
	baseComment := `The failed test case <code>%s</code> might be resolved in this issue <a href="%s">%s</a> with available PR or workarounds.`
	comment := fmt.Sprintf(baseComment, failedCase, closedIssueLink, closedIssueLink)
	var url string
	if !test {
		url = fmt.Sprintf("https://api.github.com/repos/%s/issues/%s/comments", repo, prId)
	} else {
		url = fmt.Sprintf("https://api.github.com/repos/kivenchen/klego/issues/1/comments")
	}

	err := CommentPr(cfg, url, comment)
	if err != nil {
		fmt.Errorf("failed to create PR merge reminder at %s for issue %s", url, closedIssueLink)
	}
	return err
}

func RemindUnloggedCasePr(cfg model.Config, repo string, issueId string, failedCase string, test bool) error {
	baseComment := `Please note that the test case failure <code>%s</code> has never been triggered before.`
	comment := fmt.Sprintf(baseComment, failedCase)
	var url string

	if !test {
		url = fmt.Sprintf("https://api.github.com/repos/%s/issues/%s/comments", repo, issueId)
	} else {
		url = fmt.Sprintf("https://api.github.com/repos/kivenchen/klego/issues/1/comments")
	}

	err := CommentPr(cfg, url, comment)
	if err != nil {
		fmt.Errorf("failed to create unlogged case reminder at '%s'", url)
	}
	return err
}

func CommentPr(cfg model.Config, url string, comment string) (error) {
	req := requests.Requests()
	req.SetTimeout(10 * time.Second)
	req.Header.Set("Authorization", "token "+cfg.GithubToken)
	var err error = nil
	for i := 0; i < 3; i++ {
		log.S().Info("Posting to ", url)
		resp, err := req.PostJson(url, map[string]string{
			"body": comment,
		})
		if err != nil {
			log.S().Error("Error reminding PR merge '", url, "'; Error: ", err, "; Retry")
		} else {
			if resp.R.StatusCode != 201 {
				log.S().Error("Error commenting PR ", url, ". Retry")
			} else {
				log.S().Infof("Created PR comment on '%s', body: '%s'", url, comment)
				return nil
			}
		}
	}
	return err
}
