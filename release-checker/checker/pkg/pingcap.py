from typing import Dict
import logging
import os

from .util import shell_cmd
from .types import Components
from .matcher import Matcher

download_url = "https://download.pingcap.org"
tmp_dir = "$HOME/release-checker-tarball"


def need_tiflash(version: str) -> bool:
    return version[1] > '3'  # version 4.0+


def in_commercial_tools_package(component: str) -> bool:
    tools = (Components.br, Components.dumpling, Components.lightning)
    return component in tools


def in_community_tools_package(comp: str) -> bool:
    tools = (Components.importer, Components.lightning)
    return comp in tools


# dir: $tmpdir/tidb-community-server-version-arch/bin/*
COMP_TO_BINARY_COMMERCIRL = {
    Components.pd: ["pd-server"],
    Components.tikv: ["tikv-server"],
    Components.tidb: ["tidb-server"],
    Components.tiflash: ["tiflash"],
    Components.br: ["br"],
    Components.dumpling: ["dumpling"],
    Components.binlog: ["pump", "drainer"],
    Components.lightning: ["tidb-lightning"],
}

COMP_TO_BINARY = {
    Components.ticdc: ["cdc"],
    Components.importer: ["tikv-importer"],
}
COMP_TO_BINARY.update(COMP_TO_BINARY_COMMERCIRL)
COMP_TO_BINARY[Components.tiflash] = ["tiflash/tiflash"]

TARBALL_BASE_SIZE = {  # size should be in range of (size * 0.9, size * 1.1)
    "community-tidb-linux-amd64": 1655818619,
    "community-tools-linux-amd64": 167212747,

    "commercial-tidb-linux-amd64": 462626360,
    "commercial-tools-linux-amd64": 179883298,
    "commercial-tiflash-linux-amd64": 381091552,

    "community-tidb-linux-arm64": 1537669312,
    "community-tools-linux-arm64": 143608538,

    "commercial-tidb-linux-arm64": 434093640,
    "commercial-tools-linux-arm64": 151649843,
    "commercial-tiflash-linux-arm64": 382381304,
}

TARBALL_BASE_SIZE_NEWER_THAN_V5 = {  # size should be in range of (size * 0.9, size * 1.1)
    "community-tidb-linux-amd64": 1655818619,
    "community-tools-linux-amd64": 200220415,

    "commercial-tidb-linux-amd64": 517009154,
    "commercial-tools-linux-amd64": 220147473,
    "commercial-tiflash-linux-amd64": 381091552,

    "community-tidb-linux-arm64": 1537669312,
    "community-tools-linux-arm64": 188041410,

    "commercial-tidb-linux-arm64": 500086011,
    "commercial-tools-linux-arm64": 203198135,
    "commercial-tiflash-linux-arm64": 382381304,
}


def validate_tarball_size(arch: str, edition: str, version: str) -> int:
    def validate_size(tarball: str, base_size: int):
        nerrs = 0
        try:
            size_str = shell_cmd(f"""ls -l {tarball} | awk -F" " '{{print$5}}'""")
            size = int(size_str)
            if size < base_size * 0.9 or \
                    size > base_size * 1.1:

                nerrs += 1
                logging.error(f"[{tarball}] abnormal tarball size, got size: {size} ")

        except Exception as e:
            logging.error(f"validate size of {tarball}, {e}")
            nerrs += 1

        return nerrs

    err_count = 0

    size_table = TARBALL_BASE_SIZE
    if version.startswith("v") and version[1] >= "5":
        size_table=TARBALL_BASE_SIZE_NEWER_THAN_V5

    if edition == "community":
        tidb_name = f"tidb-community-server-{version}-{arch}.tar.gz"
        err_count += validate_size(tidb_name, size_table[f"community-tidb-{arch}"])

        tool_name = f"tidb-community-toolkit-{version}-{arch}.tar.gz"
        err_count += validate_size(tool_name, size_table[f"community-tools-{arch}"])
    else:
        tidb_name = f"tidb-{version}-{arch}.tar.gz"
        tool_name = f"tidb-toolkit-{version}-{arch}.tar.gz"

        err_count += validate_size(tidb_name, size_table[f"commercial-tidb-{arch}"])
        err_count += validate_size(tool_name, size_table[f"commercial-tools-{arch}"])

        if need_tiflash(version):
            tiflash_name = f"tiflash-{version}-{arch}.tar.gz"
            err_count += validate_size(tiflash_name, size_table[f"commercial-tiflash-{arch}"])

    return err_count


