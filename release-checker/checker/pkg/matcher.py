from typing import List
import re

from .types import Components
from .util import need_edition

version_regexp = re.compile(r"Release Version:\s*v?(\S+)", re.IGNORECASE)
hash_regexp = re.compile(r"Git Commit Hash:\s*(\w{40})", re.IGNORECASE)
edition_regexp = re.compile(r"Edition:\s*(Community|Enterprise)")


class Matcher:
    component: str
    input_string: str

    version: str
    hash: str
    edition: str

    result: List

    def __init__(self, component, input, version, hash, edition) -> None:
        self.component = component
        self.input_string = input
        self.version = version
        self.hash = hash
        self.edition = edition
        self.result = []

        if self.version[0] == "v":  # am aware of out of index
            self.version = self.version[1:]

    def match(self) -> List[str]:
        self.match_version()  # it's okay to eliminate duplicated code
        self.match_edition()
        self.match_hash()

        # call match functions, returns a complete error string
        return self.result

    def match_version(self):
        if self.component == Components.importer:
            return
        match = version_regexp.search(self.input_string)
        if match is None:
            self.result.append("version not found")
        elif match.groups()[0] != self.version:
            self.result.append(f"invalid version: [{match.groups()[0]}]; want [{self.version}]")

    def match_edition(self):
        if self.component == Components.tiflash:
            return
        if not need_edition(self.component):
            return

        match = edition_regexp.search(self.input_string)
        if match is None:
            self.result.append("edition not found")
        elif match.groups()[0].lower() != self.edition:
            self.result.append(f"invalid edition: [{match.groups()[0]}]; want [{self.edition}]")

    def match_hash(self):
        if self.component == Components.importer:
            return
        match = hash_regexp.search(self.input_string)
        if match is None:
            self.result.append("hash not found")
        elif match.groups()[0] != self.hash:
            self.result.append(f"invalid hash: [{match.groups()[0]}]; want [{self.hash}]")
