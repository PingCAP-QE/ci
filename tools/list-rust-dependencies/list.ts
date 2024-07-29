// run by cargo tree -f "{p} --  {l} -- {r}" | tee tree.full.log
/*
tikv-ctl v0.0.1 (/workspace/tikv/cmd/tikv-ctl) --  Apache-2.0 --
├── api_version v0.1.0 (/workspace/tikv/components/api_version) --  Apache-2.0 --
│   ├── bitflags v1.3.2 --  MIT/Apache-2.0 -- https://github.com/bitflags/bitflags
│   ├── codec v0.0.1 (/workspace/tikv/components/codec) --  Apache-2.0 --
│   │   ├── byteorder v1.4.3 --  Unlicense OR MIT -- https://github.com/BurntSushi/byteorder
│   │   ├── error_code v0.0.1 (/workspace/tikv/components/error_code) --  Apache-2.0 --
│   │   │   ├── grpcio v0.10.4 --  Apache-2.0 -- https://github.com/tikv/grpc-rs
│   │   │   │   ├── futures-executor v0.3.15 --  MIT OR Apache-2.0 -- https://github.com/rust-lang/futures-rs
│   │   │   │   │   ├── futures-core v0.3.15 --  MIT OR Apache-2.0 -- https://github.com/rust-lang/futures-rs
│   │   │   │   │   ├── futures-task v0.3.15 --  MIT OR Apache-2.0 -- https://github.com/rust-lang/futures-rs
│   │   │   │   │   ├── futures-util v0.3.15 --  MIT OR Apache-2.0 -- https://github.com/rust-lang/futures-rs
│   │   │   │   │   │   ├── futures v0.1.31 --  MIT/Apache-2.0 -- https://github.com/rust-lang-nursery/futures-rs
│   │   │   │   │   │   ├── futures-channel v0.3.15 --  MIT OR Apache-2.0 -- https://github.com/rust-lang/futures-rs
│   │   │   │   │   │   │   ├── futures-core v0.3.15 --  MIT OR Apache-2.0 -- https://github.com/rust-lang/futures-rs
│   │   │   │   │   │   │   └── futures-sink v0.3.15 --  MIT OR Apache-2.0 -- https://github.com/rust-lang/futures-rs
*/

interface crateDep {
  depth: number;
  name: string;
  version: string;
  path: string;
  license: string;
  url: string;
}

async function getDepTree(workspace: string) {
  // Run command "cargo tree -f "{p} --  {l} -- {r}" and get the output.
  const command = new Deno.Command("cargo", {
    args: ["tree", "--edges", "no-build", "-f", "{p} --  {l} -- {r}"],
    cwd: workspace,
  });

  const { code, stdout } = await command.output();
  console.assert(code === 0);
  const tree = new TextDecoder().decode(stdout);
  //   console.log(tree);
  const deps = parseDepTree(tree);
  // write csv file
  const columnes = ["depth", "name", "version", "path", "license", "url"];
  const csv = [columnes.join(",")].concat(deps
    .map((dep) =>
      [
        dep.depth,
        dep.name,
        dep.version,
        dep.path,
        dep.license,
        dep.url,
      ].join(",")
    ))
    .join("\n");
  await Deno.writeTextFile("tree.csv", csv);
}

/**
 * parse crate dependency tree
 * @param tree
 */
function parseDepTree(tree: string) {
  const lines = tree.split("\n");
  const result: crateDep[] = [];

  for (const line of lines) {
    if (line.trim().length === 0) {
      continue;
    }
    const [pkg, license, url] = line.split("-- ", 3).map((s) => s.trim());
    const depth = parseDepth(pkg);
    const [name, version, path] = pkg.replace(/.*(──\s+)/g, "").split(
      " ",
      3,
    );

    console.dir({ name, depth, version, path });
    result.push({
      depth,
      name,
      version,
      path,
      license,
      url,
    });
  }

  return result;
}

function parseDepth(line: string) {
  const parts = line.match(/^(?:(│\s+)|(├──\s+)|(└──\s+))+/);
  return parts ? parts[0].length / 4 : 0;
}

async function main() {
  await getDepTree(Deno.cwd());
}

await main();
