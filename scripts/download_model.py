#!/usr/bin/env python3
"""
Model Asset Downloader for Living-World RPG (Project Chimera)

Downloads tiny on-device GGUF models from Hugging Face and installs them only if they are exactly the file the
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
    "smollm2-135m": {
        "name": "SmolLM2-135M-Instruct (Q4_K_M)",
        "repo": "bartowski/SmolLM2-135M-Instruct-GGUF",
        "revision": "09816acd5d99df7be770d85ea30822623dab342c",
        "filename": "SmolLM2-135M-Instruct-Q4_K_M.gguf",
        "target_filename": "smollm2-135m-q4_k_m.gguf",
        "size_bytes": 105454432,
        "sha256": "2e8040ceae7815abe0dcb3540b9995eaa1fa0d2ca9e797d0a635ae4433c68c2d",
        "recommended_for": "Ultra-fast background village simulation & reactive utility scoring",
    },
    "smollm2-360m": {
        "name": "SmolLM2-360M-Instruct (Q4_K_M)",
        "repo": "bartowski/SmolLM2-360M-Instruct-GGUF",
        "revision": "7be6f65f1db715fe5dc5a4634c0d459b4eed42ec",
        "filename": "SmolLM2-360M-Instruct-Q4_K_M.gguf",
        "target_filename": "smollm2-360m-q4_k_m.gguf",
        "size_bytes": 270590880,
        "sha256": "2fa3f013dcdd7b99f9b237717fa0b12d75bbb89984cc1274be1471a465bac9c2",
        "recommended_for": "Structured JSON decision extraction and routine NPC dialogue",
    },
    "qwen2.5-0.5b": {
        "name": "Qwen2.5-0.5B-Instruct (Q4_K_M)",
        "repo": "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
        "revision": "9217f5db79a29953eb74d5343926648285ec7e67",
        "filename": "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        "target_filename": "qwen2.5-0.5b-q4_k_m.gguf",
        "size_bytes": 491400032,
        "sha256": "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
        "recommended_for": "Multi-candidate arbitration and 32k context belief history",
    },
    "qwen2.5-coder-1.5b": {
        "name": "Qwen2.5-Coder-1.5B-Instruct (Q4_K_M)",
        "repo": "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF",
        "revision": "f86cb2c1fa58255f8052cc32aeede1b7482d4361",
        "filename": "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        "target_filename": "qwen2.5-coder-1.5b-q4_k_m.gguf",
        "size_bytes": 1117320768,
        "sha256": "cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046",
        "recommended_for": "High-reasoning mobile edge deployment via llama.cpp / Termux",
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
    parser = argparse.ArgumentParser(description="Download and verify on-device GGUF models for Living-World RPG")
    parser.add_argument("--model", choices=list(AVAILABLE_MODELS), default="smollm2-135m",
                        help="Model key to install (default: smollm2-135m)")
    parser.add_argument("--dest-dir", default="assets/models", help="Target directory (default: assets/models)")
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
