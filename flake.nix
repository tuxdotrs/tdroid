{
  description = "tdroid's Linux system: nix-on-droid with patches/userspace applied";

  # The same inputs as userspace/flake.nix, which the patched nix-on-droid is evaluated with.
  inputs = {
    # Keep this rev equal to the userspace submodule's (scripts/check-userspace-rev.sh).
    nix-on-droid = {
      url = "github:nix-community/nix-on-droid/3264519d7df25f82e716e83607acf5e61f89b1ea";
      flake = false;
    };

    nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";

    # Pinned like upstream: the bootstrap zip and proot-termux are built with it.
    nixpkgs-for-bootstrap.url = "github:NixOS/nixpkgs/49ee0e94463abada1de470c9c07bfc12b36dcf40";

    home-manager = {
      url = "github:nix-community/home-manager";
      inputs.nixpkgs.follows = "nixpkgs";
    };

    nix-formatter-pack = {
      url = "github:Gerschtli/nix-formatter-pack";
      inputs.nixpkgs.follows = "nixpkgs";
      inputs.nmd.follows = "nmd";
    };

    nixpkgs-docs.url = "github:NixOS/nixpkgs/release-23.05";

    nmd = {
      url = "sourcehut:~rycee/nmd";
      inputs.nixpkgs.follows = "nixpkgs-docs";
    };
  };

  outputs = inputs@{ self, nixpkgs, nix-on-droid, ... }:
    let
      inherit (nixpkgs) lib;
      forEachSystem = lib.genAttrs [ "aarch64-linux" "x86_64-linux" ];

      # Upstream's flake outputs, evaluated from `src` with our inputs.
      outputsOf = src:
        let
          # Its outputs function takes exactly its own inputs.
          upstreamInputs = builtins.removeAttrs inputs [ "nix-on-droid" ];
          outputs = (import "${src}/flake.nix").outputs (upstreamInputs // { self = outputs // { outPath = "${src}"; }; });
        in
        outputs;

      # What the phone evaluates: unpatched upstream plus patches/userspace.nix, so nothing is built
      # before the configuration itself.
      upstream = outputsOf nix-on-droid;

      # The bootstrap zip, built on a computer: upstream with patches/userspace applied.
      patched = system: outputsOf (nixpkgs.legacyPackages.${system}.applyPatches {
        name = "nix-on-droid-tdroid";
        src = nix-on-droid;
        patches = lib.filesystem.listFilesRecursive ./patches/userspace;
      });
    in
    {
      lib.nixOnDroidConfiguration = args: upstream.lib.nixOnDroidConfiguration (args // {
        modules = (args.modules or [ ]) ++ [ (import ./patches/userspace.nix nix-on-droid) ];
      });

      # `nix run github:tuxdotrs/tdroid -- switch`, which first boot runs too.
      packages = forEachSystem (system: {
        inherit (upstream.packages.${system}) nix-on-droid;
        inherit ((patched system).packages.${system}) bootstrapZip-aarch64 bootstrapZip-x86_64;
        default = upstream.packages.${system}.nix-on-droid;
      });

      apps = forEachSystem (system: {
        default = {
          type = "app";
          program = "${self.packages.${system}.nix-on-droid}/bin/nix-on-droid";
        };
      });

      # The config first boot creates in ~/.config/nix-on-droid.
      templates.default = {
        path = ./userspace-template;
        description = "tdroid's default Nix-on-Droid config.";
      };
    };
}
