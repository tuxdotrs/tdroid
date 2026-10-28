# The Android build: `nix-shell --run './scripts/fetch-proot.sh && ./gradlew :app:assembleDebug'`.
# nixos-unstable, since that's where androidenv has the NDK scripts/fetch-proot.sh pins.
{
  pkgs ? import (fetchTarball "https://github.com/NixOS/nixpkgs/archive/31ecbaca1bdefa45dffc409f0250bd8039ea0010.tar.gz") {
    config = {
      android_sdk.accept_license = true;
      allowUnfree = true;
    };
  },
}:
let
  ndkVersion = builtins.head (builtins.match ".*\nNDK_VERSION=([^\n]+)\n.*" (builtins.readFile ./scripts/fetch-proot.sh));
  android = pkgs.androidenv.composeAndroidPackages {
    platformVersions = [ "37.0" ];
    buildToolsVersions = [ "36.0.0" "37.0.0" ];
    cmakeVersions = [ "3.22.1" ];
    includeNDK = true;
    ndkVersions = [ ndkVersion ];
    includeEmulator = false;
    includeSystemImages = false;
  };
  sdk = "${android.androidsdk}/libexec/android-sdk";
in
pkgs.mkShell {
  packages = [
    pkgs.jdk25
    pkgs.jdk17
    pkgs.libarchive # bsdtar
    pkgs.perl # shasum
    pkgs.gnumake
    pkgs.curl
  ];
  ANDROID_HOME = sdk;
  ANDROID_SDK_ROOT = sdk;
  JAVA_HOME = pkgs.jdk25.home;
  # Gradle runs on 25; the build's toolchain is 17 (gradle.properties).
  JDK17_HOME = pkgs.jdk17.home;
  # Maven's aapt2 is linked for FHS distributions; androidenv's is patched for Nix.
  "ORG_GRADLE_PROJECT_android.aapt2FromMavenOverride" = "${sdk}/build-tools/37.0.0/aapt2";
}
