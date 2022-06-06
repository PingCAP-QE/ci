import logging
from typing import Dict, Iterable

from .util import shell_cmd
from .matcher import Matcher
from .types import Components

tiup_cmd = "$HOME/.tiup/bin/tiup"  # easier than setup and unset enviorment variable

# component name in hashfile may differ from component in tiup mirror
COMP_MAP = {  # TODO: map component names tie to time is annoying, any better solution?
    Components.pd: ["pd"],
    Components.tikv: ["tikv"],
    Components.tidb: ["tidb"],
    Components.tiflash: ["tiflash"],
    Components.br: ["br"],
    Components.dumpling: ["dumpling"],
    Components.binlog: ["pump", "drainer"],
    Components.ticdc: ["cdc"],
    Components.lightning: ["tidb-lightning"],
    Components.dm: ["dm-master","dm-worker"]
}


def install_dependencies(version: str, components: Iterable[str]):
    install_tiup = "curl --proto '=https' --tlsv1.2 -sSf https://tiup-mirrors.pingcap.com/install.sh | sh"
    shell_cmd(install_tiup)

    for comp in components:
        if comp not in COMP_MAP.keys():
            logging.warn(f"[{comp}] not supported")
            continue

        for component in COMP_MAP[comp]:
            shell_cmd(f"{tiup_cmd} uninstall {component}:{version} || true")  # cleanup first
            shell_cmd(f"{tiup_cmd} install {component}:{version}")


def validates(version: str, hashes: Dict[str, str], edition="community") -> int:
    err_count = 0
    for comp in hashes.keys():
        if comp not in COMP_MAP.keys():
            logging.warn(f"[{comp}] not supported")
            continue

        env = ""
        if comp == Components.tiflash:
            env = f"LD_LIBRARY_PATH=$HOME/.tiup/components/tiflash/{version}/tiflash"
        elif comp == Components.tikv:
            env = "LD_LIBRARY_PATH=/usr/lib:/lib"

        for component in COMP_MAP[comp]:  # it's confusing comp and component here
            comp_cmd = f"{env} {tiup_cmd} {component}:{version}"
            cmd = f"{comp_cmd} -V || {comp_cmd} --version || {comp_cmd} version"
            try:
                version_string = shell_cmd(cmd)
                logging.debug(version_string)
                matcher = Matcher(comp, version_string, version, hashes[comp], edition)
                mismatches = matcher.match()
                if len(mismatches) > 0:
                    err_count += len(mismatches)
                    msg = "\n\t\t".join(mismatches)

                    logging.error(f"{component}:\n\t\t{msg}\n")

            except Exception as e:
                err_count += 1
                logging.error(f"On command: {cmd}\n    Exception: {e}")

    return err_count
