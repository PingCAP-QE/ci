package parser

import (
	"encoding/json"
	"io/ioutil"
	"github.com/pingcap/log"
	"os"
	"regexp"
	"strings"
	"time"
)

var envRules = map[string]string{}

const EnvRuleFilePath = "envrules.json"

var envParsers = []parser{
	&envParser{envRules},
}
var caseParsers = []parser{
	&tidbUtParser{map[string]bool{"tidb_ghpr_unit_test": true, "tidb_ghpr_check": true, "tidb_ghpr_check_2": true}},
}

type parser interface {
	parse(job string, lines []string) []string
}

type tidbUtParser struct {
	jobs map[string]bool
}

func (t *tidbUtParser) parse(job string, lines []string) []string {
	var res []string
	pattern := `FAIL:|PANIC:|WARNING: DATA RACE`
	r := regexp.MustCompile(pattern)
	if _, ok := t.jobs[job]; !ok {
		return res
	}
	if strings.Contains(lines[0], "FAIL: TestT") {
		return res
	}
	matchedStr := r.FindString(lines[0])
	if len(matchedStr) == 0 {
		return res
	}
	if matchedStr == "WARNING: DATA RACE" {
		failLine := strings.TrimSpace(lines[2])
		failDetail := strings.Join([]string{"DATA RACE", regexp.MustCompile("[^\\s]+").FindAllString(failLine, -1)[1]}, ":")
		res = append(res, failDetail)
		return res
	}
	//parse panic or func fail
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

func UpdateRules() error {
	// assumed one level json dictionary
	file, err := os.Open(EnvRuleFilePath)
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

func UpdateRulesPeriodic(period time.Duration) {
	for {
		err := UpdateRules()
		if err != nil {
			log.Print("Rules update error - ", err)
		}
		time.Sleep(period)
	}
}
