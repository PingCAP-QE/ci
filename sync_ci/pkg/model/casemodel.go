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
having json_length(` + "`case`" + `)>0 and pr != '0'
order by repo, time desc;
`


const GetRerunCases = `
select
       tmp1.repo,
       tmp3.pr,
       json_extract(analysis_res, '$.case') cases,
       job_id,
       tmp1.job
from
    (select
            json_extract(description, '$.ghprbPullId') as pr,
            job, job_id, time, status, analysis_res, repo,
            json_extract(description, '$.ghprbPullLink') as prLink
        from ci_data
        where time >= DATE_SUB(CURDATE(), interval 8 HOUR)
          and json_extract(description, '$.ghprbPullLink') != ''
          and status != 'ABORTED' order by time )
    tmp1 join
    (
        select repo, pr, job, count(*) as cnt from
            (
                select
                json_extract(description, '$.ghprbPullId') as pr,
                job, time, status, repo,
                analysis_res
                from ci_data
                where time >= DATE_SUB(CURDATE(), interval 8 HOUR)
                   and json_extract(description, '$.ghprbPullLink') != ''
                    and status != 'ABORTED'
                order by time
            ) as tmp2
        group by pr, job
        order by cnt desc
    ) tmp3
    on (tmp1.pr = tmp3.pr and tmp1.job = tmp3.job and tmp1.repo=tmp3.repo)
where tmp3.cnt >= 2  -- rerun
    and tmp1.status = 'FAILURE' -- failed cases
    and json_length(analysis_res, '$.case') > 0
order by analysis_res desc;
`


const GetCICasesToday = `
select
	repo,
	json_extract(description, '$.ghprbPullId') as pr,
	json_extract(analysis_res, '$.case') as ` + "`case`" + `,
	job_id,
	job
from sync_ci_data.ci_data
where date(time)=date(now())
having json_length(` + "`case`" + `)>0 and pr != '0'
order by repo, time desc;
`


const GetCINightlyCase = `
select
	json_extract(analysis_res, '$.case') as ` + "`case`" + `,
	job_id,
	job
from sync_ci_data.ci_data
where (time between ? and ?) and repo is null
having json_length(` + "`case`" + `) > 0
order by job, time desc;
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
where url like ?  -- match number
	and url like ?  -- match repo
	and (closed_at is null
		or 	timediff(now(), closed_at) < ?);
`


type CaseIssue struct {
	IssueNo   int64          `gorm:"primary_key;column:issue_no;type:int;size:11;" json:"IssueNo" binding:"required"`
	Repo      string         `gorm:"primary_key;column:repo;type:varchar;size:100;" json:"Repo" binding:"required"`
	IssueLink sql.NullString `gorm:"column:issue_link;type:varchar;size:100;" json:"IssueLink" binding:"required"`
	Case      sql.NullString `gorm:"column:case;type:varchar;size:100;" json:"Case" binding:"required"`
	JobLink   sql.NullString `gorm:"column:joblink;type:varchar;size:200;"`
}


func (*CaseIssue) TableName() string {
	return "issue_case"
}
