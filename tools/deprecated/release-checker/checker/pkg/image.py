import logging
from typing import Iterable, Dict

from .util import shell_cmd
from .matcher import Matcher
from .types import Components


cleanup_cmds = []  # NOTE: maybe used in cleaning up image

COMP_TO_BINARY = {  # likewise, annoying
    Components.pd: ["/pd-server"],
    Components.tikv: ["/tikv-server"],
    Components.tidb: ["/tidb-server"],
    Components.tiflash: ["/tiflash/tiflash"],
    Components.br: ["/br"],
    Components.dumpling: ["/dumpling"],
    Components.binlog: ["/pump", "/drainer"],
    Components.ticdc: ["/cdc"],
    Components.lightning: ["/tidb-lightning"],
    Components.dm: ["/dm-master", "/dm-worker"],
}

BIN_TO_COMP = {
    "/tidb-lightning": Components.lightning,
    "/tikv-importer": Components.importer,
    "/br": Components.br,
}


def image_tag(registry: str, component: str, edition: str, version: str, user="pingcap") -> str:
    if edition == "enterprise":
        component += "-enterprise"

    if registry is None or len(registry) == 0:
        return f"{user}/{component}:{version}"  # default registry is dockerhub
    else:
        return f"{registry}/{user}/{component}:{version}"


def pull_images(registry: str, version: str, edition: str, components: Iterable[str], user="pingcap"):
    for comp in components:
        image = image_tag(registry, comp, edition, version)
        try:
            shell_cmd(f"docker image rm {image}")
        except:
            pass

        cmd = f"docker pull {image}"
        shell_cmd(cmd)
        cleanup_cmds.append(f"docker image rm {registry}/{user}/{comp}:{version}")


def validates(registry: str, version: str, hashes: Dict[str, str], edition="community", user="pingcap") -> int:
    err_count = 0
    if version >= "5.2.0" and version < "6.6.0":
        COMP_TO_BINARY[Components.lightning] = ["/tidb-lightning", "/br"]

    # 1. image name = component + edtion
    # 2. map compnent to binary name for each run command
    # 3. docker run --entrypoint $binary $image -V || ...

    for comp in hashes.keys():
        image = image_tag(registry, comp, edition, version)

        if comp not in COMP_TO_BINARY.keys():
            logging.warn(f"[{comp}] not supported")
            continue

        for binary in COMP_TO_BINARY[comp]:
            docker_cmd = f"docker run --entrypoint {binary} {image}"

            # NOTE: tikv-importer in lightning image will print short version(without hash) with "-V" flag
            # so use "--version" flag first here
            cmd = f"{docker_cmd} --version || {docker_cmd} -V || {docker_cmd} version"
            try:
                version_string = shell_cmd(cmd)
                logging.debug(version_string)

                if binary in BIN_TO_COMP.keys():  # NOTE: tidb-lightning image is different, bad approach now
                    comp = BIN_TO_COMP[binary]
                    if comp not in hashes.keys():
                        continue

                matcher = Matcher(comp, version_string, version, hashes[comp], edition)
                mismatches = matcher.match()
                if len(mismatches) > 0:
                    err_count += len(mismatches)
                    msg = "\n\t\t".join(mismatches)

                    logging.error(f"{comp}:\n\t\t{msg}\n")

            except Exception as e:
                err_count += 1
                logging.error(f"On command: {cmd}\n    Exception: {e}")

    return err_count
