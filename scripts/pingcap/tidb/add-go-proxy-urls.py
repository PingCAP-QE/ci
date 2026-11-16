#!/usr/bin/env python3
"""Add checksum-verified public URLs to generated legacy go_repository rules."""

import pathlib
import re
import sys

RULE = re.compile(r"(?ms)^([ \t]*)go_repository\(\n.*?^\1\)")
MIRROR = re.compile(r'(?m)^([ \t]*)"https://storage.googleapis.com/pingcapmirror/gomod/[^"\n]+",$')


def escape_module(value):
    # Go's module proxy protocol escapes uppercase letters with !lowercase.
    return "".join("!" + char.lower() if "A" <= char <= "Z" else char for char in value)


def add_proxy_urls(source):
    def rewrite(match):
        rule = match.group(0)
        mirror = MIRROR.search(rule)
        prefix = re.search(r'(?m)^\s*strip_prefix = "([^"\n]+)",$', rule)
        if not mirror or not prefix or "@" not in prefix.group(1):
            return rule
        module, version = prefix.group(1).rsplit("@", 1)
        url = "https://proxy.golang.org/{}/@v/{}.zip".format(
            escape_module(module), escape_module(version)
        )
        if '"' + url + '"' in rule:
            return rule
        # Preserve the rule's sha256, strip_prefix and fallback mirror. The
        # downloaded archive must still match the original pinned checksum.
        return rule[:mirror.start()] + mirror.group(1) + '"' + url + '",\n' + rule[mirror.start():]

    return RULE.sub(rewrite, source)


if __name__ == "__main__":
    for filename in sys.argv[1:]:
        path = pathlib.Path(filename)
        if path.is_file():
            source = path.read_text()
            result = add_proxy_urls(source)
            if result != source:
                path.write_text(result)
