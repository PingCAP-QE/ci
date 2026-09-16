package main

import (
	"os"
	"path/filepath"
	"regexp"
	"testing"
)

func TestMatchesAnyPattern(t *testing.T) {
	tests := []struct {
		name     string
		filePath string
		patterns []string
		want     bool
	}{
		{
			name:     "exact match",
			filePath: "test.go",
			patterns: []string{"test.go"},
			want:     true,
		},
		{
			name:     "wildcard match",
			filePath: "test_file.go",
			patterns: []string{"*_test.go", "test_*.go"},
			want:     true,
		},
		{
			name:     "directory wildcard match",
			filePath: "tests/integration/test.go",
			patterns: []string{"tests/**"},
			want:     true,
		},
		{
			name:     "directory wildcard deep match",
			filePath: "tests/integration/subdir/test.go",
			patterns: []string{"tests/**"},
			want:     true,
		},
		{
			name:     "no match",
			filePath: "src/main.go",
			patterns: []string{"tests/**", "*_test.go"},
			want:     false,
		},
		{
			name:     "multiple patterns one matches",
			filePath: "examples/demo.go",
			patterns: []string{"tests/**", "examples/**", "vendor/**"},
			want:     true,
		},
		{
			name:     "empty patterns",
			filePath: "any/file.go",
			patterns: []string{},
			want:     false,
		},
		{
			name:     "top level directory",
			filePath: "tests/test.go",
			patterns: []string{"tests/**"},
			want:     true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := matchesAnyPattern(tt.filePath, tt.patterns)
			if got != tt.want {
				t.Errorf("matchesAnyPattern(%q, %v) = %v, want %v",
					tt.filePath, tt.patterns, got, tt.want)
			}
		})
	}
}

func TestCheckPRDiffWithExclusions(t *testing.T) {
	// Create a test configuration with exclusions
	config := &Config{
		Repositories: []Repository{
			{
				Name: "test/repo",
				Patterns: []Pattern{
					{
						Name:        "error_pattern",
						Description: "Test error pattern",
						Regex:       `log\.Error\(".*"\)`,
						Excludes:    []string{"tests/**", "*_test.go"},
					},
				},
				Excludes: []string{"vendor/**"},
			},
		},
	}

	// Compile the regex patterns
	for i := range config.Repositories {
		for j := range config.Repositories[i].Patterns {
			pattern := &config.Repositories[i].Patterns[j]
			compiled, _ := regexp.Compile(pattern.Regex)
			pattern.compiled = compiled
		}
	}

	checker := NewErrorLogChecker(config)

	// Create a mock diff with files in different locations
	diff := `diff --git a/main.go b/main.go
index 1234567..abcdefg 100644
--- a/main.go
+++ b/main.go
@@ -10,0 +11,1 @@
+    log.Error("production error")
diff --git a/tests/integration_test.go b/tests/integration_test.go
index 1234567..abcdefg 100644
--- a/tests/integration_test.go
+++ b/tests/integration_test.go
@@ -10,0 +11,1 @@
+    log.Error("test error")
diff --git a/vendor/lib/lib.go b/vendor/lib/lib.go
index 1234567..abcdefg 100644
--- a/vendor/lib/lib.go
+++ b/vendor/lib/lib.go
@@ -10,0 +11,1 @@
+    log.Error("vendor error")
diff --git a/main_test.go b/main_test.go
index 1234567..abcdefg 100644
--- a/main_test.go
+++ b/main_test.go
@@ -10,0 +11,1 @@
+    log.Error("unit test error")
`

	matches, err := checker.CheckPRDiff("test/repo", diff)
	if err != nil {
		t.Fatalf("CheckPRDiff failed: %v", err)
	}

	// Should only match the production file (main.go)
	// Should exclude: tests/integration_test.go (pattern exclude), vendor/lib/lib.go (repo exclude), main_test.go (pattern exclude)
	if len(matches) != 1 {
		t.Errorf("Expected 1 match, got %d", len(matches))
		for _, m := range matches {
			t.Logf("  Match: %s in %s", m.Pattern, m.File)
		}
	}

	if len(matches) > 0 && matches[0].File != "main.go" {
		t.Errorf("Expected match in main.go, got %s", matches[0].File)
	}
}

