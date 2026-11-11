package main

import (
	"os"
	"path/filepath"
	"testing"
)

func writeConfig(t *testing.T, body string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "config.yaml")
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatalf("write config: %v", err)
	}
	return path
}

// A config without a settings block must not turn the gate off: zero approvals
// and an empty mode would otherwise make every run pass.
func TestLoadConfigDefaultsToFailClosed(t *testing.T) {
	path := writeConfig(t, `
repositories:
  - name: pingcap/tidb
    patterns:
      - name: basic
        regex: 'LOG_ERROR'
    approvers:
      - someone
`)

	config, err := LoadConfig(path)
	if err != nil {
		t.Fatalf("load config: %v", err)
	}
	if config.Settings.MinApprovals != 1 {
		t.Errorf("min approvals = %d, want 1", config.Settings.MinApprovals)
	}
	if config.Settings.CheckBehavior.Mode != "check_and_fail" {
		t.Errorf("mode = %q, want check_and_fail", config.Settings.CheckBehavior.Mode)
	}
}

// An explicit threshold and mode are preserved.
func TestLoadConfigKeepsExplicitSettings(t *testing.T) {
	path := writeConfig(t, `
repositories: []
settings:
  min_approvals: 2
  check_behavior:
    mode: check_and_warn
`)

	config, err := LoadConfig(path)
	if err != nil {
		t.Fatalf("load config: %v", err)
	}
	if config.Settings.MinApprovals != 2 {
		t.Errorf("min approvals = %d, want 2", config.Settings.MinApprovals)
	}
	if config.Settings.CheckBehavior.Mode != "check_and_warn" {
		t.Errorf("mode = %q, want check_and_warn", config.Settings.CheckBehavior.Mode)
	}
}
