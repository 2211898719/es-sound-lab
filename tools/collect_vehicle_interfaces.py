#!/usr/bin/env python3
"""Read installed package paths and interface metadata through an existing ADB connection."""

import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import shlex
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="ADB executable path")
    parser.add_argument("--serial", help="Already connected device serial")
    parser.add_argument("--output", type=Path, help="New local output directory")
    parser.add_argument("--package", action="append", default=[], help="Additional exact package name")
    parser.add_argument("--pull-apks", action="store_true", help="Also copy discovered target APKs locally")
    args = parser.parse_args()
    for package in args.package:
        if not re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", package):
            parser.error(f"Invalid package name: {package}")
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])

    def run(command, timeout=30):
        try:
            result = subprocess.run(adb + command, capture_output=True, text=True,
                                    encoding="utf-8", errors="replace", timeout=timeout)
            return {"exitCode": result.returncode, "stdout": result.stdout, "stderr": result.stderr}
        except (OSError, subprocess.TimeoutExpired) as error:
            return {"exitCode": -1, "stdout": "", "stderr": str(error)}

    def shell(*command):
        # adb shell parses its command remotely; quote every argument for that shell too.
        return run(["shell", shlex.join(command)])

    state = run(["get-state"])
    if state["exitCode"] != 0 or state["stdout"].strip() != "device":
        parser.exit(1, "No ready ADB device. Connect/authorize it first, or select --serial.\n"
                    + state["stderr"])
    output = (args.output or Path("build") / ("vehicle-interfaces-" +
              datetime.now().strftime("%Y%m%d-%H%M%S-%f"))).resolve()
    output.mkdir(parents=True, exist_ok=False)
    results = {"adb-state": state}

    def save(name, result):
        results[name] = result
        (output / (name + ".txt")).write_text(
            result["stdout"] + ("\n[stderr]\n" + result["stderr"] if result["stderr"] else ""),
            encoding="utf-8")
        return result["stdout"]

    save("shell-identity", shell("id"))
    save("selinux", shell("getenforce"))
    save("current-user", shell("am", "get-current-user"))
    packages = save("packages-with-paths", shell("pm", "list", "packages", "-f"))
    home = save("home-activity", shell("cmd", "package", "resolve-activity", "--brief", "-a",
                                     "android.intent.action.MAIN", "-c", "android.intent.category.HOME"))
    save("shared-libraries", shell("pm", "list", "libraries"))
    save("binder-services", shell("service", "list"))
    # Permission definitions/grants are metadata; no grants or settings are changed.
    save("package-permissions", shell("dumpsys", "package", "permissions"))
    property_names = [
        "ro.android.car.carservice.package", "ro.product.name", "ro.build.version.release",
        "ro.build.version.sdk", "ro.build.vehicle_model", "ro.vendor.build.vehicle_model",
        "vendor.adbd.disable_tcp", "sys.usb.mode", "sys.usb1.mode",
    ]
    properties = {}
    for name in property_names:
        result = shell("getprop", name)
        properties[name] = result
        results["getprop:" + name] = result
    (output / "properties.json").write_text(json.dumps(properties, ensure_ascii=False, indent=2) + "\n")

    # Labels and APK filenames are not package names; discover rather than assume a path.
    installed = set()
    for line in packages.splitlines():
        if line.startswith("package:") and "=" in line:
            package = line.rsplit("=", 1)[1].strip()
            if re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", package):
                installed.add(package)
    keywords = ("mega.car", "carservice", "redwood", "hvac", "climate", "launcher",
                "desktop", "systemui", "aiot", "engineeringmode", "carcontroladapter")
    targets = {p for p in installed if any(word in p.lower() for word in keywords)}
    targets.update(args.package)
    targets.update({"com.mega.car", "com.android.car", "com.mega.systemui",
                    "com.mega.carsettings", "com.chery.aiot", "cn.es.soundlab"})
    for line in home.splitlines():
        component = re.fullmatch(r"([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+", line.strip())
        if component:
            targets.add(component[1])
    implementation = properties["ro.android.car.carservice.package"]["stdout"].strip()
    if re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", implementation):
        targets.add(implementation)

    copied = []
    for package in sorted(targets):
        print("Inspect:", package, flush=True)
        save("package-" + package, shell("dumpsys", "package", package))
        paths = save("path-" + package, shell("pm", "path", package))
        if not any(line.strip().startswith("package:") for line in paths.splitlines()):
            paths = save("path-fallback-" + package, shell("cmd", "package", "path", package))
        if not args.pull_apks:
            continue
        for index, line in enumerate(paths.splitlines()):
            if not line.strip().startswith("package:"):
                continue
            remote = line.strip()[len("package:"):]
            if not remote.startswith("/") or not remote.endswith(".apk"):
                continue
            destination = output / "apks" / package / (str(index) + "-" + Path(remote).name)
            destination.parent.mkdir(parents=True, exist_ok=True)
            result = run(["pull", remote, str(destination)], timeout=180)
            results["pull:" + package + ":" + str(index)] = result
            copied.append({"package": package, "remote": remote,
                           "local": str(destination.relative_to(output)), "exitCode": result["exitCode"]})

    report = {"createdAt": datetime.now().astimezone().isoformat(), "targets": sorted(targets),
              "copied": copied, "commands": results}
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    failures = sum(item["exitCode"] != 0 for item in results.values())
    print(f"Saved: {output}\nCommands with nonzero exit codes: {failures}")
    print("Only metadata reads and optional APK copies were performed; no apps were started or changed.")


if __name__ == "__main__":
    main()
