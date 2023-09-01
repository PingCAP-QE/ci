package main

import (
	"encoding/csv"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/PuerkitoBio/goquery"
	"github.com/rs/zerolog"
	"github.com/rs/zerolog/log"
	"golang.org/x/mod/modfile"
)

var (
	reImports    = regexp.MustCompile(`Imports:\s*(\d+[,\d]*)`)
	reImportedBy = regexp.MustCompile(`Imported by:\s*(\d+[,\d]*)`)
)

type goMouleInfo struct {
	Name       string      `json:"name"`
	Licenses   string      `json:"licenses"`
	Imports    int         `json:"imports"`
	ImportedBy int         `json:"imported_by"`
	Repository *repository `json:"repository"`
}

type languageItemOfRepository struct {
	Language    string `json:"language,omitempty"`
	Files       int    `json:"files,omitempty"`
	Lines       int    `json:"lines,omitempty"`
	Blanks      int    `json:"blanks,omitempty"`
	Comments    int    `json:"comments,omitempty"`
	LinesOfCode int    `json:"linesOfCode,omitempty"`
}

type repository struct {
	FullName string `json:"full_name"`
	Name     string `json:"name"`
	Owner    struct {
		Type  string `json:"type"`
		Login string `json:"login"`
	} `json:"owner"`
	ForksCount      int       `json:"forks_count"`
	StargazersCount int       `json:"stargazers_count"`
	LinesOfCode     int       `json:"lines_of_code"`
	Github          bool      `json:"github"`
	Personal        bool      `json:"personal"`
	PushedAt        time.Time `json:"pushed_at"`
}

func getBasicModuleInfo(pkgName string) (*goMouleInfo, error) {
	// Construct the URL
	url := "https://pkg.go.dev/" + pkgName

	// Send an HTTP GET request to the URL
	response, err := http.Get(url)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()

	// Check if the request was successful
	if response.StatusCode != 200 {
		return nil, fmt.Errorf("HTTP request failed with status code: %d", response.StatusCode)
	}

	// Parse the HTML document
	document, err := goquery.NewDocumentFromReader(response.Body)
	if err != nil {
		return nil, err
	}

	// Find and extract the source site, license type, imports count and count of imported by.
	repoLink := document.Find("#main-content div.UnitMeta-repo a").Text()
	licenses := document.Find("#main-content span[data-test-id='UnitHeader-licenses'] a").Text()
	importsText := strings.ReplaceAll(document.Find("#main-content span[data-test-id='UnitHeader-imports'] a").Text(), ",", "")
	importedByText := strings.ReplaceAll(document.Find("#main-content span[data-test-id='UnitHeader-importedby'] a").Text(), ",", "")

	var imports, importedBy int
	importsSubM := reImports.FindStringSubmatch(importsText)
	importedBySubM := reImportedBy.FindStringSubmatch(importedByText)
	if len(importsSubM) > 1 {
		imports, _ = strconv.Atoi(reImports.FindStringSubmatch(importsText)[1])
	}
	if len(importedBySubM) > 1 {
		importedBy, _ = strconv.Atoi(reImportedBy.FindStringSubmatch(importedByText)[1])
	}

	return &goMouleInfo{
		Name:       pkgName,
		Licenses:   licenses,
		Imports:    imports,
		ImportedBy: importedBy,
		Repository: &repository{FullName: strings.TrimSpace(repoLink)},
	}, nil
}

func parseGoModules(modFilePath string) ([]string, error) {
	// Read the contents of the go.mod file
	modFileContents, err := os.ReadFile(modFilePath)
	if err != nil {
		return nil, fmt.Errorf("Failed to read go.mod file: %v", err)
	}
	mf, err := modfile.Parse(modFilePath, modFileContents, nil)
	if err != nil {
		return nil, fmt.Errorf("Failed to parse go.mod file: %v", err)
	}

	// Extract direct dependencies from the parsed go.mod file
	directPackages := make([]string, 0)
	for _, require := range mf.Require {
		if require.Indirect {
			continue
		}

		// Extract the module path
		modulePath := require.Mod.Path

		// Append the module path to the list of direct dependencies
		directPackages = append(directPackages, modulePath)
	}

	return directPackages, nil
}

func isPersonalGithubAccount(username, token string) (bool, error) {
	url := fmt.Sprintf("https://api.github.com/users/%s", username)
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return false, err
	}

	if token != "" {
		req.Header.Set("Authorization", "token "+token)
	}

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		return false, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return false, fmt.Errorf("GitHub API request failed with status code %d\n", resp.StatusCode)
	}

	var userData map[string]interface{}
	if err := json.NewDecoder(resp.Body).Decode(&userData); err != nil {
		fmt.Println("Error decoding JSON response:", err)
		return false, err
	}

	userType, _ := userData["type"].(string)
	return userType != "Organization", nil
}

