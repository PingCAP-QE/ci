package model

type Config struct {
	Port           string
	Dsn            string
	GithubDsn      string
	LogPath        string
	GithubToken    string
	WecomKey       string
	UpdateInterval int64
}
