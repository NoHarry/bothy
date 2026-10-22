#!/usr/bin/env python3
"""Fetch the voice session's on-device assets (Settings → 语音, local recognizer).

Downloads three pinned GitHub release archives of k2-fsa/sherpa-onnx, checks their SHA-256 and extracts only
what the app needs, into the directories the app builds from:

  sherpa-onnx v1.13.8 Android natives  → agent-voice/src/main/jniLibs/<abi>/
      libsherpa-onnx-jni.so, libonnxruntime.so (arm64-v8a by default; most tablets and phones are arm64)
  Zipformer Small zh-en streaming model → bothy-sample/src/main/assets/voice-models/<model>/
      encoder/joiner INT8, decoder FP32, tokens.txt (about 57 MiB of a 458 MB archive)
  KWS Zipformer WenetSpeech 3.3M (mobile) → bothy-sample/src/main/assets/voice-models/<kws model>/
      the overlay's wake word 你好小博: encoder/joiner INT8, decoder, tokens.txt (about 4.6 MiB), plus a
      keywords.txt written here with the wake phrase (the archive's own lists other phrases)

These files are committed (owner 2026-10-12), so a clone builds a working local recognizer and wake word as it
is; run this to change a version or ABI, or to check the committed files against the pinned archives, then
rebuild the Sample. Without them the app still builds and Settings → 语音 shows the local recognizer as not
installed. Archives are cached in tools/voice/.cache (git-ignored).

Usage:
  python workspace/apps/android/tools/voice/fetch_voice_assets.py [--abi arm64-v8a] [--abi armeabi-v7a]
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
import tarfile
import urllib.request
from pathlib import Path, PurePosixPath

ANDROID_ROOT = Path(__file__).resolve().parents[2]
CACHE = Path(__file__).resolve().parent / ".cache"

NATIVE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2"
NATIVE_SHA256 = "2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44"
NATIVE_LIBS = ("libsherpa-onnx-jni.so", "libonnxruntime.so")
NATIVE_DEST = ANDROID_ROOT / "agent-voice" / "src" / "main" / "jniLibs"

MODEL_NAME = "sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16"
MODEL_URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/{MODEL_NAME}.tar.bz2"
MODEL_SHA256 = "2b7c63322b32e5e0f2526043a1103366119ca58dd615cd7105a37c01db9553d7"
MODEL_FILES = (
    "encoder-epoch-99-avg-1.int8.onnx",
    "decoder-epoch-99-avg-1.onnx",
    "joiner-epoch-99-avg-1.int8.onnx",
    "tokens.txt",
)
MODEL_DEST = ANDROID_ROOT / "bothy-sample" / "src" / "main" / "assets" / "voice-models" / MODEL_NAME

KWS_NAME = "sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile"
KWS_URL = f"https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/{KWS_NAME}.tar.bz2"
KWS_SHA256 = "b812a043aef628a6915f89cb9a94e55f8e87e89ff904b516f822d7e0a3e6de2b"
KWS_FILES = (
    "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
    "decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
    "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
    "tokens.txt",
)
KWS_DEST = MODEL_DEST.parent / KWS_NAME
# Same phrase as agent-voice's DEFAULT_WAKE_TOKENS / DEFAULT_WAKE_PHRASE (pinyin tokens, then the phrase).
KWS_KEYWORDS = "n ǐ h ǎo x iǎo b ó @你好小博\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--abi", action="append", help="ABI to install (repeatable); default arm64-v8a")
    parser.add_argument("--skip-native", action="store_true", help="do not install the native libraries")
    parser.add_argument("--skip-model", action="store_true", help="do not install the recognizer model")
    parser.add_argument("--skip-kws", action="store_true", help="do not install the wake-word model")
    args = parser.parse_args()
    abis = args.abi or ["arm64-v8a"]
    try:
        if not args.skip_native:
            archive = download(NATIVE_URL, NATIVE_SHA256)
            for abi in abis:
                wanted = {f"{abi}/{name}": NATIVE_DEST / abi / name for name in NATIVE_LIBS}
                extract(archive, wanted, match_suffix=True)
        if not args.skip_model:
            archive = download(MODEL_URL, MODEL_SHA256)
            wanted = {f"{MODEL_NAME}/{name}": MODEL_DEST / name for name in MODEL_FILES}
            extract(archive, wanted, match_suffix=False)
        if not args.skip_kws:
            archive = download(KWS_URL, KWS_SHA256)
            wanted = {f"{KWS_NAME}/{name}": KWS_DEST / name for name in KWS_FILES}
            extract(archive, wanted, match_suffix=False)
            (KWS_DEST / "keywords.txt").write_text(KWS_KEYWORDS, encoding="utf-8")
            print(f"  keywords.txt → {(KWS_DEST / 'keywords.txt').relative_to(ANDROID_ROOT)}")
    except FetchError as error:
        print(f"[ERROR] {error}", file=sys.stderr)
        return 1
    print("[OK] Voice assets installed. Rebuild the Sample (assembleDebug) to package them.")
    return 0


class FetchError(Exception):
    pass


def download(url: str, sha256: str) -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    target = CACHE / url.rsplit("/", 1)[1]
    if target.exists() and digest(target) == sha256:
        print(f"[cache] {target.name}")
        return target
    partial = target.with_suffix(target.suffix + ".part")
    print(f"[download] {url}")
    try:
        with urllib.request.urlopen(url, timeout=60) as response, partial.open("wb") as out:
            total = int(response.headers.get("Content-Length") or 0)
            done = 0
            while chunk := response.read(1 << 20):
                out.write(chunk)
                done += len(chunk)
                if total:
                    print(f"\r  {done >> 20} / {total >> 20} MiB", end="", flush=True)
        print()
    except OSError as error:
        raise FetchError(f"download failed: {url}: {error}") from error
    actual = digest(partial)
    if actual != sha256:
        partial.unlink(missing_ok=True)
        raise FetchError(f"SHA-256 mismatch for {target.name}: expected {sha256}, got {actual}")
    partial.replace(target)
    return target


def extract(archive: Path, wanted: dict[str, Path], match_suffix: bool) -> None:
    """Copies only the named members; never trusts archive paths for the destination."""
    found: set[str] = set()
    with tarfile.open(archive, "r:bz2") as tar:
        for member in tar:
            if not member.isfile():
                continue
            name = PurePosixPath(member.name)
            if name.is_absolute() or ".." in name.parts:
                continue
            key = next((k for k in wanted if (str(name).endswith("/" + k) if match_suffix else str(name) == k)), None)
            if key is None or key in found:
                continue
            source = tar.extractfile(member)
            if source is None:
                continue
            destination = wanted[key]
            destination.parent.mkdir(parents=True, exist_ok=True)
            with source, destination.open("wb") as out:
                shutil.copyfileobj(source, out)
            found.add(key)
            print(f"  {key} → {destination.relative_to(ANDROID_ROOT)} ({destination.stat().st_size >> 10} KiB)")
    missing = sorted(set(wanted) - found)
    if missing:
        raise FetchError(f"{archive.name} lacks {', '.join(missing)}")


def digest(path: Path) -> str:
    sha = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1 << 20):
            sha.update(chunk)
    return sha.hexdigest()


if __name__ == "__main__":
    sys.exit(main())