def download(url: str):
    try:
        filename = os.path.basename(url)
        shell_cmd(f"rm {filename}")
    except:
        pass

    shell_cmd(f"wget {url}")


def download_tarball(arch: str, edition: str, version: str):
    shell_cmd(f"mkdir -p {tmp_dir}")

    if edition == "community":
        tidb_name = f"tidb-community-server-{version}-{arch}"
        download(f"{download_url}/{tidb_name}.tar.gz")
        shell_cmd(f"tar xzf {tidb_name}.tar.gz -C {tmp_dir}")
        shell_cmd(f"mkdir -p {tmp_dir}/{tidb_name}/bin")
        files = shell_cmd(f"find {tmp_dir}/{tidb_name} -name '*.tar.gz'")
        file_list = [f for f in files.splitlines() if len(f) > 0]
        for f in file_list:
            shell_cmd(f"tar xzf {f} -C {tmp_dir}/{tidb_name}/bin")

        tool_name = f"tidb-community-toolkit-{version}-{arch}"
        download(f"{download_url}/{tool_name}.tar.gz")
        shell_cmd(f"tar xzf {tool_name}.tar.gz -C {tmp_dir}")
    else:
        tidb_name = f"tidb-{version}-{arch}"
        download(f"{download_url}/{tidb_name}.tar.gz")
        shell_cmd(f"tar xzf {tidb_name}.tar.gz -C {tmp_dir}")

        tool_name = f"tidb-toolkit-{version}-{arch}"
        download(f"{download_url}/{tool_name}.tar.gz")
        shell_cmd(f"tar xzf {tool_name}.tar.gz -C {tmp_dir}")

        if need_tiflash(version):
            tiflash_name = f"tiflash-{version}-{arch}"
            download(f"{download_url}/{tiflash_name}.tar.gz")
            shell_cmd(f"tar xzf {tiflash_name}.tar.gz -C {tmp_dir}")


def validates(version: str, hashes: Dict[str, str], arch: str, edition="community") -> int:
    if version >= "v5.2.0" and Components.importer in COMP_TO_BINARY.keys():
        COMP_TO_BINARY.pop(Components.importer)

    def do_valiate(cmd: str, comp: str) -> int:
        nerrs = 0  # can't modify variable in closure in Python
        cmd = f"{cmd} --version || {cmd} -V || {cmd} version"
        try:
            version_string = shell_cmd(cmd)
            logging.debug(version_string)

            matcher = Matcher(comp, version_string, version, hashes[comp], "community")
            mismatches = matcher.match()
            if len(mismatches) > 0:
                nerrs += 1
                msg = "\n\t\t".join(mismatches)
                logging.error(f"{comp}:\n\t\t{msg}\n")

        except Exception as e:
            nerrs += 1
            logging.error(f"On command: {cmd}\n    Exception: {e}")

        return nerrs

    err_count = 0
    # 具体看包里的内容, 能力有限没找到规律
    for comp, hashsum in hashes.items():
        if comp not in COMP_TO_BINARY.keys():
            logging.warn(f"[{comp}] not supported")
            continue

        if edition == "community":
            if in_community_tools_package(comp):  # overlap in toolkit tarball and tidb tarball
                for binary in COMP_TO_BINARY[comp]:
                    cmd = f"{tmp_dir}/tidb-community-toolkit-{version}-{arch}/bin/{binary}"
                    err_count += do_valiate(cmd, comp)

            for binary in COMP_TO_BINARY[comp]:
                env = f"LD_LIBRARY_PATH={tmp_dir}/tidb-community-server-{version}-{arch}/bin/tiflash"
                cmd = f"{env} {tmp_dir}/tidb-community-server-{version}-{arch}/bin/{binary}"
                err_count += do_valiate(cmd, comp)

        else:
            if comp not in COMP_TO_BINARY_COMMERCIRL.keys():
                continue  # not in commercial tarball, ignore

            if in_commercial_tools_package(comp):
                path = f"{tmp_dir}/tidb-toolkit-{version}-{arch}/bin"
            elif comp == Components.tiflash and need_tiflash(version):
                env = f"LD_LIBRARY_PATH={tmp_dir}/tiflash-{version}-{arch}"
                path = f"{env} {tmp_dir}/tiflash-{version}-{arch}"
            else:
                path = f"{tmp_dir}/tidb-{version}-{arch}/bin"

            for binary in COMP_TO_BINARY_COMMERCIRL[comp]:
                cmd = f"{path}/{binary}"
                err_count += do_valiate(cmd, comp)
    return err_count
