package model

type SyncReq struct {
	Job string `json:"job" binding:"required"`
	ID  string `json:"id" binding:"required"`
}
