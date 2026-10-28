{ config, lib, pkgs, ... }:

let
  # Packages the launcher adds and removes (coding agents), as nixpkgs attribute paths.
  launcherPackages = map
    (name: lib.getAttrFromPath (lib.splitString "." name) pkgs)
    (lib.importJSON ./tlauncher-packages.json);
in
{
  environment.packages = with pkgs; [
    # What the launcher runs in the guest
    bashInteractive
    bash-completion
    coreutils
    findutils
    gnugrep
    gnused
    gawk
    gnutar
    gzip
    xz
    unzip
    diffutils
    procps
    which
    hostname
    curl
    git
    less
    man-db
    ncurses
    foot
    foot.terminfo
    libxkbcommon # xkbcli, for the keyboard map
    fontconfig
    pulseaudio

    # Fonts; Android's own are in /system/fonts
    dejavu_fonts
    jetbrains-mono
    nerd-fonts.symbols-only


    # Add your own here, then run `nix-on-droid switch --flake ~/.config/nix-on-droid`.
  ] ++ launcherPackages;

  environment.etc = {
    "os-release".text = ''
      NAME="Nix-on-Droid"
      PRETTY_NAME="tlauncher (Nix-on-Droid)"
      ID=nix-on-droid
      ID_LIKE=nixos
      HOME_URL="https://github.com/tuxdotrs/tlauncher"
    '';

    "fonts/fonts.conf".text = ''
      <?xml version="1.0"?>
      <!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
      <fontconfig>
        <include ignore_missing="yes">${pkgs.fontconfig.out}/etc/fonts/conf.d</include>
        <dir>${config.user.home}/.nix-profile/share/fonts</dir>
        <dir>/system/fonts</dir>
        <cachedir>${config.user.home}/.cache/fontconfig</cachedir>
      </fontconfig>
    '';
  };

  environment.sessionVariables = {
    FONTCONFIG_FILE = "/etc/fonts/fonts.conf";
    TERMINFO_DIRS = "${config.user.home}/.nix-profile/share/terminfo";
    XKB_CONFIG_ROOT = "${pkgs.xkeyboard-config}/share/X11/xkb";
  };

  # Backup etc files instead of failing to activate generation if a file already exists in /etc
  environment.etcBackupExtension = ".bak";

  # Read the changelog before changing this value
  system.stateVersion = "24.05";

  nix.extraOptions = ''
    experimental-features = nix-command flakes
  '';
}
