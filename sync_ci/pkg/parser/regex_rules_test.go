package parser

import (
	"bufio"
	"os"
	"strings"
	"testing"
)

func TestRegex_Load(t *testing.T) {
	if updateRegexpRules("./pkg/parser/regex_rules.json") != true {
		t.Error("load rule failed")
	}
}

func TestRegex_TestRules(t *testing.T) {
	updateRegexpRules("./pkg/parser/regex_rules.json")
	lines := FilesToLines("./pkg/parser/rules_test/integration_fatal_error.log")
	result := ApplyRegexpRulesToLines("tidb_ghpr_check", lines)
	if result == nil || len(*result) != 2 {
		t.Error("test failed")
		return
	}

	for _, r := range *result {
		if r.Key != "case" ||
			strings.Contains(r.Value, `[FATAL] [main.go:694] ["run test"] [test=select] [error="sql:SELECT c2 from t where not (c2 > 2);: run` ) == false {
			t.Error("test failed")
		}
	}
}

func FilesToLines(path string) []string {
	file, err := os.Open(path)
	if err != nil {
		panic(err)
	}
	defer file.Close()

	lines := make([]string, 0)
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		lines = append(lines, scanner.Text())
	}

	return lines
}

func TestRegexp_TiKVCompileError(t *testing.T) {
	updateRegexpRules("./pkg/parser/regex_rules.json")
	lines := FilesToLines("./pkg/parser/rules_test/tikv_compile_error.log")

	info := ApplyRegexpRulesToLines("tikv_ghpr_test", lines)
	for _, o := range *info {
		if o.Key == "compile" && strings.Contains(o.Value, "error: could not compile") {
			return
		}
	}

	t.Error("TiKV compile error missed.")
}

func TestRegexp_TiDBPanic(t *testing.T) {
	updateRegexpRules("./pkg/parser/regex_rules.json")
	lines := FilesToLines("./pkg/parser/rules_test/tidb_panic.log")

	info := ApplyRegexpRulesToLines("tidb_ghpr_check", lines)
	for _, o := range *info {
		if o.Key == "case" && strings.Contains(o.Value, "PANIC: aggregate_test.go:507: testSuiteAgg.TestGroupConcatAggr") {
			return
		}
	}

	t.Error("TiDB panic error missed.")
}

func TestRegexp_ReferenceError(t *testing.T) {
	updateRegexpRules("./pkg/parser/regex_rules.json")
	lines := FilesToLines("./pkg/parser/rules_test/replace_error.log")

	info := ApplyRegexpRulesToLines("tidb_ghpr_check", lines)
	for _, o := range *info {
		if o.Key == "env" && strings.Contains(o.Value, "fatal: reference is not a tree: 0f135cbb543c5a19e0344f07e8ccc65fb33eb245") {
			return
		}
	}

	t.Error("Reference error missed.")
}

func TestRegexp_TiKVTomlError(t *testing.T) {
	updateRegexpRules("./pkg/parser/regex_rules.json")
	lines := FilesToLines("./pkg/parser/rules_test/tikv_toml_error.log")

	info := ApplyRegexpRulesToLines("", lines)
	for _, o := range *info {
		if o.Key == "compile" && strings.Contains(o.Value, "could not parse input as TOML") {
			return
		}
	}

	t.Error("TiKV toml error missed.")
}

func TestRegex_TicsFail(t *testing.T) {
	updateRegexpRules("./pkg/parser/regex_rules.json")
	lines := FilesToLines("./pkg/parser/rules_test/tics_fail.log")

	info := ApplyRegexpRulesToLines("tidb_ghpr_tics_test", lines)
	for _, o := range *info {
		if o.Key == "case" && strings.Contains(o.Value, "Failed in branch TiCS Test") {
			return
		}
	}

	t.Error("TiCS failure missed.")
}

func TestRegex_CoprTest(t *testing.T) {
	updateRegexpRules("./pkg/parser/regex_rules.json")
	lines := FilesToLines("./pkg/parser/rules_test/copr_test_case.log")

	info := ApplyRegexpRulesToLines("", lines)
	for _, o := range *info {
		if o.Key == "case" && strings.Contains(o.Value, "sql/randgen-topn/5_math_2.sql") {
			return
		}
	}

	t.Error("Copr test case missed.")
}

func TestRegex_TiDBRaceBuildFailed(t *testing.T) {
	updateRegexpRules("./pkg/parser/regex_rules.json")
	lines := FilesToLines("./pkg/parser/rules_test/tidb_race_build_failed.log")

	info := ApplyRegexpRulesToLines("", lines)
	for _, o := range *info {
		if o.Key == "compile" && strings.Contains(o.Value, "FAIL	github.com/pingcap/tidb/planner/core [setup failed]") {
			return
		}
	}

	t.Error("tidb race setup failed missed.")
}

func TestRegex_TiDB_IntegrationCommonTest(t *testing.T) {
	updateRegexpRules("./pkg/parser/regex_rules.json")
	lines := FilesToLines("./pkg/parser/rules_test/FAIL_integration_common_test.log")

	info := ApplyRegexpRulesToLines("tidb_ghpr_integration_common_test", lines)
	if len(*info) != 2 {
		t.Error("integration common test failed.")
	}
	for _, o := range *info {
		if o.Key == "case" && strings.Contains(o.Value, "coprocessor_cache_test.go:102: testCoprocessorSuite.TestAdmission") {
			return
		}
	}

	t.Error("integration common test failed.")
}

func TestRegex_TiDB_IntegrationCommonTest2(t *testing.T) {
	updateRegexpRules("./pkg/parser/regex_rules.json")
	lines := FilesToLines("./pkg/parser/rules_test/tidb_integration_fail.log")

	info := ApplyRegexpRulesToLines("", lines)
	if info == nil || len(*info) != 1 {
		t.Error("integration common test2 failed.")
	}
	for _, o := range *info {
		if o.Key == "case" && strings.Contains(o.Value, "lock_test.go:655: testLockSuite.TestBatchResolveTxnFallenBackFromAsyncCommit") {
			return
		}
	}

	t.Error("integration common test2 failed.")
}

func TestRegex_TiDB_PDBuildFailed(t *testing.T) {
	updateRegexpRules("./pkg/parser/regex_rules.json")
	lines := FilesToLines("./pkg/parser/rules_test/pd_build_failed.log")

	info := ApplyRegexpRulesToLines("", lines)
	if len(*info) == 1 {
		if (*info)[0].Key == "compile" && strings.Contains((*info)[0].Value, "make: *** [swagger-spec] Error 1") {
			return
		}
	}

	t.Error("pd build failed rule not work.")
}

func TestRegex_TiDB_MysqlTestError(t *testing.T) {
	updateRegexpRules("./pkg/parser/regex_rules.json")
	lines := FilesToLines("./pkg/parser/rules_test/mysql_test_error.log")

	info := ApplyRegexpRulesToLines("", lines)
	if len(*info) == 1 {
		if (*info)[0].Key == "case" && strings.Contains((*info)[0].Value, "[fatal] run test [window_functions] err: sql:SHOW CREATE VIEW v;: failed to run query") {
			return
		}
	}

	t.Error("pd build failed rule not work.")
}