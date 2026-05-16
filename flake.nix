{
  description = "Development environment for bluebubbles-chatgpt-agent";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/adaa24fbf46737f3f1b5497bf64bae750f82942e";
  };

  outputs =
    { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];

      forAllSystems = nixpkgs.lib.genAttrs systems;

      pkgsFor =
        system:
        import nixpkgs {
          inherit system;
          config.allowUnfree = true;
        };

      devToolsFor =
        pkgs:
        let
          inherit (pkgs) lib stdenv;

          jdk = pkgs.temurin-bin-24;

          python = pkgs.python313.withPackages (
            ps: with ps; [
              lxmf
              pip
              rns
              setuptools
              virtualenv
              wheel
            ]
          );

          kcadm = pkgs.writeShellScriptBin "kcadm" ''
            exec ${pkgs.keycloak}/bin/kcadm.sh "$@"
          '';

          linuxNativeLibs = lib.optionals stdenv.isLinux [
            pkgs.gfortran.cc.lib
            pkgs.stdenv.cc.cc.lib
          ];
        in
        {
          inherit jdk linuxNativeLibs python;

          tools = [
            jdk
            python
            kcadm

            pkgs._1password-cli
            pkgs.bashInteractive
            pkgs.coreutils
            pkgs.curl
            pkgs.docker-client
            pkgs.docker-compose
            pkgs.findutils
            pkgs.flyway
            pkgs.fluxcd
            pkgs.git
            pkgs.gnused
            pkgs.gradle
            pkgs.jq
            pkgs.keycloak
            pkgs.kubectl
            pkgs.kustomize
            pkgs.nodejs_20
            pkgs.openapi-generator-cli
            pkgs.openssl
            pkgs.postgresql
            pkgs.which
            pkgs.zsh
          ] ++ linuxNativeLibs;
        };

      formatterFor =
        pkgs:
        pkgs.writeShellApplication {
          name = "bbagent-nixfmt";
          runtimeInputs = [ pkgs.nixfmt-rfc-style ];
          text = ''
            if [ "$#" -eq 0 ]; then
              set -- flake.nix
            fi

            exec nixfmt "$@"
          '';
        };
    in
    {
      devShells = forAllSystems (
        system:
        let
          pkgs = pkgsFor system;
          devTools = devToolsFor pkgs;
        in
        {
          default = pkgs.mkShell {
            packages = devTools.tools;

            JAVA_HOME = "${devTools.jdk}";
            BBAGENT_NIX_SHELL = "1";

            shellHook =
              ''
                export PATH="$PWD/frontend/node_modules/.bin:$PWD/build/typescript-client-generated/node_modules/.bin:$PATH"
                export GRADLE_OPTS="''${GRADLE_OPTS:-} -Dorg.gradle.java.installations.paths=${devTools.jdk} -Dorg.gradle.java.installations.auto-download=false"
                export npm_config_audit="false"
                export npm_config_fund="false"
              ''
              + pkgs.lib.optionalString pkgs.stdenv.isLinux ''
                export LD_LIBRARY_PATH="${pkgs.lib.makeLibraryPath devTools.linuxNativeLibs}:''${LD_LIBRARY_PATH:-}"
              ''
              + ''

                echo "bluebubbles-chatgpt-agent dev shell"
                echo "  Java:  $(java -version 2>&1 | head -n 1)"
                echo "  Node:  $(node --version) / npm $(npm --version)"
                echo "  Python: $(python --version)"
                echo "  Try:   ./gradlew test"
              '';
          };
        }
      );

      packages = forAllSystems (
        system:
        let
          pkgs = pkgsFor system;
          devTools = devToolsFor pkgs;
        in
        {
          dev-tools = pkgs.buildEnv {
            name = "bluebubbles-chatgpt-agent-dev-tools";
            paths = devTools.tools;
          };

          default = self.packages.${system}.dev-tools;
        }
      );

      formatter = forAllSystems (system: formatterFor (pkgsFor system));
    };
}
