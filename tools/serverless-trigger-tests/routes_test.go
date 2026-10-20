package routes_test

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"github.com/google/cel-go/cel"
	"github.com/google/cel-go/common/types"
	"github.com/google/cel-go/common/types/ref"
	"github.com/google/cel-go/ext"
	"gopkg.in/yaml.v3"
)

const root = "../../"
const sha = "0123456789abcdef0123456789abcdef01234567"

var products = []string{"tidb-cse", "pd-cse", "tidb-operator-cse", "cassandra-on-mysql", "qdrant-on-tidb"}

func TestServerlessUsesGitVersionAndPreservesLegacyCLI(t *testing.T) {
	var task struct {
		Spec struct {
			Steps []struct {
				Name, Script  string
				Command, Args []string
				When          []struct {
					Input, Operator string
					Values          []string
				}
			}
		}
	}
	load(t, "tekton/v1/tasks/pingcap-get-set-release-version.yaml", &task)
	var cleanup string
	var cleanupArgs []string
	for _, step := range task.Spec.Steps {
		switch step.Name {
		case "cleanup-tags":
			cleanup = step.Script
			cleanupArgs = step.Args
		case "compute-version":
			wantCommand := "deno run --allow-read --allow-write https://cdn.jsdelivr.net/gh/PingCAP-QE/ci@main/scripts/flow/build/versioning-strategy.ts"
			wantArgs := "--git_version_file=/workspace/raw-version.txt --contain_branches_file=/workspace/branches.txt --save_build_git_tag_file=/workspace/new-tag --save_release_version_file=$(results.version.path)"
			if strings.Join(step.Command, " ") != wantCommand || strings.Join(step.Args, " ") != wantArgs {
				t.Fatal("the shared version script invocation must remain unchanged")
			}
			if len(step.When) != 1 || step.When[0].Input != "$(params.component)" || step.When[0].Operator != "notin" || strings.Join(step.When[0].Values, ",") != strings.Join(products, ",") {
				t.Fatal("only Serverless must bypass the shared branch-based version strategy")
			}
		}
	}
	if cleanup == "" {
		t.Fatal("missing version input preparation")
	}
	type versionCase struct {
		product, branch, baseline, ref, remote, version, tag string
		commit                                               bool
	}
	cases := []versionCase{}
	for _, input := range []struct{ product, branch, baseline string }{
		{"tidb-cse", "main-8.5-keyspace", "v8.5.3-alpha.0"},
		{"pd-cse", "release-8.5-keyspace", "v8.5.0-alpha.0"},
		{"tidb-operator-cse", "serverless-on-release-1.4", "v1.4.0-alpha.0"},
		{"cassandra-on-mysql", "master", "v0.1.0-alpha.0"},
		{"qdrant-on-tidb", "main", "v1.3.4-alpha.0"},
	} {
		cases = append(cases, versionCase{product: input.product, branch: input.branch, baseline: input.baseline, ref: input.baseline, version: input.baseline})
		cases = append(cases, versionCase{product: input.product, branch: input.branch, baseline: input.baseline, ref: input.branch, version: "raw", commit: true})
	}
	for _, date := range []string{"20260206", "202603", "202604", "202605", "202607"} {
		branch := "release-8.5-keyspace-" + date
		cases = append(cases, versionCase{product: "tidb-cse", branch: branch, baseline: "v8.5.3-alpha." + date, ref: branch, version: "raw", commit: true})
	}
	cases = append(cases,
		versionCase{product: "tidb-cse", branch: "validation", baseline: "v8.5.3-alpha.0", ref: "pull/42", remote: "release-8.5-keyspace-202603", version: "raw", commit: true},
		versionCase{product: "tidb-cse", branch: "main-8.5-keyspace", baseline: "v8.5.3-rc.1", ref: "v8.5.3-rc.1", version: "v8.5.3-rc.1"},
		versionCase{product: "tidb", branch: "main-8.5-keyspace", baseline: "v8.5.3-alpha.0", ref: "main-8.5-keyspace", version: "raw", commit: true},
		versionCase{product: "tidb", branch: "release-8.5", baseline: "v8.5.3-alpha.0", ref: "v8.5.3-alpha.0", version: "v8.5.3-pre", tag: "v8.5.3"},
	)
	deno, _ := exec.LookPath("deno")
	for _, tc := range cases {
		t.Run(tc.product+"/"+tc.ref, func(t *testing.T) {
			dir := t.TempDir()
			git := func(args ...string) {
				cmd := exec.Command("git", append([]string{"-c", "user.name=Version Test", "-c", "user.email=version@example.invalid", "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false"}, args...)...)
				cmd.Dir, cmd.Env = dir, append(os.Environ(), "GIT_CONFIG_GLOBAL=/dev/null", "GIT_CONFIG_NOSYSTEM=1")
				if output, err := cmd.CombinedOutput(); err != nil {
					t.Fatalf("git %v: %v: %s", args, err, output)
				}
			}
			git("init", "--initial-branch="+tc.branch)
			git("commit", "--allow-empty", "-m", "baseline")
			git("tag", "-a", tc.baseline, "-m", "baseline")
			if tc.commit {
				git("commit", "--allow-empty", "-m", "next")
			}
			if tc.remote != "" {
				git("update-ref", "refs/remotes/origin/"+tc.remote, "HEAD")
			}
			outputDir := t.TempDir()
			args := []string{"-c", strings.ReplaceAll(cleanup, "/workspace/", outputDir+"/"), "version-task"}
			replace := strings.NewReplacer("$(params.component)", tc.product, "$(results.version.path)", filepath.Join(outputDir, "version"))
			for _, arg := range cleanupArgs {
				args = append(args, replace.Replace(arg))
			}
			cmd := exec.Command("sh", args...)
			cmd.Dir, cmd.Env = dir, append(os.Environ(), "GIT_CONFIG_GLOBAL=/dev/null", "GIT_CONFIG_NOSYSTEM=1")
			if output, err := cmd.CombinedOutput(); err != nil {
				t.Fatalf("prepare version inputs: %v: %s", err, output)
			}
			branches, _ := os.ReadFile(filepath.Join(outputDir, "branches.txt"))
			if tc.product != "tidb" {
				if _, err := os.Stat(filepath.Join(outputDir, "branches.txt")); !os.IsNotExist(err) {
					t.Fatal("Serverless must not collect branch classification inputs")
				}
			}
			if tc.product == "tidb" && tc.branch == "main-8.5-keyspace" && !strings.Contains(string(branches), tc.branch) {
				t.Fatal("legacy product branch input changed")
			}
			if tc.product == "tidb" {
				if deno == "" {
					t.Log("Deno unavailable: legacy input preparation checked; compute tests run separately")
					return
				}
				script, _ := filepath.Abs(filepath.Join(root, "scripts/flow/build/versioning-strategy.ts"))
				cmd = exec.Command(deno, "run", "--allow-read", "--allow-write", script,
					"--git_version_file="+filepath.Join(outputDir, "raw-version.txt"), "--contain_branches_file="+filepath.Join(outputDir, "branches.txt"),
					"--save_build_git_tag_file="+filepath.Join(outputDir, "new-tag"), "--save_release_version_file="+filepath.Join(outputDir, "version"))
				if output, err := cmd.CombinedOutput(); err != nil {
					t.Fatalf("original version CLI: %v: %s", err, output)
				}
			}
			want := tc.version
			if want == "raw" {
				raw, _ := os.ReadFile(filepath.Join(outputDir, "raw-version.txt"))
				want = strings.TrimSpace(string(raw))
			}
			version, _ := os.ReadFile(filepath.Join(outputDir, "version"))
			tag, err := os.ReadFile(filepath.Join(outputDir, "new-tag"))
			if string(version) != want || string(tag) != tc.tag || (tc.tag == "" && !os.IsNotExist(err)) {
				t.Fatalf("version/tag: %s/%s, want %s/%s", version, tag, want, tc.tag)
			}
		})
	}
}

