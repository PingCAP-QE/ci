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
		{`[2020-12-03T03:48:56.377Z] test server::kv_service::test_split_region ... thread 'raftstore-1-0' panicked at 'called Result::unwrap() on an Err value: channel has been closed', components/raftstore/src/coprocessor/region_info_accessor.rs:152:14
[2020-12-03T03:48:56.377Z] stack backtrace:`,
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
		{
			`[2020-12-07T04:51:27.319Z] Please make format and run tests before creating a PR
[2020-12-07T04:51:27.319Z] + exit 1`,
			"check error",
			"tikv_ghpr_test",
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
		{
			`[2020-12-07T11:52:25.547Z] error: could not compile tikv
[2020-12-07T11:52:25.547Z]
[2020-12-07T11:52:25.547Z] To learn more, run the command again with --verbose.
[2020-12-07T11:52:25.547Z] warning: build failed, waiting for other jobs to finish...
[2020-12-07T11:52:28.829Z] error: aborting due to 5 previous errors`,
			"build error",
			"tikv_ghpr_integration_common_test",
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

func TestEnvParser_Parse(t *testing.T) {
	if err := UpdateRules("envrules.json"); err != nil {
		t.Error(err)
	}
	testData := []struct {
		text string
		res  string
	}{
		//{
		//	`[2020-12-06T16:06:47.263Z] curl: (56) Recv failure: Connection reset by peer`,
		//	"Recv failure: Connection reset by peer",
		//},
		{
			`[2020-12-05T11:11:16.528Z] 2020/12/05 19:11:14.046 util.go:48: [error] open db failed dial tcp 127.0.0.1:4001: connect: connection refused, take time 30.032809965s
[2020-12-05T11:11:16.528Z] 2020/12/05 19:11:14.046 main.go:255: [fatal] dial tcp 127.0.0.1:4001: connect: connection refused`,
			`open db failed dial tcp`,
		},
	}
	for _, item := range testData {
		p := envParser{envRules}
		res := p.parse("", strings.Split(item.text, "\n"))
		if len(item.res) == 0 {
			var a []string
			assert.Equal(t, res, a)
		} else {
			assert.Equal(t, res, []string{item.res})
		}
	}
}

func TestIntegrationTestParser(t *testing.T) {
	testData := []struct {
		text string
		res  string
	}{
		{
			`[2020-12-06T14:19:39.699Z] 2020/12/06 22:19:39 2020/12/06 22:19:37 Test fail: Outputs are not matching.
[2020-12-06T14:19:39.699Z] Test case: sql/randgen-topn/8_encrypt.sql
[2020-12-06T14:19:39.699Z] Statement: #908 -  SELECT SHA1( col_float ) AS field1, ENCODE( '2033-10-15 04:49:31.015619', 0 ) AS field2, '18:39:16.010280' DIV '22:36:17.036724' AS field3, SHA2( NULL, col_bit ) AS field4 FROM table10_int_autoinc WHERE SHA1( col_year ) ORDER BY field1, field2, field3, field4 LIMIT 8 /* QNO 910 CON_ID 152 */ ;
[2020-12-06T14:19:39.699Z] NoPushDown Output:`,
			"sql/randgen-topn/8_encrypt.sql",
		},
		{
			`[2020-12-04T17:31:17.697Z] time="2020-12-05T01:31:17+08:00" level=fatal msg="run test [window_functions] err: sql:SELECT k, AVG(DISTINCT j), SUM(k) OVER (ROWS UNBOUNDED PRECEDING) foo FROM t GROUP BY (k);: failed to run query \n\"SELECT k, AVG(DISTINCT j), SUM(k) OVER (ROWS UNBOUNDED PRECEDING) foo FROM t GROUP BY (k);\" \n around line 81, \nwe need(158):\nSELECT k, AVG(DISTINCT j), SUM(k) OVER (ROWS UNBOUNDED PRECEDING) foo FROM t GROUP BY (k);\nk\tAVG(DISTINCT j)\tfoo\n1\t2.3333\t1\n2\t2.3333\t3\n3\t2.3333\t6\n4\t2.3333\t10\n\nbut got(158):\nSELECT k, AVG(DISTINCT j), SUM(k) OVER (ROWS UNBOUNDED PRECEDING) foo FROM t GROUP BY (k);\nk\tAVG(DISTINCT j)\tfoo\n4\t2.3333\t4\n1\t2.3333\t5\n2\t2.3333\t7\n3\t2.3333\t10\n\n"`,
			`level=fatal msg="run test [window_functions] err: sql:SELECT k, AVG(DISTINCT j), SUM(k) OVER (ROWS UNBOUNDED PRECEDING) foo FROM t GROUP BY (k);: failed to run query \n\"SELECT k, AVG(DISTINCT j), SUM(k) OVER (ROWS UNBOUNDED PRECEDING) foo FROM t GROUP BY (k);\" \n around line 81, \nwe need(158):\nSELECT k, AVG(DISTINCT j), SUM(k) OVER (ROWS UNBOUNDED PRECEDING) foo FROM t GROUP BY (k);\nk\tAVG(DISTINCT j)\tfoo\n1\t2.3333\t1\n2\t2.3333\t3\n3\t2.3333\t6\n4\t2.3333\t10\n\nbut got(158):\nSELECT k, AVG(DISTINCT j), SUM(k) OVER (ROWS UNBOUNDED PRECEDING) foo FROM t GROUP BY (k);\nk\tAVG(DISTINCT j)\tfoo\n4\t2.3333\t4\n1\t2.3333\t5\n2\t2.3333\t7\n3\t2.3333\t10\n\n"`,
		},
	}
	for _, item := range testData {
		p := integrationTestParser{}
		res := p.parse("", strings.Split(item.text, "\n"))
		if len(item.res) == 0 {
			var a []string
			assert.Equal(t, res, a)
		} else {
			assert.Equal(t, res, []string{item.res})
		}
	}
}
