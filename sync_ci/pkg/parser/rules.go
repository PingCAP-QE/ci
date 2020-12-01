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
}

var compileParsers = []parser{
	&simpleTidbCompileParser{rules: map[string]string{
		"rewrite error": "Rewrite error",
	}},
}

type parser interface {
	parse(job string, lines []string) []string
}

type simpleTidbCompileParser struct {
	//name->pattern
	rules map[string]string
}

func (t *simpleTidbCompileParser) parse(job string, lines []string) []string {
	var res []string
	for rule, pattern := range t.rules {
		matched, _ := regexp.MatchString(pattern, lines[0])
		if matched {
			res = append(res, rule)
		}
	}
	return res
}

type tidbUtParser struct {
	jobs map[string]bool
}

func (t *tidbUtParser) parse(job string, lines []string) []string {
	var res []string
	pattern := `FAIL:|panic: runtime error:|WARNING: DATA RACE|leaktest.go.* Test .* check-count .* appears to have leaked: .*`
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
	if strings.ToLower(matchedStr) == "panic: runtime error:" {
		failLine := strings.TrimSpace(lines[0])
		failDetail := strings.TrimSpace(strings.Split(failLine, "]")[1])
		res = append(res, failDetail)
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
