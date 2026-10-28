#!/usr/bin/env bash
# Edits patches/userspace as commits.
#   userspace-patches.sh apply   checks out build/userspace-work (a worktree of the userspace
#                                submodule at its pinned rev) with the patches as commits on top
#   userspace-patches.sh export  writes those commits back to patches/userspace
# The submodule's own checkout is never changed.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/userspace-work"
PATCHES="$ROOT/patches/userspace"
base=$(git -C "$ROOT" ls-tree HEAD userspace | awk '{print $3}')

case "${1:-}" in
  apply)
    git -C "$ROOT/userspace" worktree remove --force "$WORK" 2>/dev/null || true
    git -C "$ROOT/userspace" worktree add --force -B tdroid-patches "$WORK" "$base"
    git -C "$WORK" am --3way "$PATCHES"/*.patch
    ;;
  export)
    rm -f "$PATCHES"/*.patch
    git -C "$WORK" format-patch --quiet --no-numbered --zero-commit -o "$PATCHES" "$base"
    ls "$PATCHES"
    ;;
  *)
    echo "usage: $0 apply|export" >&2
    exit 2
    ;;
esac
