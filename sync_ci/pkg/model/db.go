package model

import (
	"database/sql"
	"time"
)

const TableCreateSql = `CREATE TABLE if not exists ci_data (
  job varchar(100) NOT NULL,
  job_id int(10) unsigned NOT NULL,
  status enum('SUCCESS','FAILURE','ABORTED') NOT NULL,
  duration int(10) unsigned DEFAULT NULL,
  time datetime DEFAULT NULL,
  commit text,
  branch varchar(100) DEFAULT NULL,
  comment varchar(100) DEFAULT NULL,
  repo varchar(100) DEFAULT NULL,
  analysis_res json DEFAULT NULL,
  description json DEFAULT NULL,
  PRIMARY KEY (job,job_id),
  KEY status (status),
  KEY job (job),
  KEY time (time),
  KEY duration (duration),
  KEY branch (branch),
  KEY repo (repo)
)`

// https://github.com/smallnest/gen generate
type CiData struct {
	Job         string         `gorm:"primary_key;column:job;type:varchar;size:100;"`
	JobID       uint32         `gorm:"primary_key;column:job_id;type:uint;"`
	Status      string         `gorm:"column:status;type:char;size:7;"`
	Duration    sql.NullInt64  `gorm:"column:duration;type:uint;"`
	Time        time.Time      `gorm:"column:time;type:datetime;"`
	Commit      sql.NullString `gorm:"column:commit;type:varchar;size:100;"`
	Branch      sql.NullString `gorm:"column:branch;type:varchar;size:100;"`
	Comment     sql.NullString `gorm:"column:comment;type:text;size:65535;"`
	AnalysisRes sql.NullString `gorm:"column:analysis_res;type:json;"`
	Description sql.NullString `gorm:"column:description;type:json;"`
	Repo        sql.NullString `gorm:"column:repo;type:varchar;size:100;"`
}
