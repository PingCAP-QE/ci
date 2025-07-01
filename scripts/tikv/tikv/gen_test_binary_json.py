#!/usr/bin/env python
import json
import os
import shutil

def move_file(full_source_path, src_base_dir="/home/jenkins/tikv-src", target_dir="archive-test-binaries"):
    # Function to copy files preserving the directory structure, excluding base_dir
    relative_path = os.path.relpath(full_source_path, src_base_dir)
    # Construct the full target path
    full_target_path = os.path.join(target_dir, relative_path)

    # Create the target directory if it doesn't exist
    target_path_dir = os.path.dirname(full_target_path)
    if not os.path.exists(target_path_dir):
        os.makedirs(target_path_dir)
    # Copy the file
    shutil.copy2(full_source_path, full_target_path)
    shutil.move(full_source_path, full_source_path)
    print("Moved %s to %s" % (full_source_path, full_target_path))

merged_dict={ "rust-binaries": {} }
visited_files=set()

with open('test-binaries', 'w') as writer:
    with open('test.json', 'r') as f:
        for l in f:
            all = json.loads(l)
            binaries = all["rust-binaries"]
            if not "rust-build-meta" in merged_dict:
                merged_dict["rust-build-meta"] = all["rust-build-meta"]
            for (name, meta) in binaries.items():
                if meta["kind"] == "proc-macro":
                    continue
                bin = meta["binary-path"]
                if bin in visited_files:
                    continue
                visited_files.add(bin)
                merged_dict["rust-binaries"][name] = meta
                writer.write("%s\\n" % bin)
                move_file(bin, )

with open('test-binaries.json', 'w') as f:
    json.dump(merged_dict, f)