func TestMountedGitCredentials(t *testing.T) {
	var task struct {
		Spec struct {
			Steps []struct{ Name, Script string }
		}
	}
	load(t, "tekton/v1/tasks/pingcap-build-binaries-linux.yaml", &task)
	var script string
	for _, step := range task.Spec.Steps {
		if step.Name == "build" {
			script = strings.Split(step.Script, "script=\"/workspace/build-package-artifacts.sh\"")[0]
		}
	}
	if script == "" {
		t.Fatal("missing credential setup")
	}
	tmp := t.TempDir()
	credentials := filepath.Join(tmp, "credentials")
	if err := os.Mkdir(credentials, 0700); err != nil {
		t.Fatal(err)
	}
	for name, value := range map[string]string{"username": "test-user", "password": "test-password"} {
		if err := os.WriteFile(filepath.Join(credentials, name), []byte(value), 0600); err != nil {
			t.Fatal(err)
		}
	}
	script = strings.NewReplacer(
		"$(workspaces.git-credentials.bound)", "true",
		"$(workspaces.git-credentials.path)", credentials,
		"$(params.git-ref)", "feature/test",
		"/workspace/serverless-git-credential", filepath.Join(tmp, "git-credential"),
	).Replace(script)
	script += "printf 'protocol=https\\nhost=github.com\\n\\n' | git credential fill\n"
	cmd := exec.Command("sh", "-c", script)
	cmd.Env = append(os.Environ(), "HOME="+tmp, "GIT_CONFIG_NOSYSTEM=1", "GIT_CONFIG_GLOBAL="+filepath.Join(tmp, ".gitconfig"))
	output, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("credential helper: %v: %s", err, output)
	}
	if !strings.Contains(string(output), "username=test-user\n") || !strings.Contains(string(output), "password=test-password\n") {
		t.Fatal("mounted credentials were not returned")
	}
	config, err := os.ReadFile(filepath.Join(tmp, ".gitconfig"))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(config), "test-password") {
		t.Fatal("secret copied into Git config")
	}
	for _, remote := range []string{"git@github.com:tidbcloud/private.git", "ssh://git@github.com/tidbcloud/private.git"} {
		cmd := exec.Command("git", "ls-remote", "--get-url", remote)
		cmd.Env = append(os.Environ(), "HOME="+tmp, "GIT_CONFIG_NOSYSTEM=1", "GIT_CONFIG_GLOBAL="+filepath.Join(tmp, ".gitconfig"))
		output, err := cmd.CombinedOutput()
		if err != nil || strings.TrimSpace(string(output)) != "https://github.com/tidbcloud/private.git" {
			t.Fatalf("SSH rewrite: %v: %s", err, output)
		}
	}
}

