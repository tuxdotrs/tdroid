{
  description = "tlauncher's Linux system: nix-on-droid with patches/userspace applied";

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

      # nix-on-droid hard-codes /data/data/com.termux.nix, so its source is patched rather than configured.
      # Patching needs a builder; `pkgs` is the one of the system that evaluates.
      patchedFor = pkgs: pkgs.applyPatches {
        name = "nix-on-droid-tlauncher";
        src = nix-on-droid;
        patches = lib.filesystem.listFilesRecursive ./patches/userspace;
      };

      # Upstream's flake outputs, evaluated from the patched source with our inputs.
      upstreamFor = pkgs:
        let
          src = patchedFor pkgs;
          # Its outputs function takes exactly its own inputs.
          upstreamInputs = builtins.removeAttrs inputs [ "nix-on-droid" ];
          outputs = (import "${src}/flake.nix").outputs (upstreamInputs // { self = outputs // { outPath = "${src}"; }; });
        in
        outputs;
    in
    {
      lib.nixOnDroidConfiguration = args: (upstreamFor args.pkgs).lib.nixOnDroidConfiguration args;

      # `nix run github:tuxdotrs/tlauncher -- switch`, which first boot runs too.
      packages = forEachSystem (system:
        let upstream = upstreamFor nixpkgs.legacyPackages.${system};
        in {
          inherit (upstream.packages.${system}) nix-on-droid bootstrapZip-aarch64 bootstrapZip-x86_64;
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
        description = "tlauncher's default Nix-on-Droid config.";
      };
    };
}
