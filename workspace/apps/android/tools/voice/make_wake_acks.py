#!/usr/bin/env python3
"""Make the overlay's wake acknowledgements: 小博 answers 「我在」 the moment it is woken.

Synthesizes a few short lines once for every MiMo preset voice (mimo-v2.5-tts: 冰糖 茉莉 苏打 白桦 Mia Chloe Milo
Dean), trims the silence around each and writes them as 16-bit mono WAV into the APK's assets:

  bothy-sample/src/main/assets/voice-acks/<voice>/<line>.wav

The app plays the chosen voice's clip from memory on a wake (DebugWakeAck), with no network round trip; a voice
without packaged clips is synthesized on its first wake and cached on the device. Keep LINES in step with
DebugWakeAckLine.

The key comes from local.properties (MIMO_TTS_API_KEY, else AGENT_RUNTIME_PROVIDER_PROFILE_MIMO_API_KEY, as the app
resolves it); it is sent to the MiMo endpoint only and never printed. The English voices sometimes misread a short
Chinese line (Dean's 「我在」 came back as "White eye"): --verify has DashScope's recognizer (DASHSCOPE_API_KEY, also
from local.properties) transcribe each new clip and synthesizes it again, up to ATTEMPTS times, until it says the line;
a line that never comes out right is removed, and the app answers with that voice's other lines.

Usage:
  python workspace/apps/android/tools/voice/make_wake_acks.py [--voice 冰糖] [--line here] [--force] [--verify]
"""

from __future__ import annotations

import argparse
import array
import base64
import io
import json
import math
import re
import sys
import urllib.error
import urllib.request
import wave
from pathlib import Path

ANDROID_ROOT = Path(__file__).resolve().parents[2]
DEST = ANDROID_ROOT / "bothy-sample" / "src" / "main" / "assets" / "voice-acks"

# (asset directory, MiMo voice id); the directory is MimoTtsVoice's name in lower case.
VOICES = (
    ("bing_tang", "冰糖"),
    ("mo_li", "茉莉"),
    ("su_da", "苏打"),
    ("bai_hua", "白桦"),
    ("mia", "Mia"),
    ("chloe", "Chloe"),
    ("milo", "Milo"),
    ("dean", "Dean"),
)
# (file, text, language): ways to answer a wake, and one for a wake that stopped what 小博 was doing; the English
# ones answer OK Bothy.
LINES = (
    ("here", "我在", "zh"),
    ("yes", "在呢", "zh"),
    ("mm", "嗯，我在", "zh"),
    ("go-on", "好，你说", "zh"),
    ("here-en", "I'm here", "en"),
    ("yes-en", "Yes?", "en"),
    ("go-on-en", "Go ahead", "en"),
)
STYLE = {
    "zh": "被叫到名字时随口答应一声：自然、轻快、亲切，短促干脆，不拖音，不要播音腔或客服腔，"
    "像反应很快的 AI 伙伴在近距离聊天。不要模仿任何特定真人声音。",
    "en": "Answer casually when called by name: natural, light and friendly, short and crisp, no drawl, no announcer "
    "or call-centre voice, like a quick AI companion chatting close by. Do not imitate any real person's voice.",
}
DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1"
DEFAULT_MODEL = "mimo-v2.5-tts"
SILENCE_DBFS = -45.0
PAD_MS = 40
FADE_MS = 12
WINDOW_MS = 10
ATTEMPTS = 4
ASR_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
ASR_MODEL = "qwen3-asr-flash"


def local_properties() -> dict[str, str]:
    values: dict[str, str] = {}
    path = ANDROID_ROOT / "local.properties"
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def synthesize(base_url: str, model: str, key: str, voice: str, text: str, language: str) -> bytes:
    url = base_url.rstrip("/")
    if not url.endswith("/chat/completions"):
        url += "/chat/completions"
    body = {
        "model": model,
        "messages": [{"role": "user", "content": STYLE[language]}, {"role": "assistant", "content": text}],
        "audio": {"format": "wav", "voice": voice},
    }
    request = urllib.request.Request(
        url,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"api-key": key, "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            root = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"MiMo TTS HTTP {error.code}") from None
    data = root["choices"][0]["message"]["audio"]["data"]
    return base64.b64decode(data)


def transcript(key: str, wav_bytes: bytes, language: str) -> str:
    audio = "data:audio/wav;base64," + base64.b64encode(wav_bytes).decode("ascii")
    body = {
        "model": ASR_MODEL,
        "messages": [{"role": "user", "content": [{"type": "input_audio", "input_audio": {"data": audio}}]}],
        "asr_options": {"language": language},
    }
    request = urllib.request.Request(
        ASR_URL,
        data=json.dumps(body).encode("utf-8"),
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read().decode("utf-8"))["choices"][0]["message"]["content"]
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"DashScope ASR HTTP {error.code}") from None


