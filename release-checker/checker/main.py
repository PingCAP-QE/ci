#!/usr/bin/env python3

from typing import Tuple
import click

from pkg import util
from pkg import tiup as TiUp
from pkg import image as Image
from pkg import pingcap as Pingcap
from pkg.types import Components


@ click.group()
def cli():
    """
    """
    pass

# NOTE: core functions in tiup.py, pingcap.py and image.py can be abstracted
# there are duplicated code in function validates(), validates function should be a validator


# 0. prepare enviorment(tiup, docker, etc.)
# 1. parse argument
# 2. downloader
# 3. validator

component_list = (
    Components.tidb,
    Components.tikv,
    Components.pd,
    Components.tiflash,
    Components.br,
    Components.binlog,
    Components.lightning,
    Components.importer,
    Components.ticdc,
    Components.dumpling,
    Components.dm
)


@cli.command()
@click.argument("hashfile", type=str)
@click.argument("version", type=str)
@click.argument("edition", type=click.Choice(("enterprise", "community")))
@click.option("--registry", type=str, help="registry to pull image from")
@ click.option("-c", "--component", type=click.Choice(component_list), multiple=True, help="components to check with")
def image(hashfile, version, edition, registry, component):
    with open(hashfile) as f:
        hashes = util.get_hashes_from_file(f)

    # only validates components speicified by CLI argument
    if len(component) > 0:
        hashes = dict((comp, hashsum) for comp, hashsum in hashes.items() if comp in component)
    Image.pull_images(registry, version, edition, hashes.keys())
    err_count = Image.validates(registry, version, hashes, edition)

    exit(err_count)


@ cli.command()
@ click.argument("hashfile", type=str)
@ click.argument("version", type=str)
@ click.option("-c", "--component", type=click.Choice(component_list), multiple=True, help="components to check with")
def tiup(hashfile, version, component: Tuple[str]):
    with open(hashfile) as f:
        hashes = util.get_hashes_from_file(f)

    if len(component) > 0:
        hashes = dict((comp, hashsum) for comp, hashsum in hashes.items() if comp in component)
    TiUp.install_dependencies(version, hashes.keys())
    err_count = TiUp.validates(version, hashes)

    exit(err_count)  # it's not conventional to use errcount as exit code. but %$#


@ cli.command()
@ click.argument("hashfile", type=str)
@ click.argument("version", type=str)
@ click.argument("edition", type=click.Choice(("enterprise", "community")))
@ click.option("--arch", required=True, type=click.Choice(("linux-amd64", "linux-arm64")))
def pingcap(hashfile, version, edition, arch):
    with open(hashfile) as f:
        hashes = util.get_hashes_from_file(f)

    Pingcap.download_tarball(arch, edition, version)
    err_count = Pingcap.validate_tarball_size(arch, edition, version)
    err_count += Pingcap.validates(version, hashes, arch, edition)

    exit(err_count)


if __name__ == "__main__":
    cli()
