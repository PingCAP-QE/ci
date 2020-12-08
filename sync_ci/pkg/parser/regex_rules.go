package parser

import (
	"encoding/json"
	"errors"
	"github.com/pingcap/log"
	"io/ioutil"
	"regexp"
	"sync/atomic"
	"time"
	"unsafe"
)

var (
	regexRules unsafe.Pointer
	rulePath = "regex_rules.json"

	timeRegexp = regexp.MustCompile(`\[20[0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9][0-9][0-9]Z\]`)
)

type RegexRule struct {
	Jobs []string `json:"jobs"`
	Rule string   `json:"regex"`
	Key  string   `json:"key"`
	Lines int     `json:"lines";default:"1"`

	r *regexp.Regexp `json:"-"`
	m map[string]int `json:"-"`
}

func (r *RegexRule) Prepare() {
	if r.Rule == "" {
		panic(errors.New("invalid regexp rule"))
	}
	if r.Key != "env" && r.Key != "case" && r.Key != "compile" && r.Key != "check" {
		panic(errors.New("invalid key"))
	}

	r.m = make(map[string]int)
	for i := 0; i < len(r.Jobs); i++ {
		r.m[r.Jobs[i]] = 1
	}

	r.r = regexp.MustCompile(r.Rule)
}

func (r *RegexRule) RemoveTime(log string) string {
	return timeRegexp.ReplaceAllString(log, "")
}

type KeyValue struct {
	Key   string
	Value string
}

func (r *RegexRule) Suitable(job string) bool {
	if len(r.m) == 0 {
		return true
	} else {
		_, ok := r.m[job]
		return ok
	}
}

func StartUpdateRegexRules() {
	updateRegexRules(rulePath)

	go func() {
		t := time.NewTicker(time.Duration(time.Second * 60))
		defer t.Stop()

		for {
			<- t.C
			updateRegexRules(rulePath)
		}
	}()
}

func updateRegexRules(path string) {
	defer func() {
		if err := recover(); err != nil {
			log.S().Errorf("Update rules failed. [error] %v", err)
		}
	} ()

	file, err := ioutil.ReadFile(path)
	if err != nil {
		panic(err)
	}

	rules := &[]RegexRule{}
	if err := json.Unmarshal([]byte(file), rules); err != nil {
		panic(err)
	}

	for i := range *rules {
		(*rules)[i].Prepare()
	}

	atomic.StorePointer(&regexRules, unsafe.Pointer(rules))
	log.S().Infof("Regexp rules updated. %+v", rules)
}

func getSuitableRules(job string) *[]RegexRule {
	rulesNow := (*[]RegexRule)(atomic.LoadPointer(&regexRules))
	if rulesNow == nil {
		return nil
	}

	suitableRules := make([]RegexRule, 0)
	for _, r := range *rulesNow {
		if r.Suitable(job) {
			suitableRules = append(suitableRules, r)
		}
	}

	return &suitableRules
}

func ApplyRegexRules(job string, log string) *[]KeyValue {
	rulesNow := getSuitableRules(job)
	if rulesNow == nil {
		return nil
	}

	ret := make([]KeyValue, 0)
	for _, rule := range *rulesNow {
		matches := rule.r.FindAllString(log, 5)
		if matches != nil {
			for _, match := range matches {
				ret = append(ret, KeyValue{rule.Key, match})
			}
		}
	}

	return &ret
}

func ApplyRegexpRulesToLines(job string, lines[]string) *[]KeyValue {
	rulesNow := getSuitableRules(job)
	if rulesNow == nil {
		return nil
	}

	ret := make([]KeyValue, 0)
	for i, _ := range lines {
		for _, rule := range *rulesNow {
			line := lines[i]
			if rule.Lines > 1 {
				// support concat multiple lines and apply regexp.
				for j := 1; j < rule.Lines && i+j < len(lines); j++ {
					line += lines[i + j]
				}
			}

			if match := rule.r.FindString(line); match != "" {
				log.S().Infof("Regex match: %s from %s, [rule]: %s", match, line, rule.Rule)
				ret = append(ret, KeyValue{rule.Key, rule.RemoveTime(match)})
			}
		}
	}

	return &ret
}
