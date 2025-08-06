package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"strings"
)

func main() {
	var (
		configPath = flag.String("config", "config.yaml", "Path to configuration file")
		prURL      = flag.String("pr", "", "PR URL or owner/repo#number format")
		token      = flag.String("token", "", "GitHub token (or set GITHUB_API_TOKEN env var)")
		dryRun     = flag.Bool("dry-run", false, "Don't actually post comments or fail, just print results")
	)
	flag.Parse()

	// Get GitHub token from environment if not provided
	if *token == "" {
		*token = os.Getenv("GITHUB_API_TOKEN")
		if *token == "" {
			log.Fatal("GitHub token must be provided via -token flag or GITHUB_API_TOKEN environment variable")
		}
	}

	// Get PR info from environment variables if not provided
	if *prURL == "" {
		owner := os.Getenv("GITHUB_REPOSITORY_OWNER")
		repo := os.Getenv("GITHUB_REPOSITORY")
		prNumber := os.Getenv("GITHUB_PR_NUMBER")

		if owner != "" && repo != "" && prNumber != "" {
			// Handle case where repo might include owner prefix
			repoName := strings.TrimPrefix(repo, owner+"/")
			*prURL = fmt.Sprintf("%s/%s#%s", owner, repoName, prNumber)
		}
	}

	if *prURL == "" {
		log.Fatal("PR URL must be provided via -pr flag or environment variables")
	}

	// Load configuration
	config, err := LoadConfig(*configPath)
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}

	// Parse PR information
	prInfo, err := ParsePRInfo(*prURL)
	if err != nil {
		log.Fatalf("Failed to parse PR info: %v", err)
	}

	repoName := fmt.Sprintf("%s/%s", prInfo.Owner, prInfo.Repo)

	// Check if this repository is configured
	repo := config.GetRepository(repoName)
	if repo == nil {
		fmt.Printf("Repository %s is not configured for critical log review - skipping\n", repoName)
		os.Exit(0)
	}

	// Initialize GitHub client
	ghClient := NewGitHubClient(*token)

	// Get PR diff
	diff, err := ghClient.GetPRDiff(prInfo)
	if err != nil {
		log.Fatalf("Failed to get PR diff: %v", err)
	}

	// Initialize checker
	checker := NewCriticalLogChecker(config)

	// Check for critical log patterns
	matches, err := checker.CheckPRDiff(repoName, diff)
	if err != nil {
		log.Fatalf("Failed to check PR diff: %v", err)
	}

	// If no critical log matches found, exit successfully
	if len(matches) == 0 {
		fmt.Println("No critical log changes detected - check passed")
		os.Exit(0)
	}

	fmt.Printf("Found %d critical log matches\n", len(matches))
	for _, match := range matches {
		fmt.Printf("  %s [%s]: %s\n", match.File, match.Pattern, match.Line)
	}

	// Check for existing approval using optimized method (early termination for large PRs)
	hasApproval, approvers, err := checker.CheckApprovalOptimized(repoName, ghClient, prInfo)
	if err != nil {
		log.Fatalf("Failed to check approval: %v", err)
	}

	if hasApproval {
		fmt.Printf("Critical log changes approved by: %s\n", strings.Join(approvers, ", "))

		// Show additional repository information
		requiredApprovers := checker.GetRequiredApprovers(repoName)
		minApprovals := config.Settings.MinApprovals

		fmt.Printf("Repository %s critical log approvers: %s\n", repoName, strings.Join(requiredApprovers, ", "))
		fmt.Printf("Approval status: %d/%d required approvals received\n", len(approvers), minApprovals)

		os.Exit(0)
	}

	// No approval found - handle based on configuration
	fmt.Println("Critical log changes found but not approved")

	// Always show required approvers, regardless of comment settings
	requiredApprovers := checker.GetRequiredApprovers(repoName)
	if len(requiredApprovers) > 0 {
		fmt.Printf("Required approvers for %s: %s\n", repoName, strings.Join(requiredApprovers, ", "))
	}

	ciMode := checker.GetCIMode()

	if *dryRun {
		if ciMode == "check_and_fail" {
			fmt.Println("DRY RUN - Would fail CI")
		} else {
			fmt.Println("DRY RUN - Would pass CI with warning")
		}
		os.Exit(0)
	}

	// Decide CI result based on configuration
	if ciMode == "check_and_fail" {
		fmt.Println("Critical log changes require approval - failing CI")
		os.Exit(1)
	} else {
		fmt.Println("Critical log changes require approval - continuing with warning")
		os.Exit(0)
	}
}