def says(heard: str, text: str) -> bool:
    """Whether [heard] is [text], punctuation, spacing and case aside."""
    def plain(value: str) -> str:
        return re.sub(r"[\s\W_]+", "", value).lower()
    return plain(heard) == plain(text)


def wav_bytes(samples: array.array, rate: int) -> bytes:
    out = array.array("h", samples)
    if sys.byteorder == "big":
        out.byteswap()
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as writer:
        writer.setnchannels(1)
        writer.setsampwidth(2)
        writer.setframerate(rate)
        writer.writeframes(out.tobytes())
    return buffer.getvalue()


def trimmed(wav_bytes: bytes) -> tuple[array.array, int]:
    """The clip's samples without the silence around it (a little kept), faded in and out, and its sample rate."""
    with wave.open(io.BytesIO(wav_bytes)) as reader:
        if reader.getsampwidth() != 2:
            raise RuntimeError(f"expected 16-bit audio, got {reader.getsampwidth() * 8}-bit")
        channels, rate = reader.getnchannels(), reader.getframerate()
        frames = reader.readframes(reader.getnframes())
    samples = array.array("h", frames)
    if sys.byteorder == "big":
        samples.byteswap()
    if channels > 1:
        samples = array.array("h", samples[::channels])
    window = max(1, rate * WINDOW_MS // 1000)
    floor = 32768 * 10 ** (SILENCE_DBFS / 20)

    def loud(start: int) -> bool:
        chunk = samples[start:start + window]
        return bool(chunk) and math.sqrt(sum(s * s for s in chunk) / len(chunk)) > floor

    starts = range(0, len(samples), window)
    voiced = [start for start in starts if loud(start)]
    if not voiced:
        raise RuntimeError("the clip is silent")
    pad = rate * PAD_MS // 1000
    first = max(0, voiced[0] - pad)
    last = min(len(samples), voiced[-1] + window + pad)
    clip = samples[first:last]
    fade = min(len(clip) // 2, rate * FADE_MS // 1000)
    for i in range(fade):
        clip[i] = int(clip[i] * i / fade)
        clip[-1 - i] = int(clip[-1 - i] * i / fade)
    return clip, rate


def make_clip(tts: tuple[str, str, str], asr_key: str | None, voice: str, text: str, language: str) -> tuple[bytes, int, str]:
    """The clip as WAV bytes, its length in ms, and what the recognizer heard ("-" unchecked)."""
    heard = "-"
    for _ in range(ATTEMPTS if asr_key else 1):
        samples, rate = trimmed(synthesize(*tts, voice, text, language))
        clip = wav_bytes(samples, rate)
        if not asr_key:
            break
        heard = transcript(asr_key, clip, language)
        if says(heard, text):
            break
    else:
        raise RuntimeError(f"still heard as {heard!r} after {ATTEMPTS} tries")
    return clip, len(samples) * 1000 // rate, heard


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--voice", action="append", help="only these MiMo voice ids (repeatable)")
    parser.add_argument("--line", action="append", help="only these line files, e.g. here (repeatable)")
    parser.add_argument("--force", action="store_true", help="remake clips that already exist")
    parser.add_argument("--verify", action="store_true", help="check each new clip with DashScope's recognizer")
    args = parser.parse_args()
    props = local_properties()
    key = props.get("MIMO_TTS_API_KEY") or props.get("AGENT_RUNTIME_PROVIDER_PROFILE_MIMO_API_KEY", "")
    if not key:
        print("No MiMo key in local.properties (MIMO_TTS_API_KEY or AGENT_RUNTIME_PROVIDER_PROFILE_MIMO_API_KEY).")
        return 1
    asr_key = props.get("DASHSCOPE_API_KEY") if args.verify else None
    if args.verify and not asr_key:
        print("--verify needs DASHSCOPE_API_KEY in local.properties.")
        return 1
    tts = (props.get("MIMO_TTS_BASE_URL") or DEFAULT_BASE_URL, props.get("MIMO_TTS_MODEL") or DEFAULT_MODEL, key)
    failures = 0
    for directory, voice in VOICES:
        if args.voice and voice not in args.voice:
            continue
        for name, text, language in LINES:
            if args.line and name not in args.line:
                continue
            path = DEST / directory / f"{name}.wav"
            if path.exists() and not args.force:
                print(f"{voice} {text}: kept {path.relative_to(ANDROID_ROOT)}")
                continue
            try:
                clip, millis, heard = make_clip(tts, asr_key, voice, text, language)
            except (RuntimeError, KeyError, IndexError, ValueError, OSError, wave.Error) as error:
                failures += 1
                # A clip that failed its check is not kept: the app then picks among the voice's other lines.
                if asr_key:
                    path.unlink(missing_ok=True)
                print(f"{voice} {text}: failed ({error})")
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(clip)
            print(f"{voice} {text}: {millis} ms, heard {heard} → {path.relative_to(ANDROID_ROOT)}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
