package main

import (
	"context"
	"fmt"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/google/go-github/v53/github"
	"golang.org/x/oauth2"
)

type GitHubClient struct {
	client *github.Client
	ctx    context.Context
}

type PRInfo struct {
	Owner  string
	Repo   string
	Number int
}

type Comment struct {
	Body      string
	User      string
	CreatedAt time.Time
}

func NewGitHubClient(token string) *GitHubClient {
	ctx := context.Background()
	ts := oauth2.StaticTokenSource(
		&oauth2.Token{AccessToken: token},
	)
	tc := oauth2.NewClient(ctx, ts)
	client := github.NewClient(tc)

	return &GitHubClient{
		client: client,
		ctx:    ctx,
	}
}

func ParsePRInfo(prURL string) (*PRInfo, error) {
	// Support formats like:
	// https://github.com/owner/repo/pull/123
	// owner/repo#123
	// or environment variables

	if strings.Contains(prURL, "github.com") {
		parts := strings.Split(prURL, "/")
		if len(parts) < 7 {
			return nil, fmt.Errorf("invalid PR URL format")
		}

		owner := parts[3]
		repo := parts[4]
		numberStr := parts[6]

		number, err := strconv.Atoi(numberStr)
		if err != nil {
			return nil, fmt.Errorf("invalid PR number: %w", err)
		}

		return &PRInfo{
			Owner:  owner,
			Repo:   repo,
			Number: number,
		}, nil
	}

	// Handle owner/repo#number format
	if strings.Contains(prURL, "#") {
		parts := strings.Split(prURL, "#")
		if len(parts) != 2 {
			return nil, fmt.Errorf("invalid PR format")
		}

		repoParts := strings.Split(parts[0], "/")
		if len(repoParts) != 2 {
			return nil, fmt.Errorf("invalid repository format")
		}

		number, err := strconv.Atoi(parts[1])
		if err != nil {
			return nil, fmt.Errorf("invalid PR number: %w", err)
		}

		return &PRInfo{
			Owner:  repoParts[0],
			Repo:   repoParts[1],
			Number: number,
		}, nil
	}

	return nil, fmt.Errorf("unsupported PR format")
}

func (gc *GitHubClient) GetPRDiff(prInfo *PRInfo) (string, error) {
	opts := &github.RawOptions{Type: github.Diff}
	diff, _, err := gc.client.PullRequests.GetRaw(gc.ctx, prInfo.Owner, prInfo.Repo, prInfo.Number, *opts)
	if err != nil {
		return "", fmt.Errorf("failed to get PR diff: %w", err)
	}

	return diff, nil
}

func (gc *GitHubClient) GetPRComments(prInfo *PRInfo) ([]Comment, error) {
	return gc.GetPRCommentsWithOptions(prInfo, nil)
}

// GetPRCommentsWithOptions allows for early termination when enough approvals are found
func (gc *GitHubClient) GetPRCommentsWithOptions(prInfo *PRInfo, stopCondition func([]Comment) bool) ([]Comment, error) {
	sort := "created"
	direction := "desc"
	opts := &github.IssueListCommentsOptions{
		ListOptions: github.ListOptions{PerPage: 100},
		Sort:        &sort,
		Direction:   &direction, // Get newest comments first for faster approval detection
	}

	var allComments []Comment
	pageCount := 0
	maxPages := 50 // Limit to avoid excessive API calls for very large PRs

	for {
		pageCount++
		if pageCount > maxPages {
			// Log warning but continue with comments we have
			fmt.Printf("Warning: PR has more than %d pages of comments, some older comments may not be checked\n", maxPages)
			break
		}

		comments, resp, err := gc.client.Issues.ListComments(gc.ctx, prInfo.Owner, prInfo.Repo, prInfo.Number, opts)
		if err != nil {
			return nil, fmt.Errorf("failed to get PR comments (page %d): %w", pageCount, err)
		}

		for _, comment := range comments {
			allComments = append(allComments, Comment{
				Body:      comment.GetBody(),
				User:      comment.GetUser().GetLogin(),
				CreatedAt: comment.GetCreatedAt().Time,
			})
		}

		// Check early termination condition
		if stopCondition != nil && stopCondition(allComments) {
			fmt.Printf("Early termination: found sufficient approvals after %d pages\n", pageCount)
			break
		}

		if resp.NextPage == 0 {
			break
		}
		opts.Page = resp.NextPage
	}

	fmt.Printf("Retrieved %d comments from %d pages\n", len(allComments), pageCount)
	return allComments, nil
}

func (gc *GitHubClient) AddComment(prInfo *PRInfo, message string) error {
	comment := &github.IssueComment{
		Body: &message,
	}

	_, _, err := gc.client.Issues.CreateComment(gc.ctx, prInfo.Owner, prInfo.Repo, prInfo.Number, comment)
	if err != nil {
		return fmt.Errorf("failed to add comment: %w", err)
	}

	return nil
}

// CheckTiChiBotApproval checks for ti-chi-bot approval notifications
func (gc *GitHubClient) CheckTiChiBotApproval(prInfo *PRInfo) ([]string, error) {
	comments, err := gc.GetPRComments(prInfo)
	if err != nil {
		return nil, fmt.Errorf("failed to get PR comments: %w", err)
	}

	return gc.extractApproversFromBotComments(comments), nil
}

// extractApproversFromBotComments parses ti-chi-bot comments to extract approvers
func (gc *GitHubClient) extractApproversFromBotComments(comments []Comment) []string {
	var approvers []string

	for _, comment := range comments {
		// Only check comments from ti-chi-bot (with or without [bot] suffix)
		if comment.User != "ti-chi-bot" && comment.User != "ti-chi-bot[bot]" {
			continue
		}

		// Look for approval notification pattern
		if strings.HasPrefix(comment.Body, "[APPROVALNOTIFIER] This PR is **APPROVED**") {
			// Extract approvers from the comment body
			extractedApprovers := gc.parseApproversFromBotComment(comment.Body)
			approvers = append(approvers, extractedApprovers...)
		}
	}

	return removeDuplicates(approvers)
}

// parseApproversFromBotComment extracts approver usernames from bot comment HTML
func (gc *GitHubClient) parseApproversFromBotComment(commentBody string) []string {
	var approvers []string

	// Check if this is an approval notification comment
	if strings.Contains(commentBody, "This pull-request has been approved by:") {
		// Pattern to match all approval links: <a href="..." title="Approved">username</a>
		// This will match all approvers in the comment
		linkRe := regexp.MustCompile(`<a[^>]*title="Approved"[^>]*>([^<]+)</a>`)
		linkMatches := linkRe.FindAllStringSubmatch(commentBody, -1)

		for _, match := range linkMatches {
			if len(match) > 1 {
				username := strings.TrimSpace(match[1])
				if username != "" {
					approvers = append(approvers, username)
				}
			}
		}
	}
	
	return approvers
}

// removeDuplicates removes duplicate strings from a slice
func removeDuplicates(slice []string) []string {
	seen := make(map[string]bool)
	result := []string{}

	for _, item := range slice {
		if !seen[item] {
			seen[item] = true
			result = append(result, item)
		}
	}

	return result
}
