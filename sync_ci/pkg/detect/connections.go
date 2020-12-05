package detect

import (
	"github.com/pingcap/ci/sync_ci/pkg/model"
	"gorm.io/driver/mysql"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
	"time"
)

func SetupCIDB(cfg model.Config) (*gorm.DB, error) {
	db, err := gorm.Open(mysql.Open(cfg.Dsn), &gorm.Config{Logger: logger.Default.LogMode(logger.Info)})
	if err != nil {
		return nil, err
	}

	d, err := db.DB()
	if err != nil {
		return nil, err
	}

	d.SetMaxIdleConns(10)
	d.SetMaxOpenConns(100)
	d.SetConnMaxIdleTime(time.Hour)
	return db, nil
}

func SetupGHDB(cfg model.Config) (*gorm.DB, error) {
	db, err := gorm.Open(mysql.Open(cfg.GithubDsn), &gorm.Config{Logger: logger.Default.LogMode(logger.Info)})
	if err != nil {
		return nil, err
	}

	d, err := db.DB()
	if err != nil {
		return nil, err
	}

	d.SetMaxIdleConns(10)
	d.SetMaxOpenConns(100)
	d.SetConnMaxIdleTime(time.Hour)
	return db, nil
}

func SetupCaseIssueDB(cfg model.Config) (*gorm.DB, error) {
	db, err := gorm.Open(mysql.Open(cfg.CaseDsn), &gorm.Config{Logger: logger.Default.LogMode(logger.Info)})
	if err != nil {
		return nil, err
	}

	d, err := db.DB()
	if err != nil {
		return nil, err
	}

	d.SetMaxIdleConns(10)
	d.SetMaxOpenConns(100)
	d.SetConnMaxIdleTime(time.Hour)
	return db, err
}
