package parser

//res_analysis -- 错误分析结果 （json 形式），如
//{“env”:[“socket timeout”,”kill process”],”case”:[“executor_test.go:testCoprCache.TestIntegrationCopCache”]}
//{“env”:[“unknown”]}  不能归类的都可以划分为 环境问题的 unknown 类型
//{“case”:[“unknown”]}

func ParseCILog(job string, ID string) map[string][]string {

	return nil
}