func getRepoInfo(fullRepo string, token string) (*repository, error) {
	parsedURL, err := url.Parse(fmt.Sprintf("https://%s", fullRepo))
	if err != nil {
		return nil, err
	}

	// Owner and personal
	if parsedURL.Host != "github.com" {
		return &repository{FullName: fullRepo}, nil
	}

	// Split the path component of the URL into segments
	pathSegments := strings.Split(strings.Trim(parsedURL.Path, "/"), "/")
	if len(pathSegments) < 2 {
		return nil, errors.New("not found owner and name for repo")
	}

	repoOwner := pathSegments[0]
	repoName := pathSegments[1]
	ret, err := getGithubRepoInfo(repoOwner, repoName, token)
	if err != nil {
		return nil, err
	}
	ret.Github = true

	personal, err := isPersonalGithubAccount(repoOwner, token)
	if err != nil {
		return nil, err
	}
	ret.Personal = personal

	if codeLines, err := getCodeLinesForGithubRepo(repoOwner, repoName); err != nil {
		return nil, err
	} else {
		ret.LinesOfCode = codeLines
	}

	return ret, nil
}

func getPkgInfo(pkgName string, token string) (*goMouleInfo, error) {
	// .Repo
	ret, err := getBasicModuleInfo(pkgName)
	if err != nil {
		return nil, err
	}

	repoInfo, err := getRepoInfo(ret.Repository.FullName, token)
	if err != nil {
		return nil, err
	}
	ret.Repository = repoInfo

	return ret, nil
}

func getGithubRepoInfo(owner, repo, accessToken string) (*repository, error) {
	apiUrl := fmt.Sprintf("https://api.github.com/repos/%s/%s", owner, repo)

	req, err := http.NewRequest("GET", apiUrl, nil)
	if err != nil {
		return nil, fmt.Errorf("Error creating request: %v", err)
	}

	req.Header.Set("Authorization", "token "+accessToken)

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("Error sending request: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("GitHub API request failed with status code %d\n", resp.StatusCode)
	}

	var ret repository
	err = json.NewDecoder(resp.Body).Decode(&ret)
	if err != nil {
		return nil, fmt.Errorf("Error decoding JSON response: %v", err)
	}

	return &ret, nil
}

// getCodeLinesForGithubRepo static go code lines.
func getCodeLinesForGithubRepo(owner, repo string) (int, error) {
	apiUrl := fmt.Sprintf("https://api.codetabs.com/v1/loc/?github=%s/%s", owner, repo)
	resp, err := http.Get(apiUrl)
	if err != nil {
		return 0, fmt.Errorf("Error sending request: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return 0, fmt.Errorf("API request failed with status code %d", resp.StatusCode)
	}

	var ret []languageItemOfRepository
	err = json.NewDecoder(resp.Body).Decode(&ret)
	if err != nil {
		return 0, fmt.Errorf("Error decoding JSON response: %v", err)
	}

	return ret[0].LinesOfCode, nil
}

func writeCSV(data []*goMouleInfo, filePath string) error {
	file, err := os.Create(filePath)
	if err != nil {
		return err
	}
	defer file.Close()

	writer := csv.NewWriter(file)
	defer writer.Flush()

	// Write the header row
	header := []string{"Module", "Licenses", "Imports", "ImportedBy", "Repository", "GitHub", "Personal", "Stars", "Forks", "LinesOfCode", "Pushed At"}
	if err := writer.Write(header); err != nil {
		return err
	}

	// Write each data row
	for _, info := range data {
		row := []string{
			info.Name,
			info.Licenses,
			strconv.Itoa(info.Imports),
			strconv.Itoa(info.ImportedBy),
			info.Repository.FullName,
			fmt.Sprintf("%t", info.Repository.Github),
			fmt.Sprintf("%t", info.Repository.Personal),
			strconv.Itoa(info.Repository.StargazersCount),
			strconv.Itoa(info.Repository.ForksCount),
			strconv.Itoa(info.Repository.LinesOfCode),
			info.Repository.PushedAt.String(),
		}
		if err := writer.Write(row); err != nil {
			return err
		}
	}

	return nil
}

func main() {
	var githubToken string
	var goModFile string
	var saveCsvFile string
	flag.StringVar(&githubToken, "github-token", "", "GitHub personal access token")
	flag.StringVar(&goModFile, "mod-file", "go.mod", "go.mod file path")
	flag.StringVar(&saveCsvFile, "save-csv-file", "report.csv", "output csv file path")

	flag.Parse()

	zerolog.TimeFieldFormat = zerolog.TimeFormatUnix
	l := log.Output(zerolog.NewConsoleWriter())

	modules, err := parseGoModules(goModFile)
	if err != nil {
		l.Fatal().Err(err).Send()
	}

	var errs []error
	// Retrieve the data
	var data []*goMouleInfo
	for _, m := range modules {
		info, err := getPkgInfo(m, githubToken)
		if err != nil {
			errs = append(errs, err)
			l.Error().Err(err).Send()
		} else {
			data = append(data, info)
			l.Info().
				Str("module", info.Name).
				Str("licenses", info.Licenses).
				Int("imported_by", info.ImportedBy).
				Int("imports", info.Imports).
				Str("repo", info.Repository.FullName).
				Bool("github", info.Repository.Github).
				Bool("repo.personal", info.Repository.Personal).
				Int("repo.stars", info.Repository.StargazersCount).
				Int("repo.forks", info.Repository.ForksCount).
				Int("repo.lines_of_code", info.Repository.LinesOfCode).
				Str("repo.pushed_at", info.Repository.PushedAt.String()).
				Send()
		}
	}

	// Write the data to a CSV file
	if err := writeCSV(data, saveCsvFile); err != nil {
		l.Fatal().Err(err).Send()
	}

	if len(errs) > 0 {
		l.Fatal().Errs("total errors", errs)
	}
}
