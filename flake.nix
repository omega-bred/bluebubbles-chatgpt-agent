{
  description = "Development environment for bluebubbles-chatgpt-agent";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-25.11";
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

          jdk = pkgs.jdk25;
          postgresql = pkgs.postgresql;
          stripeCli = pkgs.stripe-cli;

          python = pkgs.python313.withPackages (
            ps:
            [
              ps.pip
              ps.setuptools
              ps.virtualenv
              ps.wheel
            ]
            ++ lib.optionals stdenv.isLinux [
              ps.lxmf
              ps.rns
            ]
          );

          kcadm = pkgs.writeShellScriptBin "kcadm" ''
            exec ${pkgs.keycloak}/bin/kcadm.sh "$@"
          '';

          bbagentPostgresStart = pkgs.writeShellApplication {
            name = "bbagent-postgres-start";
            runtimeInputs = [
              pkgs.coreutils
              postgresql
            ];
            text = ''
              data_dir="''${BBAGENT_POSTGRES_DATA_DIR:-$PWD/.dev/postgres}"
              port="''${BBAGENT_POSTGRES_PORT:-5432}"
              user="''${BBAGENT_POSTGRES_USER:-postgres}"
              password="''${BBAGENT_POSTGRES_PASSWORD:-postgres}"
              log_file="''${BBAGENT_POSTGRES_LOG:-$data_dir/postgres.log}"

              mkdir -p "$data_dir"

              if [ ! -s "$data_dir/PG_VERSION" ]; then
                password_file="$(mktemp)"
                trap 'rm -f "$password_file"' EXIT
                printf '%s\n' "$password" > "$password_file"

                initdb \
                  --pgdata="$data_dir" \
                  --username="$user" \
                  --pwfile="$password_file" \
                  --auth-local=trust \
                  --auth-host=scram-sha-256
              fi

              if pg_ctl --pgdata="$data_dir" status >/dev/null 2>&1; then
                echo "Postgres is already running from $data_dir"
                exit 0
              fi

              pg_ctl \
                --pgdata="$data_dir" \
                --log="$log_file" \
                --options="-p $port" \
                start

              echo "Postgres started on localhost:$port"
              echo "  POSTGRES_JDBC_URL=jdbc:postgresql://localhost:$port/postgres"
              echo "  POSTGRES_USER=$user"
              echo "  POSTGRES_PASSWORD=$password"
              echo "  Log: $log_file"
            '';
          };

          bbagentPostgresStop = pkgs.writeShellApplication {
            name = "bbagent-postgres-stop";
            runtimeInputs = [ postgresql ];
            text = ''
              data_dir="''${BBAGENT_POSTGRES_DATA_DIR:-$PWD/.dev/postgres}"

              if [ ! -s "$data_dir/PG_VERSION" ]; then
                echo "No Postgres data dir found at $data_dir"
                exit 0
              fi

              pg_ctl --pgdata="$data_dir" stop --mode=fast
            '';
          };

          bbagentStripeListen = pkgs.writeShellApplication {
            name = "bbagent-stripe-listen";
            runtimeInputs = [ stripeCli ];
            text = ''
              forward_to="''${1:-''${STRIPE_WEBHOOK_FORWARD_TO:-http://localhost:8080/api/v1/subscription/receiveWebhook.subscriptionProviderEvents?provider=stripe}}"

              exec stripe listen --forward-to "$forward_to"
            '';
          };

          linuxNativeLibs = lib.optionals stdenv.isLinux [
            pkgs.gfortran.cc.lib
            pkgs.stdenv.cc.cc.lib
          ];
        in
        {
          inherit jdk linuxNativeLibs python;

          tools = [
            bbagentPostgresStart
            bbagentPostgresStop
            bbagentStripeListen
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
            pkgs.gradle_9
            pkgs.jq
            pkgs.keycloak
            pkgs.kubectl
            pkgs.kustomize
            pkgs.nodejs_20
            pkgs.openapi-generator-cli
            pkgs.openssl
            postgresql
            stripeCli
            pkgs.which
            pkgs.zsh
          ]
          ++ linuxNativeLibs;
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

            shellHook = ''
              export PATH="$PWD/frontend/node_modules/.bin:$PWD/build/typescript-client-generated/node_modules/.bin:$PATH"
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
            ''
            + pkgs.lib.optionalString pkgs.stdenv.isDarwin ''
              echo "  LXMF/RNS: omitted on Darwin because nixpkgs packages pull Linux Bluetooth deps"
            ''
            + ''
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
