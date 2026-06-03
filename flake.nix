{
  description = "Development environment for bluebubbles-chatgpt-agent";

  nixConfig = {
    extra-substituters = [ "https://bluebubbles-chatgpt-agent.cachix.org" ];
    extra-trusted-public-keys = [
      "bluebubbles-chatgpt-agent.cachix.org-1:+tLEA7ftEhOdiF5pMGTY46sx+auf6Wd/JQcbRsQUsuw="
    ];
  };

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
          ascCli =
            let
              buildGo126Module = pkgs.buildGoModule.override { go = pkgs.go_1_26; };
            in
            buildGo126Module rec {
              pname = "asc";
              version = "1.9.0";

              src = pkgs.fetchFromGitHub {
                owner = "rorkai";
                repo = "App-Store-Connect-CLI";
                rev = version;
                sha256 = "0bcbv6ylzn7c9nbs0fg0m6p44i09w701x9j4ampzh0wqbjarwl29";
              };

              vendorHash = "sha256-XBEDMUGwSh8P+dVKMebN3zD83e1odAN+Wy15yys0+2M=";

              subPackages = [ "." ];

              ldflags = [
                "-s"
                "-w"
                "-X main.version=${version}"
                "-X main.commit=bf56bf36b6389a031779d7594e49e8a1e70179b4"
                "-X main.date=1970-01-01T00:00:00Z"
              ];

              doCheck = false;

              postInstall = ''
                mv "$out/bin/App-Store-Connect-CLI" "$out/bin/asc"
              '';

              meta = {
                description = "Unofficial App Store Connect CLI";
                homepage = "https://github.com/rorkai/App-Store-Connect-CLI";
                license = pkgs.lib.licenses.mit;
                mainProgram = "asc";
              };
            };

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

          darwinAppleTools = lib.optionals stdenv.isDarwin [
            pkgs.swiftformat
            pkgs.xcbeautify
          ];
        in
        {
          inherit
            ascCli
            darwinAppleTools
            jdk
            linuxNativeLibs
            python
            ;

          tools = [
            ascCli
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
          ++ darwinAppleTools
          ++ linuxNativeLibs;
        };

      projectSourceFor =
        pkgs:
        let
          inherit (pkgs) lib;
          root = toString ./.;

          relativePath =
            path:
            let
              pathString = toString path;
            in
            if pathString == root then "" else lib.removePrefix "${root}/" pathString;

          excluded =
            rel:
            rel == ".dev"
            || lib.hasPrefix ".dev/" rel
            || rel == ".git"
            || lib.hasPrefix ".git/" rel
            || rel == ".gradle"
            || lib.hasPrefix ".gradle/" rel
            || rel == ".idea"
            || lib.hasPrefix ".idea/" rel
            || rel == "build"
            || lib.hasPrefix "build/" rel
            || rel == "frontend/node_modules"
            || lib.hasPrefix "frontend/node_modules/" rel
            || rel == "frontend/dist"
            || lib.hasPrefix "frontend/dist/" rel
            || rel == "frontend/src/client"
            || lib.hasPrefix "frontend/src/client/" rel
            || rel == "nix/typescript-client/node_modules"
            || lib.hasPrefix "nix/typescript-client/node_modules/" rel
            || rel == "src/main/resources/static/client"
            || lib.hasPrefix "src/main/resources/static/client/" rel
            || rel == "src/main/resources/static/assets"
            || lib.hasPrefix "src/main/resources/static/assets/" rel
            || rel == "src/main/resources/static/index.html"
            || rel == "src/main/resources/static/apple-touch-icon.png"
            || rel == "src/main/resources/static/bluechat-icon.png"
            || rel == "src/main/resources/static/favicon-32x32.png"
            || rel == "src/main/resources/static/site.webmanifest"
            || lib.hasPrefix "src/main/resources/static/icon-" rel
            || lib.hasInfix "/__pycache__/" rel
            || lib.hasSuffix ".pyc" rel
            || lib.hasSuffix ".pyo" rel
            || lib.hasSuffix ".class" rel
            || lib.hasSuffix ".log" rel
            || lib.hasSuffix ".DS_Store" rel;
        in
        lib.cleanSourceWith {
          src = ./.;
          filter = path: _type: !excluded (relativePath path);
        };

      lxmfBridgeSourceFor =
        pkgs:
        let
          inherit (pkgs) lib;
          root = toString ./lxmf-bridge;

          relativePath =
            path:
            let
              pathString = toString path;
            in
            if pathString == root then "" else lib.removePrefix "${root}/" pathString;
        in
        lib.cleanSourceWith {
          src = ./lxmf-bridge;
          filter =
            path: _type:
            let
              rel = relativePath path;
            in
            !(lib.hasInfix "/__pycache__/" rel || lib.hasSuffix ".pyc" rel || lib.hasSuffix ".pyo" rel);
        };

      buildPackagesFor =
        system:
        let
          pkgs = pkgsFor system;
          inherit (pkgs) lib;

          devTools = devToolsFor pkgs;
          jdk = devTools.jdk;
          nodejs = pkgs.nodejs_20;
          gradle = pkgs.gradle_9.override {
            java = jdk;
            javaToolchains = [ jdk ];
          };

          projectSource = projectSourceFor pkgs;

          frontendNodeModules = pkgs.importNpmLock.buildNodeModules {
            npmRoot = ./frontend;
            inherit nodejs;
          };

          typescriptClientNodeModules = pkgs.importNpmLock.buildNodeModules {
            npmRoot = ./nix/typescript-client;
            inherit nodejs;
          };

          nativeLibs = [
            pkgs.gfortran.cc.lib
          ];

          nativeLibraryPath = lib.makeLibraryPath nativeLibs;

          app = pkgs.stdenv.mkDerivation (finalAttrs: {
            pname = "bluebubbles-chatgpt-agent";
            version = "0.0.1-SNAPSHOT";

            src = projectSource;

            nativeBuildInputs = [
              gradle
              nodejs
            ];

            mitmCache = gradle.fetchDeps {
              pkg = finalAttrs.finalPackage;
              data = ./nix/gradle-deps.json;
            };

            __darwinAllowLocalNetworking = true;

            gradleBuildTask = "build bootJar -x test";
            gradleUpdateTask = "build bootJar -x test";
            gradleFlags = [
              "-Dfile.encoding=UTF-8"
              "-Dorg.gradle.java.home=${jdk}"
            ];

            doCheck = false;

            preBuild = ''
              export BBAGENT_USE_NIX_NODE_MODULES=true
              export JAVA_HOME=${jdk}
              export NPM_PATH=${nodejs}/bin/npm

              rm -rf frontend/node_modules
              ln -s ${frontendNodeModules}/node_modules frontend/node_modules

              gradle openApiGenerateTypescriptClient

              rm -rf build/typescript-client-generated/node_modules
              ln -s ${typescriptClientNodeModules}/node_modules build/typescript-client-generated/node_modules
            '';

            installPhase = ''
              runHook preInstall

              install -Dm644 \
                build/libs/bluebubbles-chatgpt-agent-${finalAttrs.version}.jar \
                "$out/share/bluebubbles-chatgpt-agent/bluebubbles-chatgpt-agent.jar"

              runHook postInstall
            '';

            meta = {
              description = "Spring Boot service for the BlueChat messaging agent";
              license = lib.licenses.mit;
              sourceProvenance = with lib.sourceTypes; [
                fromSource
                binaryBytecode
              ];
            };
          });

          appImageRoot = pkgs.buildEnv {
            name = "bluebubbles-chatgpt-agent-image-root";
            paths = [
              app
              jdk
              pkgs.dockerTools.caCertificates
              pkgs.findutils
            ]
            ++ nativeLibs;
            pathsToLink = [
              "/bin"
              "/etc"
              "/lib"
              "/share"
            ];
          };

          appImage = pkgs.dockerTools.buildLayeredImage {
            name = "bluebubbles-chatgpt-agent";
            tag = "nix";
            created = "1970-01-01T00:00:01Z";
            maxLayers = 120;
            contents = appImageRoot;
            extraCommands = ''
              mkdir -p app tmp var/tmp
              chmod 1777 tmp var/tmp
            '';
            config = {
              Cmd = [
                "${jdk}/bin/java"
                "--add-opens=java.base/java.lang=ALL-UNNAMED"
                "--add-opens=java.base/java.nio.charset=ALL-UNNAMED"
                "-jar"
                "${app}/share/bluebubbles-chatgpt-agent/bluebubbles-chatgpt-agent.jar"
              ];
              Env = [
                "JAVA_HOME=${jdk}"
                "LD_LIBRARY_PATH=${nativeLibraryPath}"
                "PATH=${jdk}/bin:${pkgs.findutils}/bin"
                "SSL_CERT_FILE=/etc/ssl/certs/ca-bundle.crt"
              ];
              ExposedPorts = {
                "8080/tcp" = { };
              };
              WorkingDir = "/app";
            };
          };

          lxmfBridge = pkgs.stdenvNoCC.mkDerivation {
            pname = "bluebubbles-chatgpt-agent-lxmf-bridge";
            version = "0.0.1";
            src = lxmfBridgeSourceFor pkgs;

            dontBuild = true;

            installPhase = ''
              runHook preInstall

              install -Dm755 app.py "$out/lib/lxmf-bridge/app.py"
              install -Dm755 canary.py "$out/lib/lxmf-bridge/canary.py"

              runHook postInstall
            '';
          };

          lxmfBridgePython = pkgs.python313.withPackages (ps: [
            ps.lxmf
            ps.rns
          ]);

          lxmfBridgeImageRoot = pkgs.buildEnv {
            name = "bluebubbles-chatgpt-agent-lxmf-bridge-image-root";
            paths = [
              lxmfBridge
              lxmfBridgePython
              pkgs.dockerTools.caCertificates
            ];
            pathsToLink = [
              "/bin"
              "/etc"
              "/lib"
            ];
          };

          lxmfBridgeImage = pkgs.dockerTools.buildLayeredImage {
            name = "bluebubbles-chatgpt-agent-lxmf-bridge";
            tag = "nix";
            created = "1970-01-01T00:00:01Z";
            maxLayers = 120;
            contents = lxmfBridgeImageRoot;
            extraCommands = ''
              mkdir -p app tmp var/tmp
              chmod 1777 tmp var/tmp
            '';
            config = {
              Cmd = [
                "${lxmfBridgePython}/bin/python"
                "${lxmfBridge}/lib/lxmf-bridge/app.py"
              ];
              Env = [
                "PYTHONUNBUFFERED=1"
                "SSL_CERT_FILE=/etc/ssl/certs/ca-bundle.crt"
              ];
              ExposedPorts = {
                "8091/tcp" = { };
              };
              WorkingDir = "/app";
            };
          };
        in
        {
          inherit
            app
            frontendNodeModules
            typescriptClientNodeModules
            ;

          frontend-node-modules = frontendNodeModules;
          typescript-client-node-modules = typescriptClientNodeModules;
        }
        // lib.optionalAttrs pkgs.stdenv.isLinux {
          inherit
            appImage
            lxmfBridge
            lxmfBridgeImage
            ;

          app-image = appImage;
          lxmf-bridge = lxmfBridge;
          lxmf-bridge-image = lxmfBridgeImage;
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

              if [ "''${BBAGENT_NIX_SHELL_QUIET:-}" != "1" ]; then
                echo "bluebubbles-chatgpt-agent dev shell"
                echo "  Java:  $(java -version 2>&1 | head -n 1)"
                echo "  Node:  $(node --version) / npm $(npm --version)"
                echo "  Python: $(python --version)"
            ''
            + pkgs.lib.optionalString pkgs.stdenv.isDarwin ''
              echo "  App Clip helpers: swiftformat, xcbeautify"
              echo "  App Clip external deps: Xcode"
              echo "  App Store Connect CLI: asc ${devTools.ascCli.version}"
              echo "  LXMF/RNS: omitted on Darwin because nixpkgs packages pull Linux Bluetooth deps"
            ''
            + ''
                echo "  Try:   ./gradlew test"
              fi
            '';
          };
        }
      );

      packages = forAllSystems (
        system:
        let
          pkgs = pkgsFor system;
          devTools = devToolsFor pkgs;
          devToolsPackage = pkgs.buildEnv {
            name = "bluebubbles-chatgpt-agent-dev-tools";
            paths = devTools.tools;
          };
        in
        {
          dev-tools = devToolsPackage;
          default = devToolsPackage;
        }
        // buildPackagesFor system
      );

      formatter = forAllSystems (system: formatterFor (pkgsFor system));
    };
}
