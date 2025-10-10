package main

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"

	"gopkg.in/yaml.v3"
)

type Pattern struct {
	Name        string   `yaml:"name"`
	Description string   `yaml:"description"`
	Regex       string   `yaml:"regex"`
	Excludes    []string `yaml:"excludes,omitempty"`
	compiled    *regexp.Regexp
}

type Repository struct {
	Name      string    `yaml:"name"`
	Patterns  []Pattern `yaml:"patterns"`
	Approvers []string  `yaml:"approvers"`
	Excludes  []string  `yaml:"excludes,omitempty"`
}

type CheckBehavior struct {
	Mode string `yaml:"mode"`
}

type Settings struct {
	MinApprovals                 int           `yaml:"min_approvals"`
	RequireRepoSpecificApprovers bool          `yaml:"require_repo_specific_approvers"`
	CheckBehavior                CheckBehavior `yaml:"check_behavior"`
	// Hint is an optional message shown when approval is required
	Hint string `yaml:"hint,omitempty"`
}

type Config struct {
	Repositories []Repository `yaml:"repositories"`
	Settings     Settings     `yaml:"settings"`
}

func LoadConfig(configPath string) (*Config, error) {
	data, err := os.ReadFile(configPath)
	if err != nil {
		return nil, fmt.Errorf("failed to read config file: %w", err)
	}

	var config Config
	if err := yaml.Unmarshal(data, &config); err != nil {
		return nil, fmt.Errorf("failed to parse config file: %w", err)
	}

	// Compile regex patterns
	for i := range config.Repositories {
		for j := range config.Repositories[i].Patterns {
			pattern := &config.Repositories[i].Patterns[j]
			compiled, err := regexp.Compile(pattern.Regex)
			if err != nil {
				return nil, fmt.Errorf("failed to compile regex for %s/%s: %w",
					config.Repositories[i].Name, pattern.Name, err)
			}
			pattern.compiled = compiled
		}
	}

	return &config, nil
}

func (c *Config) GetRepository(repoName string) *Repository {
	for i := range c.Repositories {
		if c.Repositories[i].Name == repoName {
			return &c.Repositories[i]
		}
	}
	return nil
}

// matchesAnyPattern checks if a file path matches any of the provided glob patterns
func matchesAnyPattern(filePath string, patterns []string) bool {
	for _, pattern := range patterns {
		matched, err := filepath.Match(pattern, filePath)
		if err != nil {
			// If pattern is invalid, skip it
			continue
		}
		if matched {
			return true
		}
		// Also try matching with /** pattern (e.g., tests/** should match tests/a/b/c.go)
		if matched, _ := filepath.Match(pattern, filepath.Dir(filePath)+"/"); matched {
			return true
		}
		// Check if the pattern ends with /** and the path starts with the prefix
		if len(pattern) > 3 && pattern[len(pattern)-3:] == "/**" {
			prefix := pattern[:len(pattern)-3]
			if filePath == prefix || len(filePath) > len(prefix) && filePath[:len(prefix)+1] == prefix+"/" {
				return true
			}
		}
	}
	return false
}
