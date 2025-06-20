# gomod-sync

A command-line tool to synchronize shared Go module dependency versions between two `go.mod` files.

## Overview

`gomod-sync` updates all dependencies in a plugin repository's `go.mod` file to match the versions found in a main repository's `go.mod`, but only for dependencies that exist in both files. This is useful for keeping plugin or extension repositories in sync with the main project's dependency versions.

## Usage

```sh
go run main.go <main_repo_go_mod_path> <plugin_repo_go_mod_path>
```

- `<main_repo_go_mod_path>`: Path to the main repository's `go.mod` file.
- `<plugin_repo_go_mod_path>`: Path to the plugin repository's `go.mod` file.

The tool will update the plugin repo's `go.mod` in-place, setting the version of any shared dependency to match the main repo.

After updating, it will run:

```
go mod tidy -go=<version>
```

in the plugin repo directory, where `<version>` is the Go version specified in the main repo's `go.mod`.

## Example

```sh
go run main.go ../../mainrepo/go.mod ./go.mod
```

## Features

- Robust parsing and updating using `golang.org/x/mod/modfile`
- Only updates dependencies present in both files (direct dependencies only, not indirect)
- Runs `go mod tidy -go=<version>` in the plugin repo after updating, using the Go version from the main repo
- Preserves comments and formatting as much as possible

## Requirements

- Go 1.23 or later
- `golang.org/x/mod` module

## Installation

Clone this repository or copy the `main.go` file into your project. Then build or run with Go:

```sh
go build -o gomod-sync
./gomod-sync <main_repo_go_mod_path> <plugin_repo_go_mod_path>
```

## License

MIT License