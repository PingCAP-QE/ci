package parser

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"reflect"
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
	envFilter := map[string]bool{}
	caseFilter := map[string]bool{}

	for {
		//parse env failed job
		res["env"] = append(res["env"], parse(job, lines, envParsers, envFilter)...)

		//parse case failed job
		res["case"] = append(res["case"], parse(job, lines, caseParsers, caseFilter)...)

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
	reflect.ValueOf(res).MapKeys()
	return res, nil
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
