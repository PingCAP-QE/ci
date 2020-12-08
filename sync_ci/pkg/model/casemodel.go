package model

import (
	"database/sql"
)

const IssueCaseCreationTable = `create table if not exists issue_case
(
	issue_no int not null
		primary key,
	repo varchar(100) not null,
	issue_link varchar(100) null,
	` + "`case`" + ` varchar(100) null
);
`

// get CI case within （interval, CI case)
const GetCICaseSql = `
select
	repo,
	json_extract(description, '$.ghprbPullId') as pr,
	json_extract(analysis_res, '$.case') as ` + "`case`" + `,
	job_id,
	job
from sync_ci_data.ci_data
where time between ? and ? and repo is not null
having json_length(` + "`case`" + `)>0 and pr != '0';
`

const GetCINightlyCase = `
select
	json_extract(analysis_res, '$.case') as ` + "`case`" + `,
	job_id,
	job
from sync_ci_data.ci_data
where (time between ? and ?) and repo is null
having json_length(` + "`case`" + `) > 0;
`

const IfValidIssuesExistSql = `
select issue_no
from issue_case
where ` + "`case`" + ` = ? and repo = ?
order by issue_no desc
limit 1;
`

const CheckClosedTimeSql = `
select url from issue
where url like '%?'  -- match number
	and url like '%/?/%'  -- match repo
	and (closed_time is null
		or 	timediff(now(), closed_time) < ?);
`

type CaseIssue struct {
	IssueNo   int64          `gorm:"primary_key;column:issue_no;type:int;size:11;" json:"IssueNo" binding:"required"`
	Repo      string         `gorm:"primary_key;column:repo;type:varchar;size:100;" json:"Repo" binding:"required"`
	IssueLink sql.NullString `gorm:"column:issue_link;type:varchar;size:100;" json:"IssueLink" binding:"required"`
	Case      sql.NullString `gorm:"column:case;type:varchar;size:100;" json:"Case" binding:"required"`
	JobLink   sql.NullString `gorm:"column:joblink;type:varchar;size:100;"`
}
