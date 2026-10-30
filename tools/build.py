#!/usr/bin/env python3
"""Build the dependency-free Android app using pinned Google SDK tools."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK = ROOT / "tools/android"
BUILD = ROOT / "build"
DIST = ROOT / "dist"


def run(*args):
    subprocess.run([str(x) for x in args], check=True, cwd=ROOT)


def fetch(name, url, checksum):
    archive = SDK / name
    SDK.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        print("Downloading", url, flush=True)
        temporary = archive.with_suffix(".download")
        urllib.request.urlretrieve(url, temporary)
        temporary.replace(archive)
    if checksum and hashlib.sha256(archive.read_bytes()).hexdigest() != checksum:
        raise RuntimeError("SDK checksum mismatch: " + name)
    with zipfile.ZipFile(archive) as z:
        for info in z.infolist():
            if not (SDK / info.filename).resolve().is_relative_to(SDK.resolve()):
                raise RuntimeError("Invalid SDK archive entry")
        z.extractall(SDK)


def tools():
    jar = SDK / "android-35/android.jar"
    if not jar.exists():
        fetch("platform.zip", "https://dl.google.com/android/repository/platform-35_r02.zip",
              "0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0")
    system = platform.system()
    if system != "Darwin":
        raise RuntimeError("This bootstrap is pinned for macOS. On Linux/Windows, use Android Studio with the included Gradle project.")
    bt = SDK / "android-15"
    if not (bt / "aapt2").exists():
        fetch("build-tools.zip", "https://dl.google.com/android/repository/build-tools_r35_macosx.zip",
              "530cdbd1ec315e1477624d7ed2f0f2962108d69f36eddba5894cef9ea2cedb48")
    for name in ("aapt2", "zipalign"):
        (bt / name).chmod(0o755)
    return jar, bt


def test():
    out = BUILD / "tests"
    out.mkdir(parents=True, exist_ok=True)
    source = ROOT / "app/src/main/java/cn/es/soundlab"
    run("javac", "-encoding", "UTF-8", "-d", out,
        source / "Protocol.java", source / "TransitionGate.java", source / "EventSpec.java",
        ROOT / "tests/CoreTest.java")
    run("java", "-ea", "-cp", out, "cn.es.soundlab.CoreTest")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--test-only", action="store_true")
    parser.add_argument("--compile-only", action="store_true",
                        help="Compile resources, Java and DEX without signing or changing dist artifacts")
    args = parser.parse_args()
    test()
    if args.test_only:
        return
    jar, bt = tools()
    if BUILD.exists():
        shutil.rmtree(BUILD)
    for name in ("classes", "generated", "dex"):
        (BUILD / name).mkdir(parents=True)
    DIST.mkdir(exist_ok=True)
    source = ROOT / "app/src/main"
    run(bt / "aapt2", "compile", "--dir", source / "res", "-o", BUILD / "resources.zip")
    run(bt / "aapt2", "link", "-o", BUILD / "resources.apk", "-I", jar,
        "--manifest", source / "AndroidManifest.xml", "--java", BUILD / "generated",
        "-A", source / "assets", "-0", "mp3", "-0", "wav", BUILD / "resources.zip")
    sources = sorted((source / "java").rglob("*.java")) + sorted((BUILD / "generated").rglob("*.java"))
    argfile = BUILD / "sources.txt"
    argfile.write_text("\n".join('"' + str(p).replace("\\", "\\\\").replace('"', '\\"') + '"' for p in sources))
    run("javac", "-encoding", "UTF-8", "--release", "8",
        "-classpath", jar, "-d", BUILD / "classes", "@" + str(argfile))
    with zipfile.ZipFile(BUILD / "classes.jar", "w", zipfile.ZIP_DEFLATED) as z:
        for p in (BUILD / "classes").rglob("*.class"):
            z.write(p, str(p.relative_to(BUILD / "classes")))
    run("java", "-cp", bt / "lib/d8.jar", "com.android.tools.r8.D8", "--min-api", "26",
        "--lib", jar, "--output", BUILD / "dex", BUILD / "classes.jar")
    if args.compile_only:
        print("Resources, Java and DEX compiled successfully; no APK signed or published.")
        return
    shutil.copy2(BUILD / "resources.apk", BUILD / "unsigned.apk")
    with zipfile.ZipFile(BUILD / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as z:
        for p in (BUILD / "dex").glob("*.dex"):
            z.write(p, p.name)
    run(bt / "zipalign", "-f", "4", BUILD / "unsigned.apk", BUILD / "aligned.apk")
    key = ROOT / "tools/debug.keystore"
    if not key.exists():
        run("keytool", "-genkeypair", "-keystore", key, "-alias", "androiddebugkey",
            "-storepass", "android", "-keypass", "android", "-dname", "CN=ES Sound Lab Debug,O=Local,C=CN",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-storetype", "JKS")
    apk = DIST / "es-sound-lab-0.3.1-debug.apk"
    signer = bt / "lib/apksigner.jar"
    run("java", "-jar", signer, "sign", "--ks", key, "--ks-key-alias", "androiddebugkey",
        "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", apk, BUILD / "aligned.apk")
    run("java", "-jar", signer, "verify", "--verbose", "--print-certs", apk)
    run(bt / "zipalign", "-c", "4", apk)
    metadata = {"file": apk.name, "bytes": apk.stat().st_size,
                "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                "package": "cn.es.soundlab", "version": "0.3.1", "minSdk": 26,
                "targetSdk": 28, "compileSdk": 35, "signing": "local debug certificate"}
    (DIST / "build-info.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(metadata, ensure_ascii=False, indent=2))
    print("APK:", apk)


if __name__ == "__main__":
    main()
