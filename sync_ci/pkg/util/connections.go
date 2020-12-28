package util

import (
	"gorm.io/driver/mysql"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
	"time"
)

//dsn doesn't contain parameters
func SetupDB(dsn string) (*gorm.DB, error) {
	db, err := gorm.Open(mysql.Open(dsn+"?loc=Asia%2FShanghai"), &gorm.Config{Logger: logger.Default.LogMode(logger.Info)})
	if err != nil {
		return nil, err
	}

	d, err := db.DB() /**/
	if err != nil {
		return nil, err
	}

	d.SetMaxIdleConns(10)
	d.SetMaxOpenConns(15)  // previous 100. Attempt to reduce connection burden
	d.SetConnMaxIdleTime(time.Hour)
	return db, nil
}
