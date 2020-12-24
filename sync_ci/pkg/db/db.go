package db

import (
	"fmt"
	"github.com/pingcap/ci/sync_ci/pkg/model"
	"github.com/pingcap/ci/sync_ci/pkg/util"
	"gorm.io/gorm"
)

type DBDesc struct {
	Dsn     string
	InitSql []string
}

var DBWarehouse map[string]*gorm.DB

const CIDBName = "ci"
const GithubDBName = "github"

func InitDB(c model.Config) {
	dbDescs := map[string]DBDesc{
		CIDBName:     {c.Dsn, []string{model.CiDataTableCreateSql}},
		GithubDBName: {c.GithubDsn, []string{}},
	}
	createDB(dbDescs)
}
func createDB(dbDescs map[string]DBDesc) {
	for k, v := range dbDescs {
		db, err := util.SetupDB(v.Dsn)
		if err != nil {
			panic(fmt.Sprintf("setup db failed: [dsn] %v, [error] %v", v.Dsn, err))
		}
		for _, s := range v.InitSql {
			res := db.Exec(s)
			if res.Error != nil {
				panic(fmt.Sprintf("execute sql failed : [sql] %v, [error] %v", s, err))
			}
		}
		DBWarehouse[k] = db
	}
}
