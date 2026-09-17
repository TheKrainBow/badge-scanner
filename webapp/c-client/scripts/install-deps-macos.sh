#!/usr/bin/env bash
#
# Install everything needed to build the badge-scanner C client on macOS,
# using Homebrew for the libraries and Apple's own toolchain/PC/SC.
#
#   ./scripts/install-deps-macos.sh           # build + GUI deps
#
# Notes specific to macOS:
#  * The compiler + make come from the Xcode Command Line Tools, not brew.
#  * PC/SC is a *system framework* (PCSC.framework) — nothing to install for
#    it. The Makefile auto-detects macOS (via `uname -s`) and links
#    `-framework PCSC` instead of `-lpcsclite`, so plain `make`/`make gui`
#    work with no extra flags.
#  * Homebrew's curl is "keg-only"; the Makefile adds it to PKG_CONFIG_PATH
#    automatically on macOS so pkg-config can find libcurl.
#
set -euo pipefail

if [ "$(uname -s)" != "Darwin" ]; then
    echo "This script is for macOS. On Linux use install-deps-fedora.sh or install-deps-debian.sh." >&2
    exit 1
fi

# 1. Xcode Command Line Tools (clang + make + headers).
if ! xcode-select -p >/dev/null 2>&1; then
    echo "==> Installing Xcode Command Line Tools (a GUI dialog will pop up)"
    xcode-select --install || true
    echo "    Re-run this script once the Command Line Tools finish installing."
    exit 0
else
    echo "==> Xcode Command Line Tools already present"
fi

# 2. Homebrew.
if ! command -v brew >/dev/null 2>&1; then
    echo "Homebrew not found. Install it first from https://brew.sh, then re-run." >&2
    exit 1
fi

# Library dev packages.
#   pkg-config -> how the Makefile finds libcurl/GTK
#   curl       -> HTTP + WebSocket lookups (keg-only; see note above)
#   gtk+3      -> the optional `make gui` graphical client
# PC/SC is a system framework (no brew package). cJSON is vendored.
PACKAGES=(
    pkg-config
    curl
    gtk+3
)

echo "==> Installing build + GUI dependencies via Homebrew:"
printf '    %s\n' "${PACKAGES[@]}"
brew install "${PACKAGES[@]}"

echo
echo "Done. The Makefile auto-detects macOS (framework PC/SC + Homebrew curl"
echo "path), so just build with:"
echo "    make        # CLI  -> bin/badge-lookup"
echo "    make gui    # GUI  -> bin/badge-lookup-gui"