type expression struct {
	Name  string
	Value any
}
type trigger struct {
	Spec struct {
		Interceptors []struct{ Params []expression }
		Bindings     []struct{ Name, Value, Ref string }
		Template     struct{ Ref string }
	}
}

func load(t *testing.T, path string, v any) {
	t.Helper()
	b, e := os.ReadFile(filepath.Join(root, path))
	if e != nil {
		t.Fatal(e)
	}
	if e = yaml.Unmarshal(b, v); e != nil {
		t.Fatal(path, e)
	}
}
func evaluate(t *testing.T, expression string, body map[string]any, header map[string]string) ref.Val {
	t.Helper()
	env, err := cel.NewEnv(cel.Variable("body", cel.DynType), cel.Variable("header", cel.MapType(cel.StringType, cel.StringType)), ext.Strings(),
		cel.Function("canonical", cel.MemberOverload("header_canonical", []*cel.Type{cel.MapType(cel.StringType, cel.StringType), cel.StringType}, cel.StringType,
			cel.BinaryBinding(func(_ ref.Val, key ref.Val) ref.Val {
				return types.String(header[strings.ToLower(string(key.(types.String)))])
			}))))
	if err != nil {
		t.Fatal(err)
	}
	ast, issues := env.Compile(expression)
	if issues.Err() != nil {
		t.Fatalf("compile %s: %v", expression, issues.Err())
	}
	p, err := env.Program(ast)
	if err != nil {
		t.Fatal(err)
	}
	v, _, err := p.Eval(map[string]any{"body": body, "header": header})
	if err != nil {
		t.Fatalf("eval %s: %v", expression, err)
	}
	return v
}
func filter(t *testing.T, tr trigger, body map[string]any, header map[string]string) bool {
	t.Helper()
	for _, i := range tr.Spec.Interceptors {
		for _, p := range i.Params {
			if p.Name == "filter" {
				return evaluate(t, p.Value.(string), body, header) == types.True
			}
		}
	}
	t.Fatal("missing filter")
	return false
}
func payload(repo, kind string) map[string]any {
	body := map[string]any{"repository": map[string]any{"name": strings.Split(repo, "/")[1], "full_name": repo, "clone_url": "https://github.com/" + repo + ".git"}, "ref": "refs/heads/feature/test", "after": sha, "deleted": false}
	if kind == "tag-create" {
		body["ref"] = "v1.2.3-alpha.0"
		body["ref_type"] = "tag"
	}
	if kind == "pr" {
		body["pull_request"] = map[string]any{"number": int64(42), "head": map[string]any{"sha": sha}, "base": map[string]any{"ref": "main"}}
	}
	return body
}
func TestDevbuildRoutesAndLegacyExclusion(t *testing.T) {
	kinds := map[string]string{"branch-push": "push", "tag-create": "create", "pr": "pull_request"}
	generic, _ := filepath.Glob(filepath.Join(root, "tekton/v1/triggers/triggers/env-gcp/_/fake-github/*.yaml"))
	for _, product := range products {
		for kind, event := range kinds {
			for _, arch := range []string{"amd64", "arm64"} {
				for _, fork := range []bool{false, true} {
					name := product + "/" + kind + "/" + arch
					if fork {
						name += "/fork"
					}
					t.Run(name, func(t *testing.T) {
						repo := "tidbcloud/" + product
						if fork {
							repo = "custom-owner/custom-fork"
						}
						body := payload(repo, kind)
						header := map[string]string{"ce-type": "net.pingcap.tibuild.devbuild." + event, "ce-paramproduct": product, "ce-paramgitrevision": sha, "ce-paramplatform": "linux/" + arch, "ce-paramgithubrepo": repo}
						var tr trigger
						load(t, "tekton/v1/triggers/triggers/env-gcp/_/serverless/devbuild-"+kind+".yaml", &tr)
						if !filter(t, tr, body, header) {
							t.Fatal("new route rejected request")
						}
						if tr.Spec.Template.Ref != "build-serverless-single-platform" {
							t.Fatal("devbuild must use single-platform template")
						}
						for _, i := range tr.Spec.Interceptors {
							for _, p := range i.Params {
								if p.Name != "overlays" {
									continue
								}
								for _, raw := range p.Value.([]any) {
									overlay := raw.(map[string]any)
									v := evaluate(t, overlay["expression"].(string), body, header)
									switch overlay["key"] {
									case "product":
										if v != types.String(product) {
											t.Fatal(v)
										}
									case "revision":
										if v != types.String(sha) {
											t.Fatal(v)
										}
									case "refspec":
										if v != types.String("+"+sha+":refs/devbuild/source") {
											t.Fatal(v)
										}
									}
								}
							}
						}
						for _, path := range generic {
							var old trigger
							load(t, strings.TrimPrefix(path, root), &old)
							if filter(t, old, body, header) {
								t.Fatalf("duplicate legacy route: %s", path)
							}
						}
						header["ce-paramplatform"] = "linux"
						if filter(t, tr, body, header) {
							t.Fatal("unexpanded dual-platform event accepted")
						}
						header["ce-paramplatform"] = "darwin/arm64"
						if filter(t, tr, body, header) {
							t.Fatal("Darwin accepted")
						}
						header["ce-paramplatform"] = "linux/" + arch
						header["ce-type"] = "net.pingcap.tibuild.hotfix." + event
						if filter(t, tr, body, header) {
							t.Fatal("hotfix accepted")
						}
					})
				}
			}
		}
	}
}
func TestLegacyCanonicalEventsAndOldProducts(t *testing.T) {
	var tr trigger
	load(t, "tekton/v1/triggers/triggers/env-gcp/_/serverless/devbuild-branch-push.yaml", &tr)
	header := map[string]string{"ce-type": "net.pingcap.tibuild.devbuild.push", "ce-paramplatform": "linux/amd64"}
	for _, product := range products {
		if !filter(t, tr, payload("tidbcloud/"+product, "branch-push"), header) {
			t.Fatal("legacy event rejected", product)
		}
	}
	body := payload("pingcap/tidb", "branch-push")
	if filter(t, tr, body, header) {
		t.Fatal("old product captured")
	}
	var old trigger
	load(t, "tekton/v1/triggers/triggers/env-gcp/_/fake-github/fake-github-branch-push-single-platform.yaml", &old)
	if !filter(t, old, body, header) {
		t.Fatal("old TiDB route broken")
	}
}
func TestFormalWhitelist(t *testing.T) {
	var push, tag trigger
	load(t, "tekton/staging/serverless/branch-push.yaml", &push)
	load(t, "tekton/staging/serverless/tag-create.yaml", &tag)
	branches := []string{"main-8.5-keyspace", "release-8.5-keyspace", "serverless-on-release-1.4", "master", "main"}
	for i, product := range products {
		body := payload("tidbcloud/"+product, "branch-push")
		body["ref"] = "refs/heads/" + branches[i]
		if !filter(t, push, body, nil) {
			t.Fatal("maintained branch rejected", product)
		}
		body["ref"] = "refs/heads/feature/unapproved"
		if filter(t, push, body, nil) {
			t.Fatal("feature branch accepted")
		}
		body = payload("tidbcloud/"+product, "tag-create")
		if !filter(t, tag, body, nil) {
			t.Fatal("release tag rejected")
		}
		body["ref"] = "production-latest"
		if filter(t, tag, body, nil) {
			t.Fatal("arbitrary tag accepted")
		}
	}
	for _, repo := range []string{"tidbcloud/tikv", "tidbcloud/tiflash-cse", "tidbcloud/qdrant-prod-us-east-1"} {
		if filter(t, tag, payload(repo, "tag-create"), nil) {
			t.Fatal("out-of-scope source", repo)
		}
	}
}

