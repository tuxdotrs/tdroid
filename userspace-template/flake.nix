{
  description = "tdroid's Nix-on-Droid system config.";

  inputs = {
    # Lowercase on purpose: first boot rewrites lines with github:NixOS/nixpkgs to its own default (24.05).
    nixpkgs.url = "github:nixos/nixpkgs/nixos-26.05";

    nix-on-droid = {
      url = "github:tuxdotrs/tdroid";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, nix-on-droid }: {

    nixOnDroidConfigurations.default = nix-on-droid.lib.nixOnDroidConfiguration {
      pkgs = import nixpkgs {
        system = "aarch64-linux";
        # Coding agents like Claude Code and Copilot are unfree.
        config.allowUnfree = true;
      };
      modules = [ ./nix-on-droid.nix ];
    };

  };
}
