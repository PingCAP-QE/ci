package model

type SyncReq struct {
	Job string `json:"job" binding:"required"`
	ID  int64 `json:"id" binding:"required"`
}
