package parser

import (
	"github.com/stretchr/testify/assert"
	"strings"
	"testing"
)

func TestTidbUtParser_Parse(t *testing.T) {
	testData := []struct {
		text string
		res  string
	}{
		{`[2020-12-03T11:18:48.011Z]     leaktest.go:177: Test TestT check-count 50 appears to have leaked: github.com/pingcap/tidb/ddl.(*worker).start(0xc07c20eba0, 0xc014cc80b0)
[2020-12-03T11:18:48.011Z]         	/home/jenkins/agent/workspace/tidb_ghpr_unit_test/go/src/github.com/pingcap/tidb/ddl/ddl_worker.go:147 +0x33c
[2020-12-03T11:18:48.011Z]         created by github.com/pingcap/tidb/ddl.(*ddl).Start
[2020-12-03T11:18:48.011Z]         	/home/jenkins/agent/workspace/tidb_ghpr_unit_test/go/src/github.com/pingcap/tidb/ddl/ddl.go:349 +0x6bb
[2020-12-03T11:18:48.011Z] FAIL
[2020-12-03T11:18:48.011Z] FAIL	github.com/pingcap/tidb/executor	141.773s`,
			"leaktest.go:Test TestT check-count 50 appears to have leaked: github.com/pingcap/tidb/ddl.(*worker).start"},
		{`[2020-12-04T07:55:59.484Z] FAIL: builtin_time_vec_test.go:606: testEvaluatorSuite.TestVecMonth
[2020-12-04T07:55:59.484Z] 
[2020-12-04T07:55:59.484Z] builtin_time_vec_test.go:619:
[2020-12-04T07:55:59.484Z]     c.Assert(len(ctx.GetSessionVars().StmtCtx.GetWarnings()), Equals, 2)
[2020-12-04T07:55:59.484Z] ... obtained int = 0
[2020-12-04T07:55:59.484Z] ... expected int = 2
[2020-12-04T07:55:59.484Z] `,
			"builtin_time_vec_test.go:testEvaluatorSuite.TestVecMonth"},
		{`[2020-12-03T13:14:28.737Z] panic: test timed out after 9m0s
[2020-12-03T13:14:28.737Z] 
[2020-12-03T13:14:28.737Z] goroutine 145266 [running]:
[2020-12-03T13:14:28.737Z] testing.(*M).startAlarm.func1()
[2020-12-03T13:14:28.737Z] 	/usr/local/go/src/testing/testing.go:1377 +0xdf
[2020-12-03T13:14:28.737Z] created by time.goFunc
[2020-12-03T13:14:28.737Z] 	/usr/local/go/src/time/sleep.go:168 +0x44`,
			"panic: test timed out"},
		{`[2020-12-03T11:46:59.073Z] WARNING: DATA RACE
[2020-12-03T11:46:59.073Z] Read at 0x00c05fdf93ad by goroutine 283:
[2020-12-03T11:46:59.073Z]   github.com/pingcap/tidb/planner/core.getPossibleAccessPaths()
[2020-12-03T11:46:59.073Z]       /home/jenkins/agent/workspace/tidb_ghpr_unit_test/go/src/github.com/pingcap/tidb/planner/core/planbuilder.go:862 +0x2a0
[2020-12-03T11:46:59.073Z]   github.com/pingcap/tidb/planner/core.(*PlanBuilder).buildDataSource()
[2020-12-03T11:46:59.073Z]       /home/jenkins/agent/workspace/tidb_ghpr_unit_test/go/src/github.com/pingcap/tidb/planner/core/logical_plan_builder.go:3106 +0xcd0`,
			"DATA RACE:github.com/pingcap/tidb/planner/core.getPossibleAccessPaths()"},
		{`[2020-12-03T11:47:46.217Z] --- FAIL: TestT (239.08s)
[2020-12-03T11:47:46.217Z]     testing.go:853: race detected during execution of test`,
			""},
	}
	p := &tidbUtParser{map[string]bool{"tidb_ghpr_unit_test": true, "tidb_ghpr_check": true, "tidb_ghpr_check_2": true}}
	for _, item := range testData {
		res := p.parse("tidb_ghpr_unit_test", strings.Split(item.text, "\n"))
		if len(item.res) == 0 {
			var a []string
			assert.Equal(t, res, a)
		} else {
			assert.Equal(t, res, []string{item.res})
		}
	}
}

func TestTikvUtParser_Parse(t *testing.T) {
	testData := []struct {
		text string
		res  string
	}{
		{`[2020-12-03T17:29:15.428Z] failures:
[2020-12-03T17:29:15.428Z] 
[2020-12-03T17:29:15.428Z] failures:
[2020-12-03T17:29:15.428Z]     server::kv_service::test_split_region
[2020-12-03T17:29:15.428Z] 
[2020-12-03T17:29:15.428Z] test result: FAILED. 18 passed; 1 failed; 0 ignored; 0 measured; 354 filtered out`,
			"server::kv_service::test_split_region"},
		{`[2020-12-03T17:29:15.428Z] failures:
[2020-12-03T17:29:15.428Z]     server::kv_service::test_split_region
[2020-12-03T17:29:15.428Z] 
[2020-12-03T17:29:15.428Z] test result: FAILED. 18 passed; 1 failed; 0 ignored; 0 measured; 354 filtered out`,
			"server::kv_service::test_split_region"},
	}
	p := &tikvUtParser{}
	for _, item := range testData {
		res := p.parse("tikv_ghpr_test", strings.Split(item.text, "\n"))
		if len(item.res) == 0 {
			var a []string
			assert.Equal(t, res, a)
		} else {
			assert.Equal(t, res, []string{item.res})
		}
	}
}

func TestSimpleParser_Check_Parse(t *testing.T) {
	testData := []struct {
		text string
		res  string
		job  string
	}{
		{`[2020-12-07T11:17:06.744Z] make: *** [fmt] Error 1
script returned exit code 2`,
			"check error",
			"tidb_ghpr_check",
		},
		{`[2020-12-03T21:34:10.051Z] make: *** [errcheck] Error 1
script returned exit code 2`,
			"check error",
			"tidb_ghpr_check",
		},
	}

	for _, item := range testData {
		res := parse(item.job, strings.Split(item.text, "\n"), checkParsers, map[string]bool{})
		if len(item.res) == 0 {
			var a []string
			assert.Equal(t, res, a)
		} else {
			assert.Equal(t, res, []string{item.res})
		}
	}
}

func TestSimpleParser_Compile_Parse(t *testing.T) {
	testData := []struct {
		text string
		res  string
		job  string
	}{
		{`[2020-12-07T11:23:08.669Z] 2020/12/07 19:23:08 compile plugin source code failure, exit status 1
script returned exit code 1`,
			"plugin error",
			"",
		},
		{`[2020-12-07T10:48:02.592Z] FAIL	github.com/pingcap/tidb/session [build failed]`,
			"build error",
			"tidb_ghpr_check_2",
		},
		{
			`[2020-12-07T03:58:53.038Z] replace github.com/pingcap/parser v0.0.0-20201201081851-e13818a9916a => github.com/lance6716/parser v0.0.0-20201207021157-8da8773e26fa
script returned exit code 1`,
			"replace parser error",
			"tidb_ghpr_check_2",
		},
	}

	for _, item := range testData {
		res := parse(item.job, strings.Split(item.text, "\n"), compileParsers, map[string]bool{})
		if len(item.res) == 0 {
			var a []string
			assert.Equal(t, res, a)
		} else {
			assert.Equal(t, res, []string{item.res})
		}
	}
}
