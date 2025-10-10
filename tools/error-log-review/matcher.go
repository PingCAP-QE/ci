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

type ErrorLogChecker struct {
	config *Config
}

func NewErrorLogChecker(config *Config) *ErrorLogChecker {
	return &ErrorLogChecker{
		config: config,
	}
}

func (clc *ErrorLogChecker) CheckPRDiff(repoName, diff string) ([]Match, error) {
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

		// Check if current file is excluded at repository level
		if matchesAnyPattern(currentFile, repo.Excludes) {
			continue
		}

		// Remove the + prefix for pattern matching
		content := strings.TrimPrefix(line, "+")

		// Check against all patterns for this repository
		for _, pattern := range repo.Patterns {
			// Check if current file is excluded for this specific pattern
			if matchesAnyPattern(currentFile, pattern.Excludes) {
				continue
			}

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
func (clc *ErrorLogChecker) CheckApprovalOptimized(repoName string, ghClient *GitHubClient, prInfo *PRInfo) (bool, []string, error) {
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

func (clc *ErrorLogChecker) ShouldFailCI() bool {
	return clc.config.Settings.CheckBehavior.Mode == "check_and_fail"
}

func (clc *ErrorLogChecker) GetCIMode() string {
	return clc.config.Settings.CheckBehavior.Mode
}

func (clc *ErrorLogChecker) GetRequiredApprovers(repoName string) []string {
	repo := clc.config.GetRepository(repoName)
	if repo == nil {
		return nil
	}
	return repo.Approvers
}
