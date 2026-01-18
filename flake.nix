{
  description = "Spring Boot Java Project with Nix Flake for Linux and macOS ARM";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/adaa24fbf46737f3f1b5497bf64bae750f82942e";  # Adjust the version as needed
  };

  outputs = { self, nixpkgs }: let
    # A function that takes a system and returns the package set and derivation
    forSystem = system: let
      pkgs = import nixpkgs { inherit system; };
    in pkgs.stdenv.mkDerivation {
      pname = "spring-boot-app";
      version = "1.0.0";

      src = ./.;

      # Use Gradle or Maven, whichever you use in the Spring Boot project
      buildInputs = [ pkgs.gradle pkgs.jdk24 ];

      buildPhase = ''
        gradle clean build -x test
      '';

      installPhase = ''
        mkdir -p $out/bin
        cp build/libs/*.jar $out/bin/
      '';
    };
  in {
    # Provide packages in both 'packages' and 'defaultPackage' attributes
    packages = {
      x86_64-linux = forSystem "x86_64-linux";
      aarch64-darwin = forSystem "aarch64-darwin";
    };

    defaultPackage.x86_64-linux = forSystem "x86_64-linux";
    defaultPackage.aarch64-darwin = forSystem "aarch64-darwin";
  };
}
