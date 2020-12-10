package detect

import (
	"gorm.io/driver/mysql"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
	"time"
)

func SetupDB(Dsn string) (*gorm.DB, error) {
	db, err := gorm.Open(mysql.Open(Dsn), &gorm.Config{Logger: logger.Default.LogMode(logger.Info)})
	if err != nil {
		return nil, err
	}

	d, err := db.DB()/**/
	if err != nil {
		return nil, err
	}

	d.SetMaxIdleConns(10)
	d.SetMaxOpenConns(100)
	d.SetConnMaxIdleTime(time.Hour)
	return db, nil
}
