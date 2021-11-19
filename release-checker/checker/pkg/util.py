import os
import subprocess
import logging
import json
import typing
from typing import Union, Dict

from .types import Components

logging.basicConfig(
    format='%(asctime)s %(levelname)-8s %(message)s',
    level=logging.INFO,
    datefmt='%Y-%m-%d %H:%M:%S',
    filemode='a')


def shell_cmd(cmd: str, env: Union[Dict[str, str], None] = None) -> str:
    cur_env = os.environ.copy()
    if env is not None:
        for k, v in env.items():
            cur_env[k] = v

    logging.debug(f"shell: {cmd}")
    proc = subprocess.Popen(['bash', '-c', cmd],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            stdin=subprocess.PIPE, env=cur_env)
    stdout, stderr = proc.communicate()
    if proc.returncode:
        raise Exception(proc.returncode, stdout.decode("utf8"), stderr.decode("utf8"), cmd)
    return stdout.decode("utf8") + "\n" + stderr.decode("utf8")


NEED_EDITIONS = [Components.pd, Components.tikv, Components.tidb]


def need_edition(comp_name):
    return comp_name in NEED_EDITIONS


COMMIT_SIFFIX = "_commit"


def get_hashes_from_file(f: typing.IO) -> dict:
    result = {}
    data: dict = json.load(f)

    for key, hashsum in data.items():
        if key.endswith(COMMIT_SIFFIX):
            comp_name = key[:-len(COMMIT_SIFFIX)]
            result[comp_name] = hashsum

    return result
