"""Create/reuse an encrypted release key; plaintext keys never enter the repository.

Only trusted main-branch jobs call this script with a signing password. Bootstrap
needs Contents:write; prepare needs Contents:read. GitHub's secret is never logged.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request

ENCRYPTED_PATH = ".github/signing/release-key.p12.gpg"


def valid_password(value):
    return len(value) >= 32 and not any(c in value for c in "\r\n\x00")


def api_request(method="GET", payload=None, history=False):
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise RuntimeError("A valid GitHub repository is required for signing setup.")
    url = f"https://api.github.com/repos/{repository}/contents/{ENCRYPTED_PATH}"
    if history:
        url = f"https://api.github.com/repos/{repository}/commits?" + urllib.parse.urlencode({
            "path": ENCRYPTED_PATH, "sha": "main", "per_page": 1,
        })
    elif method == "GET":
        url += "?ref=main"
    request = urllib.request.Request(
        url, method=method,
        data=json.dumps(payload).encode() if payload is not None else None,
        headers={
            "Authorization": "Bearer " + os.environ["GITHUB_TOKEN"],
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if method == "GET" and not history and error.code == 404:
            return None
        raise RuntimeError(f"Signing-key repository request failed (HTTP {error.code}); no key was replaced.") from None


def run(command):
    result = subprocess.run(command, capture_output=True, timeout=120)
    if result.returncode:
        # Tool diagnostics can contain paths and user-supplied values; keep them private.
        raise RuntimeError(f"{Path(command[0]).name} could not prepare the release key. Check the signing secret; existing keys were not replaced.")


def get_ciphertext():
    local = Path(ENCRYPTED_PATH)
    if local.is_file():
        data = local.read_bytes()
    else:
        remote = api_request()
        if remote is None:
            return None
        data = base64.b64decode(remote["content"], validate=False)
    if not 512 <= len(data) <= 1024 * 1024:
        raise RuntimeError("The encrypted signing key has an invalid size.")
    return data


def bootstrap(password):
    if get_ciphertext() is not None:
        print("The encrypted release key already exists; it will not be regenerated.")
        return
    history = api_request(history=True)
    if not isinstance(history, list):
        raise RuntimeError("Could not verify release signing history; no key was generated.")
    if history:
        raise RuntimeError("The release key existed previously. Restore its encrypted file from Git history; it will not be replaced with a new key.")
    with tempfile.TemporaryDirectory(prefix="pocket-signing-") as directory:
        directory = Path(directory)
        password_file = directory / "password"
        password_file.write_text(password + "\n")
        password_file.chmod(0o600)
        keystore = directory / "release.p12"
        ciphertext = directory / "release.p12.gpg"
        gnupg = directory / "gnupg"
        gnupg.mkdir(mode=0o700)
        run([
            "keytool", "-genkeypair", "-noprompt", "-alias", "pocket-agent",
            "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
            "-storetype", "PKCS12", "-keystore", str(keystore),
            "-storepass:file", str(password_file), "-keypass:file", str(password_file),
            "-dname", "CN=Pocket Agent Android Release",
        ])
        run([
            "gpg", "--homedir", str(gnupg), "--no-options", "--batch", "--yes",
            "--no-autostart", "--no-symkey-cache", "--pinentry-mode", "loopback",
            "--passphrase-file", str(password_file), "--symmetric", "--cipher-algo", "AES256",
            "--s2k-mode", "3", "--s2k-digest-algo", "SHA512", "--s2k-count", "65011712",
            "--output", str(ciphertext), str(keystore),
        ])
        api_request("PUT", {
            "message": "Initialize encrypted Android release signing key",
            "branch": "main",
            "content": base64.b64encode(ciphertext.read_bytes()).decode("ascii"),
        })
    print("Created the encrypted release key. Keep the signing password in your password manager.")


def prepare(password):
    ciphertext = get_ciphertext()
    if ciphertext is None:
        raise RuntimeError("Release signing has not been initialized. Re-run the workflow on main.")
    directory = Path(tempfile.mkdtemp(prefix="pocket-signing-", dir=os.environ.get("RUNNER_TEMP")))
    directory.chmod(0o700)
    password_file = directory / "password"
    password_file.write_text(password + "\n")
    password_file.chmod(0o600)
    encrypted = directory / "release.p12.gpg"
    encrypted.write_bytes(ciphertext)
    keystore = directory / "release.p12"
    gnupg = directory / "gnupg"
    gnupg.mkdir(mode=0o700)
    try:
        run([
            "gpg", "--homedir", str(gnupg), "--no-options", "--batch", "--yes",
            "--no-autostart", "--no-symkey-cache", "--pinentry-mode", "loopback",
            "--passphrase-file", str(password_file), "--output", str(keystore), "--decrypt", str(encrypted),
        ])
        keystore.chmod(0o600)
        run(["keytool", "-list", "-alias", "pocket-agent", "-keystore", str(keystore),
             "-storepass:file", str(password_file)])
        with open(os.environ["GITHUB_ENV"], "a") as output:
            output.write(f"POCKET_SIGNING_STORE_FILE={keystore}\n")
            output.write(f"POCKET_SIGNING_PASSWORD_FILE={password_file}\n")
            output.write("POCKET_SIGNING_READY=true\n")
    except Exception:
        import shutil
        shutil.rmtree(directory, ignore_errors=True)
        raise
    print("The persistent release signing key is ready for this job.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("bootstrap", "prepare", "cleanup"))
    args = parser.parse_args()
    if args.mode == "cleanup":
        import shutil
        root = Path(os.environ.get("RUNNER_TEMP", tempfile.gettempdir()))
        for directory in root.glob("pocket-signing-*"):
            if directory.is_dir() and not directory.is_symlink():
                shutil.rmtree(directory, ignore_errors=True)
        return
    if os.environ.get("GITHUB_REF") != "refs/heads/main" or os.environ.get("GITHUB_EVENT_NAME") not in ("push", "workflow_dispatch"):
        raise RuntimeError("Release signing runs only for trusted main-branch builds.")
    password = os.environ.get("ANDROID_SIGNING_PASSWORD", "")
    if not valid_password(password):
        raise RuntimeError("Set ANDROID_SIGNING_PASSWORD to a unique, randomly generated, single-line password of at least 32 characters.")
    (bootstrap if args.mode == "bootstrap" else prepare)(password)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(str(error))
        raise SystemExit(1) from None
