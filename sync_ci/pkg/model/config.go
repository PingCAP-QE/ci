package model

type Config struct {
	Port           string
	Dsn            string
	GithubDsn      string
	CaseDsn        string
	LogPath        string
	RulePath       string
	GithubToken    string
	WecomKey       string
	UpdateInterval int64
}
