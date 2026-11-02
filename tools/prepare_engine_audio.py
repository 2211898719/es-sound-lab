#!/usr/bin/env python3
"""Reproduce the bundled CC0 engine sample from its pinned original download."""
import array
import hashlib
import io
from pathlib import Path
import struct
import urllib.request
import wave

ROOT = Path(__file__).resolve().parents[1]
SOURCE = "https://opengameart.org/sites/default/files/loop_0.wav"
SOURCE_SHA256 = "69d74b106509037ce547429e4c3f9ae906b0fffe88d0bb8b3b5a8b0cfc239048"


def main():
    request = urllib.request.Request(SOURCE, headers={"User-Agent": "ES-SoundLab asset preparation"})
    with urllib.request.urlopen(request, timeout=30) as response:
        original = response.read(1024 * 1024)
    if hashlib.sha256(original).hexdigest() != SOURCE_SHA256:
        raise RuntimeError("Engine source checksum changed; review before updating")
    with wave.open(io.BytesIO(original), "rb") as source:
        if source.getsampwidth() != 2 or source.getnchannels() != 1:
            raise RuntimeError("Unexpected source format")
        samples = [value[0] / 32768 for value in struct.iter_unpack("<h", source.readframes(source.getnframes()))]
        input_rate = source.getframerate()
    mean = sum(samples) / len(samples)
    samples = [value - mean for value in samples]
    gain = .55 / max(abs(value) for value in samples)
    result = array.array("h")
    for index in range(round(len(samples) * 48000 / input_rate)):
        position = index * input_rate / 48000
        lower = min(int(position), len(samples) - 1)
        upper = min(lower + 1, len(samples) - 1)
        value = samples[lower] + (samples[upper] - samples[lower]) * (position - lower)
        result.append(round(value * gain * 32767))
    target = ROOT / "app/src/main/assets/audio/engine.wav"
    with wave.open(str(target), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(48000)
        output.writeframes(b"".join(struct.pack("<h", value) for value in result))
    print(target)
    print("source sha256:", SOURCE_SHA256)
    print("prepared sha256:", hashlib.sha256(target.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
