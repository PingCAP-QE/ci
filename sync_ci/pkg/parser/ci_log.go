package parser

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"log"
	"os"
	"regexp"
	"strings"
)

type CIMatchRules map[string]map[string]string
type CIMatchSet map[string]map[string]bool
type CICaseSet map[string]int
type CIMatchResult map[string][]string

const logPathFormat = "/mnt/disks/87fd0455-804f-406e-a3ea-129debf3479b/jobs/%s/builds/%d/log"

//const logPathFormat = "%s%d.log"
var ciMatchRules = getRulesFromFile()

const miscGroupName = "env"
const miscProblemName = "unknown"

func getRulesFromFile() CIMatchRules {
	data, err := ioutil.ReadFile("rules.json") // todo: ensure project root
	assert(err, "Cannot load parsing rules from file")
	rules := CIMatchRules{}
	assert(json.Unmarshal(data, &rules), "Check json rules format")
	return rules
}

//res_analysis -- 错误分析结果 （json 形式），如
//{“env”:[“socket timeout”,”kill process”],”case”:[“executor_test.go:testCoprCache.TestIntegrationCopCache”]}
//{“env”:[“unknown”]}  不能归类的都可以划分为 环境问题的 unknown 类型
//{“case”:[“unknown”]}
func ParseCILog(job string, ID int64) (CIMatchResult, error) {
	logPath := fmt.Sprintf(logPathFormat, job, ID)
	fp, err := os.Open(logPath)
	if err != nil {
		return nil, err
	}
	buffer := bufio.NewReader(fp)

	resultSet := CIMatchSet{}
	caseSet := CICaseSet{}
	rulesMatched, caseMatched := false, false

	line, readErr := buffer.ReadString('\n')
	nextLine, readErr := buffer.ReadString('\n')
	secondNextLine, readErr := buffer.ReadString('\n')
	for readErr == nil {
		rulesMatched = rulesMatched || matchRules(ciMatchRules, line, resultSet)
		caseMatched = caseMatched || matchCase(line, secondNextLine, caseSet)
		line, nextLine = nextLine, secondNextLine
		secondNextLine, readErr = buffer.ReadString('\n')
	}
	rulesMatched = rulesMatched || matchRules(ciMatchRules, line, resultSet)
	caseMatched = caseMatched || matchCase(line, "", caseSet)
	rulesMatched = rulesMatched || matchRules(ciMatchRules, nextLine, resultSet)
	caseMatched = caseMatched || matchCase(line, nextLine, caseSet)
	_ = fp.Close()
	matchResult := resultSetToJson(resultSet, caseSet)

	return matchResult, nil
}

func resultSetToJson(resultSet CIMatchSet, caseSet CICaseSet) CIMatchResult {
	// assume all has build failure
	result := CIMatchResult{}
	matchedResult := false
	for groupName, group := range resultSet {
		for problem, exists := range group {
			if exists {
				matchedResult = true
				result[groupName] = append(result[groupName], problem)
			}
		}
	}

	if !matchedResult {
		result[miscGroupName] = make([]string, 1)
		result[miscGroupName][0] = miscProblemName
	}

	if len(caseSet) == 0 {
		result["case"] = make([]string, 1)
		result["case"][0] = "unknown"
	} else {
		cases := make([]string, 0, len(caseSet))
		for funcName := range caseSet {
			cases = append(cases, funcName)
		}
		result["case"] = cases
	}
	return result
}

func matchRules(rules CIMatchRules, line string, resultStruct CIMatchSet) bool {
	// assume unsuccessful
	result := resultStruct
	matchedAny := false
	for groupName, group := range rules {
		for problem, rule := range group {
			matched, _ := regexp.MatchString(rule, line)
			if matched {
				if _, existsGroup := result[groupName]; !existsGroup {
					result[groupName] = map[string]bool{}
				}
				result[groupName][problem] = true
				matchedAny = true
			}
		}
	}
	return matchedAny
}

func matchCase(line string, secondNextLine string, caseSet CICaseSet) bool {
	// used with job "tidb_ghpr_unit_test", "tidb_ghpr_check", "tidb_ghpr_check_2" only
	if strings.Contains(line, "FAIL:") || strings.Contains(line, "WARNING: DATA RACE") {
		if strings.Contains(line, "FAIL: TestT") {
			return false
		}

		if strings.Contains(line, "WARNING: DATA RACE") {
			if secondNextLine != "" {
				failLine := strings.TrimSpace(secondNextLine)
				failCodePosition := strings.Split(
					strings.Split(failLine, " ")[2], ":")[0]
				failFuncName := strings.Split(failLine, " ")[3]
				failFunc := failCodePosition + ":" + failFuncName
				if _, exists := caseSet[failFunc]; !exists {
					caseSet[failFunc] = 0
				}
				caseSet[failFunc]++
			}
		} else {
			failLine := strings.TrimSpace(line)
			failCodePosition := strings.Split(
				strings.Split(failLine, " ")[2], ":")[0]
			failFuncName := strings.Split(failLine, " ")[3]
			failFunc := failCodePosition + ":" + failFuncName
			if _, exists := caseSet[failFunc]; !exists {
				caseSet[failFunc] = 0
			}
			caseSet[failFunc]++
		}
		return true
	}
	return false
}

func assert(err error, msg string) {
	if err != nil {
		log.Fatal(msg + ": " + err.Error())
	}
}
