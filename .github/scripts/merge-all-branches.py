#!/usr/bin/env python3
import subprocess
import sys

def run_command(cmd, check=True):
    print(f"Running: {cmd}")
    result = subprocess.run(cmd, shell=True, text=True, capture_output=True)
    print(result.stdout)
    if result.returncode != 0:
        print(result.stderr)
        if check:
            sys.exit(result.returncode)
    return result.stdout.strip()

# Configure Git
run_command("git config --global user.name 'GitHub Actions'")
run_command("git config --global user.email 'actions@github.com'")

# Fetch all remote branches
run_command("git fetch --all")

# Get all remote branches
raw_branches = run_command("git branch -r")

branches = []
for line in raw_branches.splitlines():
    line = line.strip()
    # Skip HEAD pointer and base/publish branches
    if "HEAD" in line or "->" in line:
        continue
    if "origin/main-actions" in line or "origin/master" in line or "origin/repo" in line:
        continue

    branch_name = line.replace("origin/", "").strip()
    if branch_name:
        branches.append(branch_name)

print("Branches found for dynamic merge:")
print("\n".join(branches))

# Loop through and merge each branch
for branch in branches:
    print(f"===================================")
    print(f"Merging origin/{branch}...")

        # Attempt to merge. If it fails, exit with an error.
        result = subprocess.run(f"git merge origin/{branch} --no-edit --allow-unrelated-histories", shell=True, text=True, capture_output=True)

    if result.returncode != 0:
        print(result.stdout)
        print(result.stderr)
        print(f"::error::Merge conflict detected while merging {branch}. Please resolve this locally.")
        sys.exit(1)

    print(result.stdout)

print("All branches merged successfully!")
