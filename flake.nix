{
  description = "JollyFin Android development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    android-nixpkgs = {
      url = "github:tadfisher/android-nixpkgs";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    git-hooks = {
      url = "github:cachix/git-hooks.nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    android-app-ci = {
      url = "github:pschmitt/android-app-ci";
      flake = false;
    };
  };

  outputs =
    {
      self,
      nixpkgs,
      android-nixpkgs,
      git-hooks,
      android-app-ci,
    }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config.allowUnfree = true;
      };

      androidEnv = import "${android-app-ci}/nix/devshells.nix" {
        inherit pkgs android-nixpkgs system;
        appName = "JollyFin";
        # buildSrc/src/main/kotlin/Versions.kt: COMPILE_SDK = 37, BUILD_TOOLS = "36.1.0" (not a
        # typo - the project intentionally compiles against a newer platform than its pinned
        # build-tools version).
        buildToolsVersion = "36.1.0";
        platformVersion = "37-0";
        gitHooksLib = git-hooks.lib;
        # No local AVD-based screenshot capture here (only CI-driven, see screenshots.yaml) - so
        # no `screenshots` devShell.
        screenshotsSystemImage = null;
        quickStart = ''
          echo "  just build-phone-debug              # Build debug APK (phone) on rofl-13"
          echo "  just build-and-fetch-phone-debug    # ...and copy it back to ./dist"
          echo "  just deploy-phone-debug             # ...and install it on the Mi Pad 4"
          echo "  just mipad-logcat                   # Tail logs from the Mi Pad 4"
        '';
      };
    in
    {
      devShells.${system} = androidEnv.devShells;
      checks.${system} = androidEnv.checks;
    };
}
