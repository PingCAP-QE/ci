#!/usr/bin/env python
import subprocess
import json
import os
import shutil

chunk_count = os.environ.get("LEGACY_CHUNK_COUNT", 20)
scores = list()

def score(bin, l):
    if "integration" in bin or "failpoint" in bin:
        if "test_split_region" in l:
            return 50
        else:
            return 20
    elif "deps/tikv-" in bin:
        return 10
    else:
        return 1

def write_to(part):
    f = open("test-chunk-%d" % part, 'w')
    f.write("#/usr/bin/env bash\n")
    f.write("set -ex\n")
    return f

# def upload(bin, binary_tmp_path="test-binaries-tmp"):
#     return subprocess.check_call(["cp", bin, binary_tmp_path])

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


total_score=0
visited_files=set()

with open('test.json', 'r') as f:
    for l in f:
        if "proc-macro" in l:
            continue
        meta = json.loads(l)
        if "profile" in meta and meta["profile"]["test"]:
            for bin in meta["filenames"]:
                if bin in visited_files:
                    continue
                visited_files.add(bin)
                cases = subprocess.check_output([bin, '--list']).splitlines()
                if len(cases) < 2:
                    continue
                cases = list(c[:c.index(': ')] for c in cases if ': ' in c)
                bin_score = sum(score(bin, c) for c in cases)
                scores.append((bin, cases, bin_score))
                total_score += bin_score

chunk_score = total_score / chunk_count + 1
current_chunk_score=0
part=1
writer = write_to(part)
scores.sort(key=lambda t: t[0])

for bin, cases, bin_score in scores:
    move_file(bin)
    if current_chunk_score + bin_score <= chunk_score:
        writer.write("%s --test --nocapture\n" % bin)
        current_chunk_score += bin_score
        continue
    batch_cases = list()
    for c in cases:
        c_score = score(bin, c)
        if current_chunk_score + c_score > chunk_score and part < chunk_count and batch_cases:
            writer.write("%s --test --nocapture --exact %s\n" % (bin, ' '.join(batch_cases)))
            current_chunk_score = 0
            part += 1
            writer.close()
            writer = write_to(part)
            batch_cases = list()
        batch_cases.append(c)
        current_chunk_score += c_score
    if batch_cases:
        writer.write("%s --test --nocapture --exact %s\n" % (bin, ' '.join(batch_cases)))

writer.close()