func TestTemplatesAndPipelineContracts(t *testing.T) {
	for _, pair := range []struct {
		name  string
		count int
	}{{"single-platform", 1}, {"linux", 2}} {
		var tr struct {
			Spec struct {
				Resourcetemplates []struct {
					Spec struct {
						Params     []struct{ Name, Value string }
						Workspaces []struct{ Name string }
					}
				}
			}
		}
		load(t, "tekton/v1/triggers/templates/_/build-serverless-"+pair.name+".yaml", &tr)
		if len(tr.Spec.Resourcetemplates) != pair.count {
			t.Fatalf("%s generated %d runs", pair.name, len(tr.Spec.Resourcetemplates))
		}
		for _, r := range tr.Spec.Resourcetemplates {
			params := map[string]string{}
			for _, p := range r.Spec.Params {
				params[p.Name] = p.Value
			}
			if params["deliver-artifacts"] != "false" || params["require-artifacts"] != "true" {
				t.Fatal("unsafe delivery/empty artifact defaults", params)
			}
			found := false
			for _, w := range r.Spec.Workspaces {
				if w.Name == "git-credentials" {
					found = true
				}
				if w.Name == "mac-ssh-credentials" {
					t.Fatal("Linux template requires Mac credentials")
				}
			}
			if !found {
				t.Fatal("private dependency credentials not bound")
			}
		}
	}
	var pipeline struct {
		Spec struct {
			Params  []struct{ Name, Default string }
			Results []struct{ Name, Value string }
			Tasks   []struct {
				Name    string
				TaskRef struct{ Name string } `yaml:"taskRef"`
				Params  []struct{ Name, Value string }
				When    []struct{ Input string }
			}
		}
	}
	load(t, "tekton/v1/pipelines/pingcap-build-package-linux.yaml", &pipeline)
	if len(pipeline.Spec.Results) != 2 {
		t.Fatal("the existing Pipeline results contract must stay unchanged")
	}
	wantResults := map[string]string{
		"pushed-binaries": "$(tasks.build-binaries.results.pushed)",
		"pushed-images":   "$(tasks.build-images.results.pushed)",
	}
	for _, result := range pipeline.Spec.Results {
		if value, ok := wantResults[result.Name]; !ok || value != result.Value {
			t.Fatalf("changed Pipeline result: %+v", result)
		}
	}
	for _, param := range pipeline.Spec.Params {
		if param.Name == "artifacts-revision" || param.Name == "versioning-strategy-url" {
			t.Fatalf("validation-only parameter in the production Pipeline: %s", param.Name)
		}
	}
	for _, task := range pipeline.Spec.Tasks {
		var definition struct {
			Spec struct{ Params []struct{ Name string } }
		}
		taskFile := "tekton/v1/tasks/" + task.TaskRef.Name + ".yaml"
		if _, err := os.Stat(filepath.Join(root, taskFile)); err != nil {
			_ = filepath.WalkDir(filepath.Join(root, "tekton/v1/tasks"), func(path string, d os.DirEntry, err error) error {
				if err != nil || d.IsDir() || !strings.HasSuffix(path, ".yaml") {
					return nil
				}
				var candidate struct{ Metadata struct{ Name string } }
				b, _ := os.ReadFile(path)
				_ = yaml.Unmarshal(b, &candidate)
				if candidate.Metadata.Name == task.TaskRef.Name {
					taskFile = strings.TrimPrefix(path, root)
				}
				return nil
			})
		}
		load(t, taskFile, &definition)
		declared := map[string]bool{}
		for _, p := range definition.Spec.Params {
			declared[p.Name] = true
		}
		for _, p := range task.Params {
			if !declared[p.Name] {
				t.Fatalf("%s passes undeclared param %s to %s", task.Name, p.Name, task.TaskRef.Name)
			}
		}
		if strings.HasPrefix(task.Name, "deliver-") {
			gated := false
			for _, w := range task.When {
				if w.Input == "$(params.deliver-artifacts)" {
					gated = true
				}
			}
			if !gated {
				t.Fatal("delivery not gated", task.Name)
			}
		}
	}
}
