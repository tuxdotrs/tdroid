#!/usr/bin/env bash
# Builds tdroid's patched Mesa (the flake's tdroidOverlay) for aarch64 and pushes it to tuxdotrs.cachix.org, where
# phones get it instead of compiling it. Run it after every change to flake.lock's nixpkgs or to patches/mesa, and push
# the commit only once it's done: a phone that updates before the cache has it builds Mesa itself, which fails.
# On x86_64 this builds under qemu (binfmt, extra-platforms = aarch64-linux): an hour or two. Needs `cachix authtoken`.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/mesa-aarch64"
nix build "path:$ROOT#packages.aarch64-linux.mesa" --out-link "$OUT" -L
# The whole closure: what cache.nixos.org already has is skipped.
nix shell nixpkgs#cachix -c cachix push tuxdotrs "$OUT"
readlink "$OUT"