func TestLoadConfigWithFileScopedPattern(t *testing.T) {
	configData := `repositories:
  - name: "pingcap/tidb"
    patterns:
      - name: "go_mod_version"
        description: "Pattern for go.mod go and toolchain directives"
        regex: '^\s*(?:go\s+\d+\.\d+(?:\.\d+)?(?:[A-Za-z0-9.-]+)?|toolchain\s+go\d+\.\d+(?:\.\d+)?(?:[A-Za-z0-9.-]+)?)\s*$'
        files:
          - "go.mod"
    approvers:
      - "approver"
settings:
  min_approvals: 1
  require_repo_specific_approvers: true
  check_behavior:
    mode: "check_and_fail"
`

	configPath := filepath.Join(t.TempDir(), "config.yaml")
	if err := os.WriteFile(configPath, []byte(configData), 0o600); err != nil {
		t.Fatalf("failed to write config fixture: %v", err)
	}

	config, err := LoadConfig(configPath)
	if err != nil {
		t.Fatalf("LoadConfig failed: %v", err)
	}

	repo := config.GetRepository("pingcap/tidb")
	if repo == nil {
		t.Fatal("expected repository pingcap/tidb to be present")
	}

	if len(repo.Patterns) != 1 {
		t.Fatalf("expected 1 pattern, got %d", len(repo.Patterns))
	}

	if len(repo.Patterns[0].Files) != 1 || repo.Patterns[0].Files[0] != "go.mod" {
		t.Fatalf("expected file scope [go.mod], got %v", repo.Patterns[0].Files)
	}

	if repo.Patterns[0].compiled == nil {
		t.Fatal("expected regex to be compiled")
	}
}

func TestLoadSharedConfigIncludesGoModReview(t *testing.T) {
	configPath := filepath.Join("..", "..", "configs", "error-log-review", "config.yaml")

	config, err := LoadConfig(configPath)
	if err != nil {
		t.Fatalf("LoadConfig failed for shared config: %v", err)
	}

	for _, repo := range config.Repositories {
		found := false
		for _, pattern := range repo.Patterns {
			if pattern.Name != "go_mod_version" {
				continue
			}

			found = true
			if !matchesFileScope("go.mod", pattern.Files) {
				t.Errorf("repository %s go_mod_version pattern should match root go.mod", repo.Name)
			}
			if !matchesFileScope("nested/module/go.mod", pattern.Files) {
				t.Errorf("repository %s go_mod_version pattern should match nested go.mod", repo.Name)
			}
		}

		if !found {
			t.Errorf("repository %s is missing go_mod_version pattern", repo.Name)
		}
	}
}

func TestCheckPRDiffWithFileScopedPatterns(t *testing.T) {
	config := &Config{
		Repositories: []Repository{
			{
				Name: "pingcap/tidb",
				Patterns: []Pattern{
					{
						Name:        "go_mod_version",
						Description: "Pattern for go.mod go and toolchain directives",
						Regex:       `^\s*(?:go\s+\d+\.\d+(?:\.\d+)?(?:[A-Za-z0-9.-]+)?|toolchain\s+go\d+\.\d+(?:\.\d+)?(?:[A-Za-z0-9.-]+)?)\s*$`,
						Files:       []string{"go.mod"},
					},
					{
						Name:        "error_pattern",
						Description: "Pattern for error log changes",
						Regex:       `log\.Error\(".*"\)`,
					},
				},
			},
		},
	}

	for i := range config.Repositories {
		for j := range config.Repositories[i].Patterns {
			pattern := &config.Repositories[i].Patterns[j]
			compiled, _ := regexp.Compile(pattern.Regex)
			pattern.compiled = compiled
		}
	}

	checker := NewErrorLogChecker(config)

	diff := `diff --git a/go.mod b/go.mod
index 1234567..abcdefg 100644
--- a/go.mod
+++ b/go.mod
@@ -1,2 +1,4 @@
+go 1.24.1
+toolchain go1.24.2
+toolchain local
diff --git a/components/worker/go.mod b/components/worker/go.mod
index 1234567..abcdefg 100644
--- a/components/worker/go.mod
+++ b/components/worker/go.mod
@@ -1,1 +1,2 @@
+go 1.23.7
diff --git a/docs/upgrade.md b/docs/upgrade.md
index 1234567..abcdefg 100644
--- a/docs/upgrade.md
+++ b/docs/upgrade.md
@@ -10,0 +11,1 @@
+go 1.25.0
diff --git a/main.go b/main.go
index 1234567..abcdefg 100644
--- a/main.go
+++ b/main.go
@@ -10,0 +11,1 @@
+    log.Error("production error")
`

	matches, err := checker.CheckPRDiff("pingcap/tidb", diff)
	if err != nil {
		t.Fatalf("CheckPRDiff failed: %v", err)
	}

	if len(matches) != 4 {
		t.Fatalf("expected 4 matches, got %d: %+v", len(matches), matches)
	}

	var goModMatches, logMatches int
	for _, match := range matches {
		switch match.Pattern {
		case "go_mod_version":
			goModMatches++
			if filepath.Base(match.File) != "go.mod" {
				t.Errorf("expected go.mod scoped match, got %s", match.File)
			}
		case "error_pattern":
			logMatches++
			if match.File != "main.go" {
				t.Errorf("expected error pattern to match main.go, got %s", match.File)
			}
		default:
			t.Errorf("unexpected pattern %s", match.Pattern)
		}
	}

	if goModMatches != 3 {
		t.Errorf("expected 3 go.mod matches, got %d", goModMatches)
	}

	if logMatches != 1 {
		t.Errorf("expected 1 error log match, got %d", logMatches)
	}
}

