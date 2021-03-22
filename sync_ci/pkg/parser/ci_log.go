package parser

import (
	"bufio"
	"fmt"
	"io"
	"os"
)

const PreReadLines = 20

const logPathFormat = "/mnt/disks/87fd0455-804f-406e-a3ea-129debf3479b/jobs/%s/builds/%d/log"

//res_analysis -- 错误分析结果 （json 形式），如
//{“env”:[“socket timeout”,”kill process”],”case”:[“executor_test.go:testCoprCache.TestIntegrationCopCache”]}
//{“case”:[“executor_test.go:testCoprCache.TestIntegrationCopCache”]}

func ParseCILog(job string, ID int64) (map[string][]string, error) {
	logPath := fmt.Sprintf(logPathFormat, job, ID)
	fp, err := os.Open(logPath)
	if err != nil {
		return nil, err
	}
	defer fp.Close()
	buffer := bufio.NewReader(fp)
	//some parse rule need pre read lines
	lines, err := readLines(buffer, PreReadLines)
	if err != nil {
		return nil, err
	}
	res := map[string][]string{}

	caseFilter := map[string]bool{}
	compileFilter := map[string]bool{}
	checkFilter := map[string]bool{}

	regexResults := make([]KeyValue, 0)

	for {
		//parse case failed job
		res["case"] = append(res["case"], parse(job, lines, caseParsers, caseFilter)...)

		//compile failed job
		res["compile"] = append(res["compile"], parse(job, lines, compileParsers, compileFilter)...)

		//check failed job
		res["check"] = append(res["check"], parse(job, lines, checkParsers, checkFilter)...)

		if result := ApplyRegexpRulesToLines(job, lines); result != nil {
			regexResults = append(regexResults, *result...)
		}

		line, err := readLines(buffer, 1)

		if err != nil && err != io.EOF {
			return nil, err
		}
		if err == io.EOF {
			break
		}
		lines = append(lines, line[0])
		lines = lines[1:]
	}

	// merge into input.
	for _, kv := range regexResults {
		res[kv.Key] = append(res[kv.Key], kv.Value)
	}

	// remove redundant errors.
	for k, v := range res {
		newV := make([]string, 0)
		filter := make(map[string]bool)

		for _, item := range v {
			if _, ok := filter[item]; !ok {
				filter[item] = true
				newV = append(newV, item)
			}
		}

		// use the first failed case only, for other cases may cause by this case failure.
		if k == "case" && len(newV) > 1 {
			newV = newV[0:1]
		}
		res[k] = newV
	}

	refineParseRes(res)
	return res, nil
}

func refineParseRes(res map[string][]string) {
	for _, v := range []string{"env", "case", "compile", "check"} {
		if len(res[v]) == 0 {
			delete(res, v)
		}
	}

	// The input will only fall on one category according to ranking
	ranking := []string{"compile", "check", "case", "env"}
	locatedTopRank := false
	for _, cat := range ranking {
		if _, ok := res[cat]; !locatedTopRank && ok {
			locatedTopRank = true
			continue
		}
		delete(res, cat)
	}
}

func parse(job string, lines []string, ps []parser, filter map[string]bool) (res []string) {
	for _, p := range ps {
		pRes := p.parse(job, lines)
		for _, v := range pRes {
			if _, ok := filter[v]; !ok {
				res = append(res, v)
				filter[v] = true
			}
		}
	}
	return
}

func readLines(buffer *bufio.Reader, n int) (lines []string, err error) {
	for i := 0; i < n; i++ {
		line, err := buffer.ReadString('\n')
		if err != nil {
			return nil, err
		}
		lines = append(lines, line)
	}
	return lines, nil
}
