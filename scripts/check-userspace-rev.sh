#!/usr/bin/env bash
# flake.nix fetches nix-on-droid at a pinned rev; the userspace submodule, which the patches are
# written against, must be at the same one.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
submodule=$(git -C "$ROOT" ls-tree HEAD userspace | awk '{print $3}')
flake=$(sed -n 's#.*url = "github:nix-community/nix-on-droid/\([0-9a-f]\{40\}\)".*#\1#p' "$ROOT/flake.nix")
if [ "$submodule" != "$flake" ]; then
  echo "userspace is at ${submodule:-?} but flake.nix pins nix-on-droid at ${flake:-?}" >&2
  exit 1
fi
