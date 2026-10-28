{
  description = "tdroid's Nix-on-Droid system config.";

  inputs = {
    nix-on-droid.url = "github:tuxdotrs/tdroid";

    # tdroid's own nixpkgs (nixos-unstable), so the GPU driver it builds comes from its binary cache rather than
    # being compiled here. `nix flake update` moves both together.
    nixpkgs.follows = "nix-on-droid/nixpkgs";
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
