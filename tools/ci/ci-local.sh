#!/usr/bin/env bash
# Run the repository's Woodpecker pipeline locally, without a server, on a
# throwaway copy of the working tree. `woodpecker-cli exec` bind-mounts the
# repository path, so running it on the checkout would let `npm ci` and the
# Gradle steps overwrite host files; the copy avoids that.
#
# Usage: tools/ci/ci-local.sh [pipeline-file]
#   default: .woodpecker/ci.yml
#   Exit 0 = green.
#
# Requirements: git, Docker (running) and woodpecker-cli on PATH.
# On Windows, run this from Git Bash or WSL, or use tools/ci/ci-local.cmd.
set -euo pipefail

root=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cfg="${1:-.woodpecker/ci.yml}"

case "$cfg" in
  /*) [ -f "$cfg" ] || { echo "ci-local: $cfg not found" >&2; exit 2; } ;;
  *) [ -f "$root/$cfg" ] || { echo "ci-local: $root/$cfg not found" >&2; exit 2; } ;;
esac

command -v woodpecker-cli >/dev/null || { echo "ci-local: woodpecker-cli is not on PATH" >&2; exit 2; }
docker info >/dev/null 2>&1 || { echo "ci-local: Docker is not running" >&2; exit 2; }

# A fixed directory keeps the named Docker volumes (Gradle, npm, Android SDK)
# warm across runs. The content is emptied; node_modules is kept because the
# package manager's content-addressable store lives inside it.
tmp="${CI_LOCAL_WORKDIR:-/tmp/ci-local-$(basename "$root")}"
mkdir -p "$tmp"
find "$tmp" -mindepth 1 -maxdepth 1 ! -name node_modules -exec rm -rf {} + 2>/dev/null || true

if git -C "$root" rev-parse --git-dir >/dev/null 2>&1; then
  # Tracked + untracked-not-ignored files: what a commit of the current tree would contain.
  git -C "$root" ls-files -z -co --exclude-standard |
    tar -C "$root" --ignore-failed-read --null -T - -cf - 2>/dev/null | tar -C "$tmp" -xf -
else
  tar -C "$root" \
    --exclude=.git --exclude=.venv --exclude=venv --exclude=node_modules \
    --exclude=__pycache__ --exclude=.mypy_cache --exclude=.pytest_cache \
    --exclude=.ruff_cache --exclude=.gradle --exclude=build \
    -cf - . | tar -C "$tmp" -xf -
fi

# .git too, so steps that call git behave as on the server.
[ -d "$root/.git" ] && tar -C "$root" -cf - .git | tar -C "$tmp" -xf -

repo=$(git -C "$root" remote get-url origin 2>/dev/null | sed -E 's#.*github\.com[:/]##; s#\.git$##' || true)
cd "$tmp"

# --repo-trusted-volumes: the pipeline uses named cache volumes, as a trusted
# repository does on the server. The timeout is larger than the CLI default.
woodpecker-cli exec --backend-engine docker --repo-trusted-volumes \
  --timeout "${CI_LOCAL_TIMEOUT:-3h}" \
  --repo-path "$(pwd -W 2>/dev/null || pwd)" \
  ${repo:+--repo "$repo"} --pipeline-event push "$cfg"
