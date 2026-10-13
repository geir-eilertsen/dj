#!/usr/bin/env python3
"""Publishes docker/README.md as the Docker Hub overview of geireilertsen/dj.

Editing a repository's description needs a token with "Read, Write, Delete" access: pass it
as DOCKERHUB_TOKEN. Without it, the login saved by `docker login` is used (a push-only
"Read & Write" token gets 403). The token is never printed.
"""
import base64
import json
import os
import pathlib
import urllib.error
import urllib.request

REPO = "geireilertsen/dj"
SHORT = "AI DJ for Lyrion Music Server: picks what plays next, follows your moods, explains every song."

root = pathlib.Path(__file__).resolve().parent.parent
overview = (root / "docker" / "README.md").read_text(encoding="utf-8")

if os.environ.get("DOCKERHUB_TOKEN"):
    user, secret = REPO.split("/")[0], os.environ["DOCKERHUB_TOKEN"]
else:
    config = json.loads(pathlib.Path(os.path.expanduser("~/.docker/config.json")).read_text())
    auth = next(v["auth"] for k, v in config.get("auths", {}).items() if "docker.io" in k)
    user, secret = base64.b64decode(auth).decode().split(":", 1)


def call(method, url, body, token=None):
    request = urllib.request.Request(url, data=json.dumps(body).encode(), method=method,
                                     headers={"Content-Type": "application/json"})
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request) as response:
            return json.load(response)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {url}: HTTP {e.code}: {e.read().decode(errors='replace')[:300]}")


token = call("POST", "https://hub.docker.com/v2/auth/token", {"identifier": user, "secret": secret})["access_token"]
result = call("PATCH", f"https://hub.docker.com/v2/repositories/{REPO}/",
              {"description": SHORT, "full_description": overview}, token)
print("Updated", REPO, "-", len(result.get("full_description", "")), "characters of overview")
