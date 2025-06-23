package main

import (
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"

	"golang.org/x/mod/modfile"
)

func main() {
	var srcModPath string
	var targetModPath string

	flag.StringVar(&srcModPath, "source", "", "Path to source repo go.mod file")
	flag.StringVar(&srcModPath, "s", "", "Path to source repo go.mod file (shorthand)")
	flag.StringVar(&targetModPath, "target", "", "Path to target repo go.mod file")
	flag.StringVar(&targetModPath, "t", "", "Path to target repo go.mod file (shorthand)")
	flag.Usage = func() {
		fmt.Fprintf(flag.CommandLine.Output(),
			"Usage: %s --source <main_repo_go_mod_path> --target <plugin_repo_go_mod_path>\n", os.Args[0])
		flag.PrintDefaults()
	}
	flag.Parse()

	if srcModPath == "" || targetModPath == "" {
		flag.Usage()
		os.Exit(1)
	}

	sourceVersions, err := loadModuleVersions(srcModPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error loading source go.mod: %v\n", err)
		os.Exit(1)
	}

	destModFile, err := loadModFile(targetModPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error loading target go.mod: %v\n", err)
		os.Exit(1)
	}

	updated := false
	for _, req := range destModFile.Require {
		// Only update direct dependencies (not indirect)
		if req.Indirect {
			continue
		}
		if newVer, ok := sourceVersions[req.Mod.Path]; ok && req.Mod.Version != newVer {
			err := destModFile.AddRequire(req.Mod.Path, newVer)
			if err == nil {
				updated = true
				fmt.Printf("Updated %s: %s -> %s\n", req.Mod.Path, req.Mod.Version, newVer)
			}
		}
	}

	if updated {
		newMod, err := destModFile.Format()
		if err != nil {
			fmt.Fprintf(os.Stderr, "Failed to format updated go.mod: %v\n", err)
			os.Exit(1)
		}
		if err := os.WriteFile(targetModPath, newMod, 0644); err != nil {
			fmt.Fprintf(os.Stderr, "Failed to write updated plugin go.mod: %v\n", err)
			os.Exit(1)
		}
		fmt.Println("Plugin go.mod updated with main repo versions.")

		// Run 'go mod tidy -go <version>' in the plugin repo directory
		goVersion, err := loadGoVersion(srcModPath)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Failed to get Go version from main go.mod: %v\n", err)
			os.Exit(1)
		}
		fmt.Printf("Running 'go mod tidy -go=%s'...\n", goVersion)
		if err := runGoModTidy(filepath.Dir(targetModPath), goVersion); err != nil {
			fmt.Fprintf(os.Stderr, "Failed to run 'go mod tidy': %v\n", err)
			os.Exit(1)
		}
		fmt.Println("go mod tidy completed.")
	} else {
		fmt.Println("No shared dependencies needed updating.")
	}
}

// runGoModTidy runs 'go mod tidy -go <version>' in the specified directory using os/exec.
func runGoModTidy(dir string, goVersion string) error {
	cmd := []string{"go", "mod", "tidy", "-go=" + goVersion}
	return runCmd(cmd, dir)
}

// runCmd runs the given command in the specified directory.
func runCmd(cmdArgs []string, dir string) error {
	if len(cmdArgs) == 0 {
		return fmt.Errorf("no command provided")
	}
	cmd := exec.Command(cmdArgs[0], cmdArgs[1:]...)
	cmd.Dir = dir
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

// loadModuleVersions loads the module versions from a go.mod file.
func loadModuleVersions(modPath string) (map[string]string, error) {
	modFile, err := loadModFile(modPath)
	if err != nil {
		return nil, err
	}
	versions := make(map[string]string)
	for _, req := range modFile.Require {
		versions[req.Mod.Path] = req.Mod.Version
	}
	return versions, nil
}

// loadGoVersion parses the go version from a go.mod file.
func loadGoVersion(modPath string) (string, error) {
	modFile, err := loadModFile(modPath)
	if err != nil {
		return "", err
	}
	if modFile.Go != nil && modFile.Go.Version != "" {
		return modFile.Go.Version, nil
	}
	return "", fmt.Errorf("go version not found in %s", modPath)
}

func loadModFile(modPath string) (*modfile.File, error) {
	modBytes, err := os.ReadFile(modPath)
	if err != nil {
		return nil, err
	}
	modFile, err := modfile.Parse(modPath, modBytes, nil)
	return modFile, err
}
