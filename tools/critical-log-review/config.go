package main

import (
	"fmt"
	"os"
	"regexp"

	"gopkg.in/yaml.v3"
)

type Pattern struct {
	Name        string `yaml:"name"`
	Description string `yaml:"description"`
	Regex       string `yaml:"regex"`
	compiled    *regexp.Regexp
}

type Repository struct {
	Name      string    `yaml:"name"`
	Patterns  []Pattern `yaml:"patterns"`
	Approvers []string  `yaml:"approvers"`
}

type CheckBehavior struct {
	Mode string `yaml:"mode"`
}

type Settings struct {
	MinApprovals                 int           `yaml:"min_approvals"`
	RequireRepoSpecificApprovers bool          `yaml:"require_repo_specific_approvers"`
	CheckBehavior                CheckBehavior `yaml:"check_behavior"`
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
