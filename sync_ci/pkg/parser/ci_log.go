package parser

import (
	"bufio"
	"fmt"
	"os"
)

const PreReadLines = 10

const logPathFormat = "/mnt/disks/87fd0455-804f-406e-a3ea-129debf3479b/jobs/%s/builds/%d/log"

//res_analysis -- 错误分析结果 （json 形式），如
//{“env”:[“socket timeout”,”kill process”],”case”:[“executor_test.go:testCoprCache.TestIntegrationCopCache”]}
//{“case”:[“executor_test.go:testCoprCache.TestIntegrationCopCache”]}
//{“unknown”:[]} 不能归类的都划分为 unknown

func ParseCILog(job string, ID int64) (map[string][]string, error) {
	logPath := fmt.Sprintf(logPathFormat, job, ID)
	fp, err := os.Open(logPath)
	if err != nil {
		return nil, err
	}
	buffer := bufio.NewReader(fp)
	//some parse rule need pre read lines
	lines, err := readLines(buffer, PreReadLines)
	if err != nil {
		return nil, err
	}
	res := map[string][]string{
		"env":     []string{},
		"case":    []string{},
		"unknown": []string{},
	}
	for err == nil {
		//parse env failed job
		for _, p := range envParsers {
			res["env"] = append(res["env"], p.Parse(job, lines)...)
		}

		//parse case failed job
		for _, p := range caseParser {
			res["case"] = append(res["case"], p.Parse(job, lines)...)
		}
		line, err := readLines(buffer, 1)
		if err != nil {
			return nil, err
		}
		lines = append(lines, line[0])
		lines = lines[1:]
	}
	return res, nil
}

func readLines(buffer *bufio.Reader, n int) (lines []string, err error) {
	for i := 0; i < n; i++ {
		line, err := buffer.ReadString('\n')
		if err != nil {
			return nil, nil
		}
		lines = append(lines, line)
	}
	return lines, nil
}
