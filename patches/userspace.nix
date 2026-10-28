# patches/userspace for the phone. nix-on-droid sets its paths in two read-only options, which no
# module can override, so this drops the two modules that set them and imports copies with tlauncher's
# paths, rewritten while evaluating. Applying the patches would be a build on the phone, which fails
# under proot. The bootstrap zip is built from upstream's pkgs/, which takes no modules, so it keeps
# using the patches; both make the same change.
src:
let
  files = [ "modules/build/config.nix" "modules/user.nix" ];
  rewrite = file: builtins.toFile (baseNameOf file) (builtins.replaceStrings
    [ "/data/data/com.termux.nix/" ]
    [ "/data/data/com.tux.tlauncher/" ]
    (builtins.readFile "${src}/${file}"));
in
{
  disabledModules = map (file: "${src}/${file}") files;
  imports = map rewrite files;
}
