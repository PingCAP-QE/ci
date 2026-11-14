package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestParseBrokerPermissions(t *testing.T) {
	tests := []struct {
		name    string
		spec    string
		want    map[string]string
		wantErr bool
	}{
		{name: "default", spec: DefaultBrokerPermissions, want: map[string]string{"pull_requests": "read", "issues": "read"}},
		{name: "spaces and case", spec: " pull_requests = READ , issues=Read ", want: map[string]string{"pull_requests": "read", "issues": "read"}},
		{name: "write", spec: "pull_requests=write", want: map[string]string{"pull_requests": "write"}},
		{name: "missing level", spec: "pull_requests", wantErr: true},
		{name: "bad level", spec: "pull_requests=admin", wantErr: true},
		{name: "empty name", spec: "=read", wantErr: true},
		{name: "empty spec", spec: "", wantErr: true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := ParseBrokerPermissions(tt.spec)
			if tt.wantErr {
				if err == nil {
					t.Fatalf("expected an error for %q, got %v", tt.spec, got)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if len(got) != len(tt.want) {
				t.Fatalf("got %v, want %v", got, tt.want)
			}
			for name, level := range tt.want {
				if got[name] != level {
					t.Errorf("permission %s = %q, want %q", name, got[name], level)
				}
			}
		})
	}
}

func TestMintBrokerToken(t *testing.T) {
	callerToken := filepath.Join(t.TempDir(), "token")
	if err := os.WriteFile(callerToken, []byte("caller-sa-token\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	tests := []struct {
		name       string
		status     int
		response   string
		wantToken  string
		wantErr    string
		wantAuth   string
		wantRepo   string
		wantPerms  map[string]string
		callerFile string
	}{
		{
			name:      "success",
			status:    http.StatusOK,
			response:  `{"token":"ghs_minted","expires_at":"2026-11-14T09:00:00Z","source":"app"}`,
			wantToken: "ghs_minted",
			wantAuth:  "Bearer caller-sa-token",
			wantRepo:  "pingcap/tidb",
			wantPerms: map[string]string{"pull_requests": "read", "issues": "read"},
		},
		{
			name:     "forbidden",
			status:   http.StatusForbidden,
			response: `{"message":"caller is not allowed"}`,
			wantErr:  "caller is not allowed",
		},
		{
			name:     "no token in response",
			status:   http.StatusOK,
			response: `{"source":"app"}`,
			wantErr:  "returned no token",
		},
		{
			name:       "missing caller token file",
			status:     http.StatusOK,
			response:   `{"token":"never"}`,
			wantErr:    "read caller token",
			callerFile: filepath.Join(t.TempDir(), "absent"),
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var (
				gotRepo  string
				gotPerms map[string]string
			)
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method != http.MethodPost || r.URL.Path != "/v1/token" {
					t.Errorf("unexpected request %s %s", r.Method, r.URL.Path)
				}
				if got := r.Header.Get("Authorization"); tt.wantAuth != "" && got != tt.wantAuth {
					t.Errorf("Authorization = %q, want %q", got, tt.wantAuth)
				}
				var body struct {
					Repo        string            `json:"repo"`
					Permissions map[string]string `json:"permissions"`
				}
				if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
					t.Errorf("decode request: %v", err)
				}
				gotRepo, gotPerms = body.Repo, body.Permissions
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(tt.status)
				_, _ = w.Write([]byte(tt.response))
			}))
			defer server.Close()

			callerFile := tt.callerFile
			if callerFile == "" {
				callerFile = callerToken
			}
			token, err := MintBrokerToken(context.Background(), server.URL+"/", "pingcap/tidb", callerFile,
				map[string]string{"pull_requests": "read", "issues": "read"})
			if tt.wantErr != "" {
				if err == nil || !strings.Contains(err.Error(), tt.wantErr) {
					t.Fatalf("error = %v, want it to contain %q", err, tt.wantErr)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if token != tt.wantToken {
				t.Errorf("token = %q, want %q", token, tt.wantToken)
			}
			if gotRepo != tt.wantRepo {
				t.Errorf("repo = %q, want %q", gotRepo, tt.wantRepo)
			}
			for name, level := range tt.wantPerms {
				if gotPerms[name] != level {
					t.Errorf("permission %s = %q, want %q", name, gotPerms[name], level)
				}
			}
		})
	}
}
