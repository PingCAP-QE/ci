package parser

import (
	"database/sql"
	"encoding/json"
	"github.com/bndr/gojenkins"
	"github.com/pingcap/ci/sync_ci/pkg/model"
)


func ParseCIJob(jenkins *gojenkins.Jenkins, job string, ID int64) (*model.CiData, error) {
	build, err := jenkins.GetBuild(job, ID)
	if err != nil {
		return nil, err
	}
	parameters := build.GetParameters()
	var ciData model.CiData
	ciData.Job = job
	ciData.JobID = uint32(ID)
	ciData.Status = build.GetResult()
	ciData.Duration = sql.NullInt64{
		Int64: build.GetDuration(),
		Valid: true,
	}
	ciData.Time = build.GetTimestamp()
	desc := map[string]interface{}{}
	for _, param := range parameters {
		switch param.Name {
		case "ghprbActualCommit":
			ciData.Commit = sql.NullString{String: param.Value, Valid: param.Value != ""}
		case "ghprbTargetBranch", "release_test__release_branch":
			ciData.Branch = sql.NullString{String: param.Value, Valid: param.Value != ""}
		case "ghprbCommentBody":
			ciData.Comment = sql.NullString{String: param.Value, Valid: param.Value != ""}
		case "ghprbGhRepository":
			ciData.Repo = sql.NullString{String: param.Value, Valid: param.Value != ""}
		default:
			desc[param.Name] = param.Value
		}
	}
	descByt, err := json.Marshal(desc)
	if err != nil {
		return nil, err
	}
	ciData.Description = sql.NullString{
		String: string(descByt),
		Valid:  true,
	}

	return &ciData, nil
}

func GetJobStatus(jenkins *gojenkins.Jenkins, job string, ID int64) (string, error) {
	build, err := jenkins.GetBuild(job, ID)
	if err != nil {
		return "", err
	}
	return build.GetResult(), nil
}
