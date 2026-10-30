#!/usr/bin/env python3
"""Run native Android UI checks and produce screenshots with Robolectric."""
from pathlib import Path
import os
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
deps = root / "tools/ui-check/lib"
if not deps.exists():
    subprocess.run(["mvn", "-q", "-s", "tools/ui-check/settings.xml", "-f", "tools/ui-check/pom.xml",
                    "dependency:copy-dependencies", "-DoutputDirectory=lib"], cwd=root, check=True)
for aar in deps.glob("*.aar"):
    with zipfile.ZipFile(aar) as z:
        (deps / (aar.stem + "-classes.jar")).write_bytes(z.read("classes.jar"))
out = root / "tools/ui-check/classes"
out.mkdir(exist_ok=True)
cp = os.pathsep.join([str(root / "build/classes"), str(root / "tools/android/android-35/android.jar")]
                     + [str(p) for p in deps.glob("*.jar")])
java_root = Path(os.environ.get("ES_SOUNDLAB_JAVA_HOME", str(Path.home() / ".sdkman/candidates/java/21.0.6-amzn"))) / "bin"
javac = str(java_root / "javac") if java_root.exists() else "javac"
java = str(java_root / "java") if java_root.exists() else "java"
subprocess.run([javac, "--release", "11", "-encoding", "UTF-8", "-cp", cp, "-d", str(out), str(root / "tests/UiTest.java"), str(root / "tests/PlaybackTest.java"), str(root / "tests/LogReaderTest.java"), str(root / "tests/SplitProbeTest.java")], cwd=root, check=True)
subprocess.run([java, "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED",
                "-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2",
                "-cp", str(out) + os.pathsep + cp, "org.junit.runner.JUnitCore", "cn.es.soundlab.UiTest", "cn.es.soundlab.PlaybackTest", "cn.es.soundlab.LogReaderTest", "cn.es.soundlab.SplitProbeTest"], cwd=root, check=True)
