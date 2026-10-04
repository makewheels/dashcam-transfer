"""Exchange GitHub OIDC for a short-lived, release-only Infisical identity."""
import os
import secrets
from urllib.parse import urlencode

import requests

KEYS = {"DASHCAM_APP_TOKEN", "DASHCAM_API_URL", "DASHCAM_KEYSTORE_BASE64", "DASHCAM_KEYSTORE_PASSWORD",
        "OSS_BUCKET", "OSS_ENDPOINT", "OSS_REGION", "RELEASE_ACCESS_KEY_ID", "RELEASE_ACCESS_KEY_SECRET"}


def main():
    url = os.environ["ACTIONS_ID_TOKEN_REQUEST_URL"] + "&" + urlencode({"audience": "dashcam-transfer-release"})
    result = requests.get(url, headers={"Authorization": "Bearer " + os.environ["ACTIONS_ID_TOKEN_REQUEST_TOKEN"]}, timeout=30)
    result.raise_for_status()
    jwt = result.json()["value"]
    domain = os.environ["INFISICAL_DOMAIN"].rstrip("/")
    response = requests.post(domain + "/api/v1/auth/oidc-auth/login",
                             json={"identityId": os.environ["INFISICAL_IDENTITY_ID"], "jwt": jwt}, timeout=30)
    if not response.ok:
        try:
            detail = response.json()
            message = str(detail.get("message", detail.get("error", "unspecified")))
        except ValueError:
            message = "non-JSON response; content-type=" + response.headers.get("Content-Type", "unknown")
        message = message.replace(jwt, "[redacted]").replace(os.environ["ACTIONS_ID_TOKEN_REQUEST_TOKEN"], "[redacted]")
        raise SystemExit(f"Infisical OIDC exchange failed ({response.status_code}): {message[:300]}")
    token = response.json()["accessToken"]
    print("::add-mask::" + token)
    response = requests.get(domain + "/api/v3/secrets/raw", headers={"Authorization": "Bearer " + token},
                            params={"workspaceId": os.environ["INFISICAL_PROJECT_ID"], "environment": "prod", "secretPath": "/"}, timeout=30)
    if not response.ok:
        raise SystemExit(f"Release credentials unavailable ({response.status_code})")
    values = {x["secretKey"]: x["secretValue"] for x in response.json()["secrets"]}
    if not KEYS <= values.keys():
        raise SystemExit("Required release credential keys missing")
    with open(os.environ["GITHUB_ENV"], "a") as env:
        for key in sorted(KEYS):
            value = values[key]
            for line in value.splitlines():
                print("::add-mask::" + line)
            delimiter = "secret_" + secrets.token_hex(16)
            env.write(f"{key}<<{delimiter}\n{value}\n{delimiter}\n")


if __name__ == "__main__":
    main()
