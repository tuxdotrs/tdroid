#!/usr/bin/env bash
# Builds the Linux system's bootstrap zip: nix-on-droid (the userspace submodule's rev, fetched by
# flake.nix) with patches/userspace applied. Upload the zip to a release and put its URL and SHA256
# in linux/Bootstrap.kt. Needs Nix with flakes.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/userspace"
ARCH=${ARCH:-aarch64}
# Where first boot gets its config template and every `nix-on-droid switch` the patched modules:
# this repo's flake.nix, which must be public.
TDROID_FLAKE=${TDROID_FLAKE:-github:tuxdotrs/tdroid}

"$ROOT/scripts/check-userspace-rev.sh"
mkdir -p "$OUT"
# --impure: nix-on-droid reads the flake URL from the environment.
NIX_ON_DROID_FLAKE_URL="$TDROID_FLAKE" \
  nix build "path:$ROOT#bootstrapZip-$ARCH" --impure --out-link "$OUT/result-$ARCH"
cp -f "$OUT/result-$ARCH/bootstrap-$ARCH.zip" "$OUT/"
chmod u+w "$OUT/bootstrap-$ARCH.zip"
sha256sum "$OUT/bootstrap-$ARCH.zip"
