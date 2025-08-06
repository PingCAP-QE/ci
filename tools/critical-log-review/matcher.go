package main

import (
	"fmt"
	"strings"
)

type Match struct {
	Pattern     string
	Description string
	Line        string
	File        string
}

type CriticalLogChecker struct {
	config *Config
}

func NewCriticalLogChecker(config *Config) *CriticalLogChecker {
	return &CriticalLogChecker{
		config: config,
	}
}

func (clc *CriticalLogChecker) CheckPRDiff(repoName, diff string) ([]Match, error) {
	repo := clc.config.GetRepository(repoName)
	if repo == nil {
		return nil, fmt.Errorf("repository %s not found in config", repoName)
	}

	var matches []Match
	lines := strings.Split(diff, "\n")
	currentFile := ""

	for _, line := range lines {
		// Track current file being processed
		if strings.HasPrefix(line, "+++") {
			parts := strings.Fields(line)
			if len(parts) > 1 {
				currentFile = strings.TrimPrefix(parts[1], "b/")
			}
			continue
		}

		// Only check added lines (starting with +)
		if !strings.HasPrefix(line, "+") || strings.HasPrefix(line, "+++") {
			continue
		}

		// Remove the + prefix for pattern matching
		content := strings.TrimPrefix(line, "+")

		// Check against all patterns for this repository
		for _, pattern := range repo.Patterns {
			if pattern.compiled.MatchString(content) {
				matches = append(matches, Match{
					Pattern:     pattern.Name,
					Description: pattern.Description,
					Line:        strings.TrimSpace(content),
					File:        currentFile,
				})
			}
		}
	}

	return matches, nil
}

// CheckApprovalOptimized performs approval check using ti-chi-bot notifications
func (clc *CriticalLogChecker) CheckApprovalOptimized(repoName string, ghClient *GitHubClient, prInfo *PRInfo) (bool, []string, error) {
	repo := clc.config.GetRepository(repoName)
	if repo == nil {
		return false, nil, fmt.Errorf("repository %s not found in config", repoName)
	}

	// Get approvers from ti-chi-bot comments
	botApprovers, err := ghClient.CheckTiChiBotApproval(prInfo)
	if err != nil {
		return false, nil, fmt.Errorf("failed to check ti-chi-bot approval: %w", err)
	}

	var validApprovers []string

	// Check if any bot-detected approvers are in the required approvers list
	for _, botApprover := range botApprovers {
		for _, requiredApprover := range repo.Approvers {
			if botApprover == requiredApprover {
				validApprovers = append(validApprovers, botApprover)
				break
			}
		}
	}

	hasEnoughApprovals := len(validApprovers) >= clc.config.Settings.MinApprovals
	return hasEnoughApprovals, validApprovers, nil
}

// CheckApproval is deprecated but kept for backward compatibility
// New code should use CheckApprovalOptimized instead
func (clc *CriticalLogChecker) CheckApproval(repoName string, comments []Comment) (bool, []string, error) {
	repo := clc.config.GetRepository(repoName)
	if repo == nil {
		return false, nil, fmt.Errorf("repository %s not found in config", repoName)
	}

	// Extract approvers from ti-chi-bot comments in the provided comments
	var validApprovers []string

	for _, comment := range comments {
		// Only check comments from ti-chi-bot
		if comment.User != "ti-chi-bot" {
			continue
		}

		// Look for approval notification pattern
		if strings.HasPrefix(comment.Body, "[APPROVALNOTIFIER] This PR is **APPROVED**") {
			// This is a simplified version - for full functionality use CheckApprovalOptimized
			// Extract usernames from the comment (basic implementation)
			for _, requiredApprover := range repo.Approvers {
				if strings.Contains(comment.Body, requiredApprover) {
					validApprovers = append(validApprovers, requiredApprover)
				}
			}
		}
	}

	// Remove duplicates
	seen := make(map[string]bool)
	uniqueApprovers := []string{}
	for _, approver := range validApprovers {
		if !seen[approver] {
			seen[approver] = true
			uniqueApprovers = append(uniqueApprovers, approver)
		}
	}

	hasEnoughApprovals := len(uniqueApprovers) >= clc.config.Settings.MinApprovals
	return hasEnoughApprovals, uniqueApprovers, nil
}

func (clc *CriticalLogChecker) ShouldFailCI() bool {
	return clc.config.Settings.CheckBehavior.Mode == "check_and_fail"
}

func (clc *CriticalLogChecker) GetCIMode() string {
	return clc.config.Settings.CheckBehavior.Mode
}

func (clc *CriticalLogChecker) GetRequiredApprovers(repoName string) []string {
	repo := clc.config.GetRepository(repoName)
	if repo == nil {
		return nil
	}
	return repo.Approvers
}
