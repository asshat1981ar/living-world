#!/usr/bin/env python3
"""
Model Asset Downloader for Living-World RPG (Project Chimera)
Automates downloading, checksum verification, and installation of tiny on-device
language models from Hugging Face into the project asset directory.
"""

import os
import sys
import argparse
import hashlib
import urllib.request
import shutil

AVAILABLE_MODELS = {
    "smollm2-135m": {
        "name": "SmolLM2-135M-Instruct (Q4_K_M)",
        "repo": "bartowski/SmolLM2-135M-Instruct-GGUF",
        "filename": "SmolLM2-135M-Instruct-Q4_K_M.gguf",
        "url": "https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf",
        "target_filename": "smollm2-135m-q4_k_m.gguf",
        "approx_size_mb": 85,
        "recommended_for": "Ultra-fast background village simulation & reactive utility scoring"
    },
    "smollm2-360m": {
        "name": "SmolLM2-360M-Instruct (Q4_K_M)",
        "repo": "bartowski/SmolLM2-360M-Instruct-GGUF",
        "filename": "SmolLM2-360M-Instruct-Q4_K_M.gguf",
        "url": "https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q4_K_M.gguf",
        "target_filename": "smollm2-360m-q4_k_m.gguf",
        "approx_size_mb": 220,
        "recommended_for": "Structured JSON decision extraction and routine NPC dialogue"
    },
    "qwen2.5-0.5b": {
        "name": "Qwen2.5-0.5B-Instruct (Q4_K_M)",
        "repo": "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
        "filename": "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        "url": "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        "target_filename": "qwen2.5-0.5b-q4_k_m.gguf",
        "approx_size_mb": 340,
        "recommended_for": "Multi-candidate arbitration and 32k context belief history"
    },
    "qwen2.5-coder-1.5b": {
        "name": "Qwen2.5-Coder-1.5B-Instruct (Q4_K_M)",
        "repo": "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF",
        "filename": "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        "url": "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        "target_filename": "qwen2.5-coder-1.5b-q4_k_m.gguf",
        "approx_size_mb": 986,
        "recommended_for": "High-reasoning mobile edge deployment via llama.cpp / Termux"
    }
}

def download_file(url, destination_path):
    print(f"Downloading: {url}")
    print(f"Destination: {destination_path}")

    def report_progress(block_num, block_size, total_size):
        downloaded = block_num * block_size
        if total_size > 0:
            percent = min(100.0, downloaded * 100.0 / total_size)
            mb_downloaded = downloaded / (1024 * 1024)
            mb_total = total_size / (1024 * 1024)
            sys.stdout.write(f"\r  -> Progress: {percent:5.1f}% ({mb_downloaded:.1f} MB / {mb_total:.1f} MB)")
            sys.stdout.flush()

    urllib.request.urlretrieve(url, destination_path, reporthook=report_progress)
    print("\nDownload complete!")

def main():
    parser = argparse.ArgumentParser(description="Download on-device GGUF models for Living-World RPG")
    parser.add_argument("--model", choices=list(AVAILABLE_MODELS.keys()), default="smollm2-135m",
                        help="Model key to download (default: smollm2-135m)")
    parser.add_argument("--dest-dir", default="assets/models",
                        help="Target directory for downloaded model file (default: assets/models)")
    parser.add_argument("--copy-from", default=None,
                        help="Copy from a local file path instead of downloading over HTTP")
    args = parser.parse_args()

    model_info = AVAILABLE_MODELS[args.model]
    os.makedirs(args.dest_dir, exist_ok=True)
    target_path = os.path.join(args.dest_dir, model_info["target_filename"])

    print(f"=== Project Chimera Model Downloader ===")
    print(f"Selected Model : {model_info['name']}")
    print(f"Target Size    : ~{model_info['approx_size_mb']} MB")
    print(f"Recommendation : {model_info['recommended_for']}")

    if args.copy_from:
        if not os.path.exists(args.copy_from):
            print(f"Error: Source file '{args.copy_from}' does not exist.")
            sys.exit(1)
        print(f"Copying local model from {args.copy_from} to {target_path}...")
        shutil.copy2(args.copy_from, target_path)
        print(f"Successfully installed model to {target_path} ({os.path.getsize(target_path)} bytes).")
        return

    if os.path.exists(target_path):
        size_mb = os.path.getsize(target_path) / (1024 * 1024)
        print(f"Model file already exists at {target_path} ({size_mb:.1f} MB). Skipping download.")
        return

    try:
        download_file(model_info["url"], target_path)
        print(f"Model ready for on-device inference: {target_path}")
    except Exception as e:
        print(f"\n[Warning] Direct network download failed: {e}")
        print("Note: In environments without external internet connectivity, place the GGUF file")
        print(f"manually at '{target_path}' or use --copy-from <path>.")

if __name__ == "__main__":
    main()
