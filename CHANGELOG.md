# Changelog

The most important changes in each release. The Play Store notes for a release
are in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

tlauncher is a fork of [omalauncher](https://github.com/SimonSchubert/omalauncher);
its history up to 0.14.0 is in that repository.

## 0.1.0 (versionCode 1) – unreleased

- Renamed to tlauncher (`com.tux.tlauncher`).
- The Linux system is Nix-on-Droid instead of Arch Linux ARM. Packages are
  managed by Nix: edit `~/.config/nix-on-droid/flake.nix` and run
  `nix-on-droid switch --flake ~/.config/nix-on-droid`.
- Removed for now: the packages screen, AUR, system updates, automatic installs
  of a widget's `requires`, and the GPU driver installer (Linux renders in software).
