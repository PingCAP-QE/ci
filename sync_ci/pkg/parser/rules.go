package parser

import (
	"encoding/json"
	"github.com/pingcap/log"
	"io/ioutil"
	"os"
	"regexp"
	"strings"
	"time"
)

var envRules = map[string]string{}

var envParsers = []parser{
	&envParser{envRules},
}
var caseParsers = []parser{
	&tidbUtParser{map[string]bool{"tidb_ghpr_unit_test": true, "tidb_ghpr_check": true, "tidb_ghpr_check_2": true}},
	&tidbITParser{`^tidb_ghpr`},
	&tikvUtParser{},
}

var compileParsers = []parser{
	&simpleParser{rules: []rule{
		{name: "rewrite error", patterns: []string{"Rewrite error"}},
		{name: "go.mod error", patterns: []string{"go: errors parsing go.mod"}},
		{name: "plugin error", patterns: []string{"compile plugin source code failure"}},
		{name: "syntax error", patterns: []string{"syntax error:"}},
		{name: "build failpoint error", patterns: []string{`make: \*\*\* \[failpoint-enable\] Error`}},
		{jobs: []string{"tidb_ghpr_check"}, name: "server_check build error", patterns: []string{`make: \*\*\* [server_check] Error`}},
		{jobs: []string{"tidb_ghpr_check_2"}, name: "replace parser error", patterns: []string{`replace.*github.com/pingcap/parser`}},
		{jobs: []string{"tidb_ghpr_check_2"}, name: "build error", patterns: []string{`\[build failed\]`}},
		{jobs: []string{"tidb_ghpr_check_2"}, name: "build error", patterns: []string{`make: \*\*\* \[(server|importer)\] Error`}},
	}},
}

var checkParsers = []parser{
	&simpleParser{rules: []rule{
		{jobs: []string{"tidb_ghpr_check"}, name: "check error", patterns:
		[]string{`make: \*\*\* \[(fmt|errcheck|unconvert|lint|tidy|testSuite|check-static|vet|staticcheck|errdoc|checkdep|gogenerate)\] Error`}},
	}},
}

type parser interface {
	parse(job string, lines []string) []string
}

// if job is empty , it matches all jobs
type rule struct {
	jobs     []string
	name     string
	patterns []string
}
type simpleParser struct {
	rules []rule
}

func (s *simpleParser) parse(job string, lines []string) []string {
	var res []string
	for _, r := range s.rules {
		matched := len(r.jobs) == 0
		for _, j := range r.jobs {
			if j == job {
				matched = true
			}
		}
		if ! matched {
			break
		}
		for _, p := range r.patterns {
			matched, _ = regexp.MatchString(p, lines[0])
			if matched {
				res = append(res, p)
				break
			}
		}
	}
	return res
}

type tidbUtParser struct {
	jobs map[string]bool
}

func (t *tidbUtParser) parse(job string, lines []string) []string {
	var res []string
	pattern := `FAIL:|panic: runtime error:.*|panic: test timed out|WARNING: DATA RACE|leaktest.go.* Test .* check-count .* appears to have leaked: .*`
	r := regexp.MustCompile(pattern)
	if _, ok := t.jobs[job]; !ok {
		return res
	}
	matchedStr := r.FindString(lines[0])
	if len(matchedStr) == 0 {
		return res
	}
	if strings.Contains(matchedStr, "leaktest.go") {
		failLine := strings.TrimSpace(lines[0])
		prefix := regexp.MustCompile(`leaktest.go:[0-9]*:`).FindAllString(failLine, -1)[0]
		failDetail := strings.Join([]string{strings.Split(prefix, ":")[0], strings.TrimSpace(strings.Split(strings.Split(failLine, prefix)[1], "(0x")[0])}, ":")
		res = append(res, failDetail)
		return res
	}
	if matchedStr == "WARNING: DATA RACE" {
		failLine := strings.TrimSpace(lines[2])
		failDetail := strings.Join([]string{"DATA RACE", regexp.MustCompile("[^\\s]+").FindAllString(failLine, -1)[1]}, ":")
		res = append(res, failDetail)
		return res
	}
	if strings.Contains(strings.ToLower(matchedStr), "panic:") {
		res = append(res, matchedStr)
		return res
	}
	//parse func fail
	if strings.Contains(lines[0], "FAIL: TestT") {
		return res
	}
	failLine := strings.TrimSpace(lines[0])
	failCodePosition := strings.Split(
		strings.Split(failLine, " ")[2], ":")[0]
	failDetail := strings.Join([]string{failCodePosition, strings.Split(failLine, " ")[3]}, ":")
	res = append(res, failDetail)
	return res
}

type tidbITParser struct {
	jobPattern string
}

//TODO require other rules
func (t *tidbITParser) parse(job string, lines []string) []string {
	var res []string
	if len(regexp.MustCompile(t.jobPattern).FindString(job)) == 0 {
		return res
	}
	pattern := `level=fatal msg=.*`
	r := regexp.MustCompile(pattern)
	matchedStr := r.FindString(lines[0])
	if len(matchedStr) == 0 {
		return res
	}
	res = append(res, matchedStr)
	return res
}

type tikvUtParser struct {
}

func (t *tikvUtParser) parse(job string, lines []string) []string {
	var res []string
	if job != "tikv_ghpr_test" {
		return res
	}
	startMatchedStr := regexp.MustCompile(`^\[.+\]\s+failures:$`).FindString(lines[0])
	if len(startMatchedStr) == 0 {
		return res
	}
	if strings.Contains(lines[0], "there is a core dumped, which should not happen") {
		res = append(res, "core dumped")
		return res
	}
	for i, _ := range lines {
		caseMatchedStr := regexp.MustCompile(`^\[.+\]\s+([A-Za-z0-9:_]+)$`).FindString(lines[0])
		if len(caseMatchedStr) != 0 {
			failDetail := strings.TrimSpace(strings.Split(caseMatchedStr, "]")[1])
			res = append(res, failDetail)
		}
		endMatchedStr := regexp.MustCompile(`\[.+\] test result: (\S+)\. (\d+) passed; (\d+) failed; .*`).FindString(lines[i])
		if len(endMatchedStr) != 0 {
			break
		}
	}
	return res
}

type envParser struct {
	rules map[string]string
}

func (t *envParser) parse(job string, lines []string) []string {
	var res []string
	for rule, pattern := range t.rules {
		matched, _ := regexp.MatchString(pattern, lines[0])
		if matched {
			res = append(res, rule)
		}
	}
	return res
}

func UpdateRules(rulePath string) error {
	// assumed one level json dictionary
	file, err := os.Open(rulePath)
	if err != nil {
		return err // file not exist
	}
	defer file.Close()

	content, err := ioutil.ReadAll(file)
	if err != nil {
		return err // io error
	}

	newEnvRules := map[string]string{}
	err = json.Unmarshal(content, &newEnvRules)
	if err != nil {
		return err // invalid json format
	}
	envRules = newEnvRules

	return nil
}

func UpdateRulesPeriodic(rulePath string, period time.Duration) {
	for {
		err := UpdateRules(rulePath)
		if err != nil {
			log.S().Errorf("Rules update failed: [error]", err)
		}
		time.Sleep(period)
	}
}
