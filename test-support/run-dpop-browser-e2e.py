"""Isolated Keycloak 26.8.1 + real browser + BlueChat Spring security verification.

Requires Docker, the project Nix shell, and `npm exec -- playwright install chromium`
in frontend/. Does not read production credentials or change a live Keycloak realm.
"""

import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
IMAGE = (
    "registry.breland.io/keycloak/keycloak:"
    "26.8.1-acme-theme-cfacaccc0a05480bdabcc435ba0d611f465d5114-72"
    "@sha256:ccf1b50203f34a525f4762c5a7f2646a50caaf730826d841db0cbb995dac1c2e"
)


def main():
    directory = Path(tempfile.mkdtemp(prefix="bluechat-dpop-e2e-", dir="/private/tmp" if Path("/private/tmp").exists() else None))
    name = "bluechat-dpop-keycloak-" + uuid.uuid4().hex[:12]
    password = secrets.token_urlsafe(24)
    realm = {
        "realm": "bluechat-dpop-test", "enabled": True, "sslRequired": "none",
        "registrationAllowed": True, "accessTokenLifespan": 120,
        "clients": [{
            "clientId": "bbagent-web", "enabled": True, "publicClient": True,
            "standardFlowEnabled": True, "directAccessGrantsEnabled": False,
            "redirectUris": ["http://localhost:15174/*"],
            "webOrigins": ["http://localhost:15174"],
            "attributes": {"pkce.code.challenge.method": "S256", "dpop.bound.access.tokens": "false",
                "post.logout.redirect.uris": "http://localhost:15174"},
        }],
        "users": [{
            "username": "dpop-test", "enabled": True, "emailVerified": True,
            "email": "dpop-test@example.invalid", "firstName": "DPoP", "lastName": "Test",
            "credentials": [{"type": "password", "value": password, "temporary": False}],
        }],
    }
    fixture = directory / "realm.json"
    fixture.write_text(json.dumps(realm))
    fixture.chmod(0o600)
    issuer = "http://localhost:18181/realms/bluechat-dpop-test"
    try:
        print("Starting isolated Keycloak 26.8.1 (DPoP optional).", flush=True)
        subprocess.run([
            "docker", "run", "--detach", "--name", name, "--user=0",
            "--publish", "127.0.0.1:18181:8080", "--mount",
            f"type=bind,source={directory},target=/opt/keycloak/data/import,readonly",
            IMAGE, "start-dev", "--db=dev-file", "--import-realm", "--hostname=http://localhost:18181",
        ], check=True, stdout=subprocess.DEVNULL)
        for attempt in range(120):
            try:
                with urllib.request.urlopen(issuer + "/.well-known/openid-configuration", timeout=2) as response:
                    if response.status == 200:
                        break
            except Exception:
                if attempt % 10 == 9:
                    running = subprocess.run(["docker", "inspect", "--format", "{{.State.Running}}", name],
                        capture_output=True, text=True, check=True)
                    if running.stdout.strip() != "true":
                        subprocess.run(["docker", "logs", "--tail", "20", name])
                        raise RuntimeError("Isolated Keycloak exited before readiness")
                time.sleep(1)
        else:
            subprocess.run(["docker", "logs", "--tail", "45", name])
            raise RuntimeError("Isolated Keycloak did not become ready")
        print("Testing the real browser, Keycloak, generated API client, and Spring verifier.", flush=True)
        environment = dict(os.environ, CI="true", BBAGENT_NIX_SHELL_QUIET="1",
            BLUECHAT_DPOP_E2E_ISSUER=issuer, BLUECHAT_DPOP_E2E_PASSWORD=password)
        result = subprocess.run(["nix", "develop", "--command", "./gradlew", "spotlessApply",
            "test", "--tests", "*DpopBrowserE2eTest"], cwd=ROOT, env=environment)
        if result.returncode:
            raise SystemExit(result.returncode)
    finally:
        subprocess.run(["docker", "rm", "--force", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        shutil.rmtree(directory)
        print("Removed the isolated Keycloak and temporary test credentials.", flush=True)


if __name__ == "__main__":
    main()
