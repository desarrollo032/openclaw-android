#!/usr/bin/env python3
import argparse
import json
import lzma
import os
import shutil
import tarfile
import tempfile
import urllib.request
import urllib.error

NPM_VERSION = "11.14.1"
NPM_REGISTRIES = [
    "https://registry.npmjs.org",
    "https://registry.npmmirror.com"
]


def download_url(url: str, dest: str) -> None:
    print(f"Downloading {url}")
    req = urllib.request.Request(url, headers={"User-Agent": "OpenClaw npm fetch/1.0"})
    with urllib.request.urlopen(req, timeout=60) as response, open(dest, "wb") as out:
        shutil.copyfileobj(response, out)


def fetch_npm_tarball_url(version: str) -> str:
    candidates = [f"npm/{version}", "npm/latest"]
    for registry in NPM_REGISTRIES:
        for candidate in candidates:
            package_url = f"{registry}/{candidate}"
            print(f"Querying npm metadata: {package_url}")
            try:
                req = urllib.request.Request(package_url, headers={"User-Agent": "OpenClaw npm fetch/1.0", "Accept": "application/json"})
                with urllib.request.urlopen(req, timeout=30) as response:
                    metadata = json.load(response)
                tarball = metadata.get("dist", {}).get("tarball")
                if tarball:
                    print(f"Found tarball: {tarball}")
                    return tarball
            except urllib.error.HTTPError as exc:
                print(f"HTTP {exc.code} fetching {package_url}")
            except Exception as exc:
                print(f"Failed to fetch metadata from {package_url}: {exc}")
    raise RuntimeError(f"Could not resolve npm@{version} tarball URL")


def normalize_member_name(name: str) -> str:
    return name.lstrip("./").replace('\\', '/')


def add_npm_to_payload(payload_path: str, npm_tarball_path: str, output_path: str) -> None:
    with tempfile.TemporaryDirectory() as tmpdir:
        extracted = os.path.join(tmpdir, "npm-package")
        os.makedirs(extracted, exist_ok=True)
        with tarfile.open(npm_tarball_path, 'r:gz') as npm_tar:
            npm_tar.extractall(extracted)
        package_root = os.path.join(extracted, 'package')
        if not os.path.isdir(package_root):
            raise RuntimeError("npm tarball did not contain a package/ root directory")

        with tarfile.open(payload_path, 'r:xz') as old_tar, tarfile.open(output_path, 'w:xz', preset=6) as new_tar:
            for member in old_tar.getmembers():
                name = normalize_member_name(member.name)
                if name.startswith('lib/node_modules/npm/'):
                    print(f"Skipping existing payload npm member: {name}")
                    continue
                fileobj = old_tar.extractfile(member) if member.isfile() else None
                new_tar.addfile(member, fileobj)
                if fileobj is not None:
                    fileobj.close()

            for root, dirs, files in os.walk(package_root):
                rel_root = os.path.relpath(root, package_root)
                if rel_root == '.':
                    rel_root = ''
                arc_root = 'lib/node_modules/npm' if rel_root == '' else f'lib/node_modules/npm/{rel_root}'
                dirinfo = tarfile.TarInfo(arc_root)
                dirinfo.type = tarfile.DIRTYPE
                dirinfo.mode = 0o755
                dirinfo.mtime = int(os.path.getmtime(root))
                new_tar.addfile(dirinfo)
                for filename in files:
                    fullpath = os.path.join(root, filename)
                    arcname = f"{arc_root}/{filename}"
                    new_tar.add(fullpath, arcname=arcname)
    print(f"Wrote updated payload with npm to: {output_path}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Download npm@11.14.1 and inject it into payload-v2.tar.xz")
    parser.add_argument("--payload", default="android/app/src/main/assets/payload-v2.tar.xz", help="Path to payload tar.xz")
    parser.add_argument("--output", default="android/app/src/main/assets/payload-v2-with-npm.tar.xz", help="Resulting payload tar.xz path")
    parser.add_argument("--npm-version", default=NPM_VERSION, help="npm version to download")
    args = parser.parse_args()

    if not os.path.isfile(args.payload):
        raise FileNotFoundError(f"Payload not found: {args.payload}")

    with tempfile.TemporaryDirectory() as tmpdir:
        tarball_path = os.path.join(tmpdir, "npm.tgz")
        tarball_url = fetch_npm_tarball_url(args.npm_version)
        download_url(tarball_url, tarball_path)
        add_npm_to_payload(args.payload, tarball_path, args.output)


if __name__ == '__main__':
    main()
