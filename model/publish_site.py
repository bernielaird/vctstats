"""Upload the website's data file to a GitHub Release, then tell Render to redeploy.

Run after export_site.py (update_site.ps1 does both). From the project root:
    .venv\\Scripts\\python model\\publish_site.py
    .venv\\Scripts\\python model\\publish_site.py --no-deploy     upload only

The file is gzipped and replaces the asset on the release tagged site-data (created if missing), so its
download URL never changes:
    https://github.com/<repo>/releases/download/site-data/vct_site.sqlite.gz
The website's vct/fetch_data.py downloads that URL during Render's build.

Needs in .env (or the environment):
    GITHUB_TOKEN        fine-grained token with Contents: read and write on the repo
    RENDER_DEPLOY_HOOK  Render > the web service > Settings > Deploy Hook (optional; skips redeploy if unset)
"""
import argparse
from contextlib import closing
import gzip
import json
import os
import shutil
import sqlite3
import sys
import urllib.error
import urllib.parse
import urllib.request

REPO = "bernielaird/vctstats"
TAG = "site-data"
ASSET = "vct_site.sqlite.gz"


def load_env(path=".env"):
    """Values from .env, without overriding real environment variables (same rule as LocalConfig.java)."""
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def github(method, url, token, data=None, content_type="application/json"):
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "Content-Type": content_type,
    })
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            body = r.read()
            return json.loads(body) if body else None
    except urllib.error.HTTPError as e:
        if e.code == 404 and method == "GET":
            return None
        sys.exit(f"GitHub {method} {url} failed: {e.code} {e.read().decode(errors='replace')[:500]}")


def check_export(path):
    """Refuse to publish a file the website can't use."""
    with closing(sqlite3.connect(f"file:{path}?mode=ro", uri=True)) as c:
        meta = dict(c.execute("SELECT key, value FROM meta"))
        for table in ("matches", "rounds", "actions", "leaderboard", "plays"):
            if not c.execute(f"SELECT 1 FROM {table} LIMIT 1").fetchone():
                sys.exit(f"{path}: table {table} is empty; not publishing.")
    return meta


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--file", default="data/exports/vct_site.sqlite")
    ap.add_argument("--no-deploy", action="store_true", help="upload, but don't trigger Render")
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    load_env()
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        sys.exit("No GITHUB_TOKEN in .env or the environment (see the top of this file).")

    meta = check_export(args.file)
    gz = args.file + ".gz"
    with open(args.file, "rb") as src, gzip.open(gz, "wb", compresslevel=9) as dst:
        shutil.copyfileobj(src, dst)
    print(f"{meta['matches']} matches, built {meta['built']}: {os.path.getsize(gz) / 1e6:.1f} MB gzipped")

    api = f"https://api.github.com/repos/{REPO}"
    release = github("GET", f"{api}/releases/tags/{TAG}", token)
    if not release:
        release = github("POST", f"{api}/releases", token, json.dumps({
            "tag_name": TAG, "name": "Website data",
            "body": "Data file for the website's /vct/ pages. Replaced by model/publish_site.py on every update.",
        }).encode())
        print(f"Created release {TAG}")
    for asset in release.get("assets", []):
        if asset["name"] == ASSET:
            github("DELETE", f"{api}/releases/assets/{asset['id']}", token)
    upload = release["upload_url"].split("{")[0] + "?" + urllib.parse.urlencode({"name": ASSET})
    with open(gz, "rb") as f:
        github("POST", upload, token, f.read(), "application/gzip")
    print(f"Uploaded https://github.com/{REPO}/releases/download/{TAG}/{ASSET}")

    hook = os.environ.get("RENDER_DEPLOY_HOOK")
    if args.no_deploy or not hook:
        print("Not redeploying" + ("" if args.no_deploy else " (no RENDER_DEPLOY_HOOK)") + ".")
        return
    with urllib.request.urlopen(urllib.request.Request(hook, method="POST"), timeout=60) as r:
        print(f"Render redeploy triggered ({r.status}). The site updates when the build finishes.")


if __name__ == "__main__":
    main()
