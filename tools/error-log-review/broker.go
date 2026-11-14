package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

// DefaultBrokerCallerTokenFile is where Kubernetes projects the Pod
// ServiceAccount token. The broker authenticates the caller with a TokenReview
// of this token, so no PAT or App private key has to sit in the job Pod.
const DefaultBrokerCallerTokenFile = "/var/run/secrets/kubernetes.io/serviceaccount/token"

// DefaultBrokerPermissions is the least privilege these checks need: the PR
// diff (`pull_requests`) and the approval comments (`issues`).
const DefaultBrokerPermissions = "pull_requests=read,issues=read"

// brokerTimeout bounds one token request; the broker answers from its cache or
// calls GitHub, and a Prow job should fail fast rather than hang here.
const brokerTimeout = 15 * time.Second

type brokerTokenRequest struct {
	Repo        string            `json:"repo"`
	Permissions map[string]string `json:"permissions,omitempty"`
}

type brokerTokenResponse struct {
	Token     string `json:"token"`
	ExpiresAt string `json:"expires_at"`
}

// ParseBrokerPermissions turns "pull_requests=read,issues=read" into the map
// form the broker expects.
func ParseBrokerPermissions(spec string) (map[string]string, error) {
	permissions := make(map[string]string)
	for _, pair := range strings.Split(spec, ",") {
		pair = strings.TrimSpace(pair)
		if pair == "" {
			continue
		}
		name, level, ok := strings.Cut(pair, "=")
		if !ok {
			return nil, fmt.Errorf("permission %q is not name=level", pair)
		}
		name, level = strings.TrimSpace(name), strings.ToLower(strings.TrimSpace(level))
		if name == "" {
			return nil, fmt.Errorf("permission %q has an empty name", pair)
		}
		if level != "read" && level != "write" {
			return nil, fmt.Errorf("permission %q must be read or write", pair)
		}
		permissions[name] = level
	}
	if len(permissions) == 0 {
		return nil, fmt.Errorf("no permissions requested")
	}
	return permissions, nil
}

// MintBrokerToken asks the in-cluster github-token-broker for a GitHub App
// installation token scoped to repo (owner/name). The broker authorizes the
// caller by namespace and ServiceAccount, so the returned token carries only
// what the broker policy grants that identity.
func MintBrokerToken(ctx context.Context, brokerURL, repo, callerTokenFile string, permissions map[string]string) (string, error) {
	if brokerURL == "" {
		return "", fmt.Errorf("broker URL is empty")
	}
	if callerTokenFile == "" {
		callerTokenFile = DefaultBrokerCallerTokenFile
	}
	callerToken, err := os.ReadFile(callerTokenFile)
	if err != nil {
		return "", fmt.Errorf("read caller token: %w", err)
	}
	if strings.TrimSpace(string(callerToken)) == "" {
		return "", fmt.Errorf("caller token %s is empty", callerTokenFile)
	}

	body, err := json.Marshal(brokerTokenRequest{Repo: repo, Permissions: permissions})
	if err != nil {
		return "", fmt.Errorf("encode broker request: %w", err)
	}

	url := strings.TrimSuffix(brokerURL, "/") + "/v1/token"
	ctx, cancel := context.WithTimeout(ctx, brokerTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return "", fmt.Errorf("build broker request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+strings.TrimSpace(string(callerToken)))
	req.Header.Set("Content-Type", "application/json")

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("call broker %s: %w", url, err)
	}
	defer func() { _ = resp.Body.Close() }()

	payload, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", fmt.Errorf("read broker response: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("broker %s refused %s: %s: %s", url, repo, resp.Status, strings.TrimSpace(string(payload)))
	}

	var result brokerTokenResponse
	if err := json.Unmarshal(payload, &result); err != nil {
		return "", fmt.Errorf("decode broker response: %w", err)
	}
	if result.Token == "" {
		return "", fmt.Errorf("broker returned no token for %s", repo)
	}
	return result.Token, nil
}
