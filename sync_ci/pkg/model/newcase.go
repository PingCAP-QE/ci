package model

// NewCase defines a struct describing a new case found in a failed job, and thus it is necessary to comment the pr.
type NewCase struct {
	Repo     string
	PR       string
	CaseInfo string
	JobLink  string
}