func TestTiCDCExclusionScenario(t *testing.T) {
	// Create a test configuration similar to pingcap/ticdc
	config := &Config{
		Repositories: []Repository{
			{
				Name: "pingcap/ticdc",
				Patterns: []Pattern{
					{
						Name:        "string_literals",
						Description: "Pattern for string literals",
						Regex:       `log\.Fatalf\(".*"\)`,
						Excludes:    []string{"tests/**"},
					},
					{
						Name:        "variables_and_functions",
						Description: "Pattern for variables and function calls",
						Regex:       `log\.Fatalf\(`,
						Excludes:    []string{"tests/**"},
					},
				},
			},
		},
	}

	// Compile the regex patterns
	for i := range config.Repositories {
		for j := range config.Repositories[i].Patterns {
			pattern := &config.Repositories[i].Patterns[j]
			compiled, _ := regexp.Compile(pattern.Regex)
			pattern.compiled = compiled
		}
	}

	checker := NewErrorLogChecker(config)

	// Simulate the diff from the issue: tests/integration_tests/ddl_wait/test.go
	diff := `diff --git a/tests/integration_tests/ddl_wait/test.go b/tests/integration_tests/ddl_wait/test.go
index 1234567..abcdefg 100644
--- a/tests/integration_tests/ddl_wait/test.go
+++ b/tests/integration_tests/ddl_wait/test.go
@@ -10,0 +11,1 @@
+    log.Fatalf("insert value failed:, host:%s, port:%s, k:%d, i:%d, val:%d, num:%d, err: %+v", host, port, k, i, val, num, err)
diff --git a/pkg/main.go b/pkg/main.go
index 1234567..abcdefg 100644
--- a/pkg/main.go
+++ b/pkg/main.go
@@ -10,0 +11,1 @@
+    log.Fatalf("production error: %v", err)
`

	matches, err := checker.CheckPRDiff("pingcap/ticdc", diff)
	if err != nil {
		t.Fatalf("CheckPRDiff failed: %v", err)
	}

	// Should only match the production file (pkg/main.go)
	// Should NOT match tests/integration_tests/ddl_wait/test.go (excluded by tests/**)
	// The string_literals pattern requires quotes which the production line has
	// The variables_and_functions pattern matches any log.Fatalf( call
	if len(matches) < 1 {
		t.Errorf("Expected at least 1 match in pkg/main.go, got %d", len(matches))
		return
	}

	// Verify all matches are from pkg/main.go, not from tests directory
	for _, match := range matches {
		if match.File != "pkg/main.go" {
			t.Errorf("Expected match only in pkg/main.go, but got match in %s", match.File)
		}
	}

	// Verify no matches from test files
	for _, match := range matches {
		if len(match.File) >= 5 && match.File[:5] == "tests" {
			t.Errorf("Test file should be excluded but got match in %s", match.File)
		}
	}
}
