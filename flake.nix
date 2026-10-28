{
  description = "tdroid's Linux system: nix-on-droid with patches/userspace applied";

  # The same inputs as userspace/flake.nix, which the patched nix-on-droid is evaluated with.
  inputs = {
    # Keep this rev equal to the userspace submodule's (scripts/check-userspace-rev.sh).
    nix-on-droid = {
      url = "github:nix-community/nix-on-droid/3264519d7df25f82e716e83607acf5e61f89b1ea";
      flake = false;
    };

    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

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

      /**
       * pkgs.tdroid in every tdroid config. mesa: OpenGL as zink on the phone's Vulkan driver (Turnip on Adreno), with
       * patches/mesa so zink presents through wl_shm, the only buffers tdroid's compositor takes; only the drivers a
       * phone can use, to keep the build short. It's on tuxdotrs.cachix.org, built from this flake's nixpkgs, which
       * configs follow so theirs match it. gpu-check: whether OpenGL starts on the GPU, run by the launcher.
       */
      tdroidOverlay = final: prev: {
        tdroid = rec {
          mesa = (prev.mesa.override {
            galliumDrivers = [ "zink" "llvmpipe" "freedreno" "panfrost" ];
            vulkanDrivers = [ "freedreno" "panfrost" "swrast" ];
          }).overrideAttrs (old: {
            pname = "mesa-tdroid";
            patches = (old.patches or [ ]) ++ lib.filesystem.listFilesRecursive ./patches/mesa;
            # Video decoding through VA-API needs a desktop GPU's driver; none of these have it.
            mesonFlags = old.mesonFlags ++ [ (lib.mesonEnable "gallium-va" false) ];
            # spirv2dxil comes with the Dozen (D3D12) driver, which isn't built; its output stays, empty.
            postInstall = old.postInstall + "\nmkdir -p $spirv2dxil\n";
          });
          gpu-check = final.writeShellScriptBin "tdroid-gpu-check" ''
            # OpenGL offscreen with the environment the launcher gives GPU apps; prints the renderer.
            ${final.mesa-demos}/bin/eglinfo -B -p surfaceless 2>&1 | grep -m1 'renderer: zink' || { echo "OpenGL didn't start on the GPU"; exit 1; }
          '';
        };
      };
    in
    {
      overlays.default = tdroidOverlay;

      lib.nixOnDroidConfiguration = args: upstream.lib.nixOnDroidConfiguration (args // {
        pkgs = args.pkgs.extend tdroidOverlay;
        modules = (args.modules or [ ]) ++ [ (import ./patches/userspace.nix nix-on-droid) ];
      });

      # `nix run github:tuxdotrs/tdroid -- switch`, which first boot runs too.
      packages = forEachSystem (system: {
        inherit (upstream.packages.${system}) nix-on-droid;
        inherit ((patched system).packages.${system}) bootstrapZip-aarch64 bootstrapZip-x86_64;
        default = upstream.packages.${system}.nix-on-droid;

        # The patched Mesa (see tdroidOverlay), for scripts/push-mesa.sh to build and push to the cache.
        mesa = (nixpkgs.legacyPackages.${system}.extend tdroidOverlay).tdroid.mesa;
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
