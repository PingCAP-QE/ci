package main

import (
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
