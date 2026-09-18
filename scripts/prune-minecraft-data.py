#!/usr/bin/env python3
"""Prune minecraft-data to only the Java versions needed by Java-AFK.

Keeps:
  - minecraft-data/data/pc/common/  (index JSONs hard-required by index.js)
  - the pc version dirs referenced by allowlisted versions (dependency closure)
  - minecraft-data/data/bedrock/common/  (index JSONs hard-required by index.js)

Deletes every other pc/bedrock version dir (bedrock holds ~331MB of unused
data for a Java-only bot). Verifies by loading minecraft-data for each allowed
version with node. Exits non-zero with a clear message on any problem.

Usage:
  python3 scripts/prune-minecraft-data.py [minecraft-data-dir]
"""
import json
import os
import shutil
import subprocess
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ALLOWLIST = os.path.join(SCRIPT_DIR, "minecraft-data-allowlist.json")
DEFAULT_TARGET = os.path.join(
    SCRIPT_DIR, "..", "nodejs-project", "node_modules", "minecraft-data"
)


def main() -> None:
    target = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_TARGET)
    if not os.path.isdir(target):
        sys.exit(f"ERROR: minecraft-data dir not found: {target}")

    with open(ALLOWLIST, encoding="utf-8") as f:
        allowlist = json.load(f)["versions"]
    if not allowlist:
        sys.exit("ERROR: allowlist is empty: " + ALLOWLIST)

    data_dir = os.path.join(target, "minecraft-data", "data")
    data_paths = os.path.join(data_dir, "dataPaths.json")
    with open(data_paths, encoding="utf-8") as f:
        data_paths_json = json.load(f)

    pc_map = data_paths_json["pc"]
    for version in allowlist:
        if version not in pc_map:
            sys.exit(f"ERROR: '{version}' not in dataPaths.json pc entries.")

    closure = set()
    for version in allowlist:
        closure.update(pc_map[version].values())
    keep_pc = {"pc/common"} | {os.path.normpath(p) for p in closure}
    keep_bedrock = {"bedrock/common"}

    saved = 0
    for branch in ["pc", "bedrock"]:
        branch_dir = os.path.join(data_dir, branch)
        if not os.path.isdir(branch_dir):
            continue
        keep = keep_pc if branch == "pc" else keep_bedrock
        for entry in os.listdir(branch_dir):
            abs_entry = os.path.join(branch_dir, entry)
            rel = os.path.normpath(os.path.join(branch, entry))
            if rel in keep or not os.path.isdir(abs_entry):
                continue
            size = sum(
                os.path.getsize(os.path.join(r, fn))
                for r, _, files in os.walk(abs_entry)
                for fn in files
            )
            saved += size
            shutil.rmtree(abs_entry)
            print(f"removed {rel} ({size // 1024 // 1024} MiB)")

    print(f"kept versions: {', '.join(allowlist)}")
    print(f"kept pc dirs: {sorted(keep_pc)}")
    print(f"kept bedrock: {sorted(keep_bedrock)}")
    print(f"saved {saved // 1024 // 1024} MiB")

    node = shutil.which("node")
    if not node:
        sys.exit("ERROR: node not found; cannot verify pruning.")

    # 1) Every pc dir referenced by an allowlisted version must still exist.
    missing = []
    for version in allowlist:
        for rel in pc_map[version].values():
            if not os.path.isdir(os.path.join(data_dir, rel)):
                missing.append(f"{version} -> {rel}")
    if missing:
        sys.exit("ERROR: prune removed required data dirs:\n  " + "\n  ".join(sorted(missing)))

    # 2) minecraft-data must load each allowlisted version and expose key data.
    load = (
        "let bad=[];"
        "for(const v of process.argv[1].split(',')){try{"
        "const m=require('minecraft-data')(v);"
        "for(const k of ['blocks','items','protocol','biomes','entities','commands']){"
        "if(!m[k])bad.push(v+': missing '+k);}"
        "}catch(e){bad.push(v+': '+e.message);}}"
        "if(bad.length){console.error('FAIL',bad.join(' | '));process.exit(1);}"
        "console.log('OK',process.argv[1]);"
    )
    res = subprocess.run(
        [node, "-e", load, ",".join(allowlist)],
        cwd=target,
        capture_output=True,
        text=True,
    )
    print(res.stdout.strip())
    if res.returncode != 0:
        sys.exit("ERROR: prune verification failed:\n" + res.stderr.strip())


if __name__ == "__main__":
    main()