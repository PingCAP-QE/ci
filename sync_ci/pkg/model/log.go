package model

import "github.com/pingcap/log"

func InitLog(logPath string) error {
	conf := &log.Config{
		Level: "INFO",
		File:  log.FileLogConfig{Filename: logPath}}
	l, p, err := log.InitLogger(conf)
	if err != nil {
		return err
	}
	log.ReplaceGlobals(l, p)
	return nil
}
