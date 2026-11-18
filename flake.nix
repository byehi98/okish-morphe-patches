{
  description = "Morphe Patches development environment";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        # ------------------------------------------------------------------
        # Android SDK — required by the `app.morphe.patches` Gradle plugin:
        # the :extensions:* modules are Android apps built with compileSdk 36,
        # so plugin tasks (e.g. minifyReleaseWithR8) fail with
        # "SDK location not found" without a real SDK.
        #
        # Pinned to the versions `./gradlew buildAndroid` needs:
        #   platforms;android-36   (compileSdk 36)
        #   build-tools;36.0.0     (aapt2 / d8 / r8)
        # No emulator, system images, sources, CMake or NDK -> small closure.
        # ------------------------------------------------------------------
        androidComposition = pkgs.androidenv.composeAndroidPackages {
          cmdLineToolsVersion = "19.0";
          platformToolsVersion = "37.0.0";
          buildToolsVersions = [ "36.0.0" ];
          platformVersions = [ "36" ];
          includeEmulator = false;
          includeSystemImages = false;
          includeSources = false;
          includeCmake = false;
          includeNDK = false;
          extraLicenses = [
            "android-sdk-license"
            "android-sdk-preview-license"
            "android-googletv-license"
          ];
        };
        androidSdk = androidComposition.androidsdk;
      in
      {
        packages = {
          android-sdk = androidSdk;
          default = androidSdk;
        };

        devShells.default = pkgs.mkShell {
          name = "morphe-patches";

          buildInputs = with pkgs; [
            jdk17
            jdk21
            androidSdk
          ];

          shellHook = ''
            export ANDROID_HOME="${androidSdk}/libexec/android-sdk"
            export ANDROID_SDK_ROOT="$ANDROID_HOME"
            export JAVA_HOME="${pkgs.jdk17}"

            # AGP discovers the SDK through `sdk.dir` in local.properties
            # (ANDROID_HOME alone is not enough for old/parallel toolchains).
            # Keep the gitignored file pointing at the flake-provided SDK.
            if [ -d "$ANDROID_HOME" ]; then
              if ! grep -qxF "sdk.dir=$ANDROID_HOME" local.properties 2>/dev/null; then
                echo "sdk.dir=$ANDROID_HOME" > local.properties
                echo "morphe-patches: wrote local.properties -> sdk.dir=$ANDROID_HOME"
              fi
            fi

            echo "morphe-patches devshell: ANDROID_HOME=$ANDROID_HOME"
            echo "  ./gradlew buildAndroid  -> patches/build/libs/patches-*.mpp"
          '';
        };
      });
}
