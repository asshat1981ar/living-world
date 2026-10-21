#!/usr/bin/env python3
"""
Model Asset Downloader for Living-World RPG (Project Chimera)

Downloads the on-device dialogue model (a LiteRT-LM .litertlm file) from Hugging Face and installs them only if they are exactly the file the
catalog promises: pinned to a repository commit, with an exact size and sha256. Nothing is ever left half-written
(download goes to <name>.part and is renamed only after verification), an existing file is verified rather than
trusted, and any failure exits non-zero. The table below must match ModelAssetCatalog in
game-ai/.../ondevice/ModelAssetManager.kt (tools/test_download_model.py checks this).
"""

import argparse
import hashlib
import os
import shutil
import sys
import urllib.parse
import urllib.request

CHUNK = 1024 * 1024

# size_bytes and sha256 are Hugging Face's published values (x-linked-size / x-linked-etag at `revision`).
AVAILABLE_MODELS = {
    "qwen3-0.6b": {
        "name": "Qwen3-0.6B for LiteRT-LM (int4 weights)",
        "repo": "litert-community/Qwen3-0.6B",
        "revision": "e6687177e00897731e7716267eebd4eba0dde7c2",
        "filename": "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
        "target_filename": "qwen3-0.6b-int4.litertlm",
        "size_bytes": 344671744,
        "sha256": "03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856",
        "recommended_for": "Default on-device NPC dialogue model (fits mid-range phones)",
    },
    "qwen3-1.7b": {
        "name": "Qwen3-1.7B for LiteRT-LM (int4 weights)",
        "repo": "litert-community/Qwen3-1.7B",
        "revision": "79bf0667b32ae025b972e5fe555fe72ca6e8816c",
        "filename": "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
        "target_filename": "qwen3-1.7b-int4.litertlm",
        "size_bytes": 977184032,
        "sha256": "2a72330b504a6596d53a2c00923c57173b10f1b83e6a541213ffec08d1fc5de8",
        "recommended_for": "Optional higher-quality dialogue model for phones with plenty of free memory",
    },
}


class IntegrityError(Exception):
    pass


def model_url(model):
    return f"https://huggingface.co/{model['repo']}/resolve/{model['revision']}/{model['filename']}"


def check_url(url):
    """Only https on huggingface.co (or its CDN subdomains) is ever fetched."""
    parts = urllib.parse.urlsplit(url)
    host = (parts.hostname or "").lower()
    if parts.scheme != "https" or not (host == "huggingface.co" or host.endswith(".huggingface.co")):
        raise IntegrityError(f"refusing to download from {url!r}: only https://huggingface.co is allowed")


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_file(path, size_bytes, sha256):
    """Raises IntegrityError unless `path` has exactly the promised size and sha256."""
    actual = os.path.getsize(path)
    if actual != size_bytes:
        raise IntegrityError(f"size {actual} does not match expected {size_bytes}")
    digest = sha256_of(path)
    if digest.lower() != sha256.lower():
        raise IntegrityError(f"sha256 {digest} does not match expected {sha256}")


def install_verified(source_path, dest_path, size_bytes, sha256):
    """Verify `source_path`, then move it into place atomically; `source_path` is removed on failure."""
    try:
        verify_file(source_path, size_bytes, sha256)
    except IntegrityError:
        if os.path.exists(source_path):
            os.remove(source_path)
        raise
    os.replace(source_path, dest_path)


def download_verified(url, dest_path, size_bytes, sha256, open_url=urllib.request.urlopen, check=True, progress=None):
    """Stream `url` to `dest_path` + '.part', verify size and sha256, then rename. Never leaves a partial file."""
    if check:
        check_url(url)
    part = dest_path + ".part"
    try:
        digest = hashlib.sha256()
        received = 0
        with open_url(url) as response, open(part, "wb") as out:
            while True:
                chunk = response.read(CHUNK)
                if not chunk:
                    break
                received += len(chunk)
                if received > size_bytes:
                    raise IntegrityError(f"received more than the expected {size_bytes} bytes")
                digest.update(chunk)
                out.write(chunk)
                if progress:
                    progress(received, size_bytes)
        if received != size_bytes:
            raise IntegrityError(f"received {received} bytes, expected {size_bytes}")
        if digest.hexdigest().lower() != sha256.lower():
            raise IntegrityError(f"sha256 {digest.hexdigest()} does not match expected {sha256}")
        os.replace(part, dest_path)
    except BaseException:
        if os.path.exists(part):
            os.remove(part)
        raise


def _progress(received, total):
    sys.stdout.write(f"\r  -> {received * 100.0 / total:5.1f}% ({received / 1048576:.1f} / {total / 1048576:.1f} MB)")
    sys.stdout.flush()


def main(argv=None):
    parser = argparse.ArgumentParser(description="Download and verify the on-device dialogue model for Living-World RPG")
    parser.add_argument("--model", choices=list(AVAILABLE_MODELS), default="qwen3-0.6b",
                        help="Model key to install (default: qwen3-0.6b)")
    parser.add_argument("--dest-dir", default="models", help="Target directory (default: models, gitignored)")
    parser.add_argument("--copy-from", default=None, help="Install (after verifying) a local file instead of downloading")
    parser.add_argument("--verify-only", action="store_true", help="Only check the installed file; never download")
    args = parser.parse_args(argv)

    model = AVAILABLE_MODELS[args.model]
    os.makedirs(args.dest_dir, exist_ok=True)
    target = os.path.join(args.dest_dir, model["target_filename"])
    print(f"=== Project Chimera Model Downloader ===\nModel  : {model['name']}\nSize   : {model['size_bytes']} bytes\nTarget : {target}")

    if os.path.exists(target):
        try:
            verify_file(target, model["size_bytes"], model["sha256"])
            print("Installed file verified (size and sha256 match). Nothing to do.")
            return 0
        except IntegrityError as e:
            print(f"Installed file is NOT valid ({e}).")
            if args.verify_only:
                return 1
            print("Replacing it.")
    elif args.verify_only:
        print("Not installed.")
        return 1

    try:
        if args.copy_from:
            if not os.path.isfile(args.copy_from):
                print(f"Error: source file '{args.copy_from}' does not exist.")
                return 1
            staging = target + ".part"
            shutil.copyfile(args.copy_from, staging)
            install_verified(staging, target, model["size_bytes"], model["sha256"])
        else:
            download_verified(model_url(model), target, model["size_bytes"], model["sha256"], progress=_progress)
            print()
    except (IntegrityError, OSError) as e:
        print(f"\nError: model was NOT installed: {e}")
        return 1
    except Exception as e:  # network and protocol failures
        print(f"\nError: download failed: {e}")
        return 1

    print(f"Model installed and verified: {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
