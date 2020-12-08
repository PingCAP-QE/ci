package server

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"github.com/bndr/gojenkins"
	"github.com/gin-contrib/zap"
	"github.com/gin-gonic/gin"
	"github.com/pingcap/ci/sync_ci/pkg/model"
	"github.com/pingcap/ci/sync_ci/pkg/parser"
	"github.com/pingcap/log"
	"gorm.io/driver/mysql"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
	"k8s.io/apimachinery/pkg/util/wait"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"
)

type Server struct {
	cfg *model.Config
}

func NewServer(cfg *model.Config) *Server {
	return &Server{cfg: cfg}
}

func (s *Server) Run() {
	if err := model.InitLog(s.cfg.LogPath); err != nil {
		log.S().Fatalf("init log error , [error]", err)
	}
	ruleFilePath := s.cfg.RulePath
	if err := parser.UpdateRules(ruleFilePath); err != nil { // init log fail
		log.S().Fatalf("init rule file error, [error]", err)
	}

	httpServer := s.setupHttpServer()
	go httpServer.ListenAndServe()
	go parser.UpdateRulesPeriodic(ruleFilePath, 10*time.Second)
	go parser.StartUpdateRegexRules()

	ch := make(chan os.Signal)
	defer close(ch)
	signal.Notify(ch, syscall.SIGINT, syscall.SIGTERM)
	<-ch

	if err := httpServer.Shutdown(context.Background()); err != nil {
		log.S().Errorw("http server shutdown", "err", err)
	}
}

func (s *Server) setupDB() (*gorm.DB, error) {
	db, err := gorm.Open(mysql.Open(s.cfg.Dsn), &gorm.Config{Logger: logger.Default.LogMode(logger.Info)})
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
	res := db.Exec(model.TableCreateSql)
	return db, res.Error
}

func (s *Server) setupHttpServer() (httpServer *http.Server) {
	gin.SetMode(gin.ReleaseMode)
	router := gin.Default()
	logger := log.L()
	router.Use(ginzap.Ginzap(logger, time.RFC3339, true))
	router.Use(ginzap.RecoveryWithZap(logger, true))

	db, err := s.setupDB()
	if err != nil {
		panic(fmt.Sprintf("setup db failed: %v", err))
	}
	jenkins, err := gojenkins.CreateJenkins(nil, "https://internal.pingcap.net/idc-jenkins/").Init()
	if err != nil {
		panic(fmt.Sprintf("setup jenkins failed: %v", err))
	}
	syncHandler := &SyncHandler{db, jenkins}
	router.GET("/ping", func(c *gin.Context) {
		c.String(http.StatusOK, "pong")
	})
	apiv1 := router.Group("/api/v1")
	apiv1.POST("/ci/job/sync", syncHandler.syncData)

	addr := fmt.Sprintf("0.0.0.0:%s", s.cfg.Port)
	log.S().Info(fmt.Sprintf("listening on %s", addr))
	httpServer = &http.Server{
		Addr:         addr,
		Handler:      router,
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 10 * time.Second,
	}
	return httpServer
}

type SyncHandler struct {
	db      *gorm.DB
	jenkins *gojenkins.Jenkins
}

func (h *SyncHandler) syncData(c *gin.Context) {
	var req model.SyncReq
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}
	go h.syncDataJob(req.Job, req.ID)
	c.AbortWithStatus(http.StatusOK)
}

func (h *SyncHandler) syncDataJob(job string, ID int64) {
	defer func() {
		if r := recover(); r != nil {
			log.S().Errorf("recovery from panic , [error] %v", r)
		}
	}()
	// wait for the correct job status
	time.Sleep(1 * time.Minute)
	timeout := 10 * time.Minute
	err := wait.PollImmediate(2*time.Second, timeout, func() (bool, error) {
		jobStatus, err := parser.GetJobStatus(h.jenkins, job, ID)
		_, ok := map[string]bool{"FAILURE": true, "SUCCESS": true, "ABORTED": true}[jobStatus]
		if ok {
			return true, err
		}
		return false, err
	})
	if err != nil {
		log.S().Errorf("wait poll job status error, [job] %v,[ID] %v,[error] %v", job, ID, err)
		return
	}
	ciData, err := parser.ParseCIJob(h.jenkins, job, ID)
	if err != nil {
		log.S().Errorf("parse ci job api error , [job] %v,[ID] %v,[error] %v", job, ID, err)
		return
	}
	if ciData.Status != "SUCCESS" {
		analysisRes, err := parser.ParseCILog(job, ID)
		if err != nil {
			log.S().Errorf("parse ci job log error , [job] %v,[ID] %v,[error] %v", job, ID, err)
		}
		analysisResByt, err := json.Marshal(analysisRes)
		if err != nil {
			log.S().Errorf("json marshal error , [job] %v,[ID] %v,[error] %v", job, ID, err)
		}
		ciData.AnalysisRes = sql.NullString{
			String: string(analysisResByt),
			Valid:  err == nil && analysisRes != nil,
		}
	}
	res := h.db.Create(ciData)
	if res.Error != nil {
		log.S().Errorf("database create record error , [job] %v,[ID] %v,[error] %v", job, ID, res.Error)
	}
}
