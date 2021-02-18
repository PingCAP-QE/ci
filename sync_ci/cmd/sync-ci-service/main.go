package main

import (
	"context"
	"flag"
	"os"
	"os/signal"

	"github.com/google/subcommands"
	"github.com/pingcap/ci/sync_ci/pkg/command"
	"github.com/pingcap/log"
)

func main() {
	defer log.Sync() //nolint:errcheck
	subcommands.Register(subcommands.HelpCommand(), "")
	subcommands.Register(subcommands.FlagsCommand(), "")
	subcommands.Register(subcommands.CommandsCommand(), "")
	subcommands.Register(&command.SyncCICommand{}, "")

	flag.Parse()

	ctx := context.Background()
	ctx, cancel := context.WithCancel(ctx)
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, os.Interrupt)

	go func() {
		<-ch
		cancel()
	}()

	os.Exit(int(subcommands.Execute(ctx)))
}
