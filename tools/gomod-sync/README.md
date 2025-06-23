# gomod-sync

A command-line tool to synchronize shared Go module dependency versions between two `go.mod` files.

## Overview

`gomod-sync` updates all dependencies in a target repository's `go.mod` file to match the versions found in a source repository's `go.mod`, but only for dependencies that exist in both files. This is useful for keeping target or extension repositories in sync with the source project's dependency versions.

## Usage

```sh
gomod-sync --source=from/repo/path/go.mod --target=to/repo/path/go.mod
```

- `from/repo/path/go.mod`: Path to the source repository's `go.mod` file.
- `to/repo/path/go.mod`: Path to the target repository's `go.mod` file.

The tool will update the target repo's `go.mod` in-place, setting the version of any shared dependency to match the main repo.

After updating, it will run:

```
go mod tidy -go=<version>
```

in the target repo directory, where `<version>` is the Go version specified in the main repo's `go.mod`.

## Example

```sh
go run ./ --source=from/repo/path/go.mod --target=to/repo/path/go.mod
```

## Features

- Robust parsing and updating using `golang.org/x/mod/modfile`
- Only updates dependencies present in both files (direct dependencies only, not indirect)
- Runs `go mod tidy -go=<version>` in the target repo after updating, using the Go version from the source repo
- Preserves comments and formatting as much as possible

## Requirements

- Go 1.23 or later
- `golang.org/x/mod` module

## Installation

Clone this repository or copy the `main.go` file into your project. Then build or run with Go:

```sh
go install github.com/PingCAP-QE/ci/tools/gomod-sync@main
$(go env GOPATH)/bin/gomod-sync --source=from/repo/path/go.mod --target=to/repo/path/go.mod
```
