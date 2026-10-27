#!/usr/bin/env python3
"""Verify the packaged plugin against local IDE installations or extracted SDKs.

Example: python3 scripts/verify-compatibility.py /path/to/idea-2019 /path/to/idea-2026
Use --java to select a JDK 21+ executable. Reports include the checked ZIP's SHA-256.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
VERIFIER_VERSION = "1.411"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ides", nargs="+", type=Path)
    parser.add_argument("--plugin", type=Path, default=ROOT / "build/distributions/easy-request-1.0.0.zip")
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--verifier", type=Path, default=ROOT / ".tools/compat/verifier-cli.jar")
    args = parser.parse_args()
    if not args.plugin.is_file():
        parser.error("Build the plugin ZIP with buildPlugin first.")
    if not args.java:
        parser.error("Java is required; supply --java /path/to/jdk/bin/java.")
    targets = []
    for supplied in args.ides:
        home = supplied.resolve()
        if (home / "Contents/Resources/product-info.json").is_file():
            home = home / "Contents"
        metadata = next((p for p in (home / "product-info.json", home / "Resources/product-info.json") if p.is_file()), None)
        if metadata is None:
            parser.error("IDE product-info.json not found in " + str(home))
        info = json.loads(metadata.read_text())
        targets.append((home, info))
    if not args.verifier.is_file():
        args.verifier.parent.mkdir(parents=True, exist_ok=True)
        url = "https://repo.maven.apache.org/maven2/org/jetbrains/intellij/plugins/verifier-cli/{0}/verifier-cli-{0}-all.jar".format(VERIFIER_VERSION)
        partial = args.verifier.with_suffix(".download")
        urllib.request.urlretrieve(url, partial)
        partial.replace(args.verifier)
    reports = ROOT / "build/reports/pluginVerifier"
    reports.mkdir(parents=True, exist_ok=True)
    command = [args.java, "-Xmx2g", "-jar", str(args.verifier.resolve()), "check-plugin", str(args.plugin.resolve())]
    command += [str(home) for home, _ in targets]
    command += ["-offline", "-verification-reports-dir", str(reports)]
    log = reports / "verification.log"
    print("Checking {} IDEs; detailed output: {}".format(len(targets), log), flush=True)
    started = time.time()
    with log.open("w") as output:
        result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, cwd=ROOT)
    checks = []
    passed = result.returncode == 0
    for _, info in targets:
        folder = reports / (info["productCode"] + "-" + info["buildNumber"])
        verdicts = list(folder.glob("plugins/com.easyrequest.idea/*/verification-verdict.txt"))
        verdict = verdicts[0].read_text().strip() if len(verdicts) == 1 else "No verification verdict"
        problems = list(folder.glob("plugins/com.easyrequest.idea/*/compatibility-problems.txt"))
        fresh = len(verdicts) == 1 and verdicts[0].stat().st_mtime >= started
        compatible = fresh and verdict.startswith("Compatible.") and not any(p.read_text().strip() for p in problems)
        passed = passed and compatible
        checks.append({"product": info["productCode"], "version": info["version"], "build": info["buildNumber"], "compatible": compatible, "verdict": verdict})
        print(info["version"] + ": " + verdict)
    digest = hashlib.sha256(args.plugin.read_bytes()).hexdigest()
    summary_path = ROOT / "build/reports/compatibility-summary.json"
    previous = json.loads(summary_path.read_text()) if summary_path.is_file() else {}
    combined = {check["build"]: check for check in previous.get("checks", [])} if previous.get("sha256") == digest else {}
    combined.update({check["build"]: check for check in checks})
    checks = sorted(combined.values(), key=lambda check: tuple(int(part) for part in check["build"].split(".")))
    passed = passed and all(check["compatible"] for check in checks)
    summary = {"plugin": args.plugin.name, "sha256": digest,
               "verifier": VERIFIER_VERSION, "passed": passed, "checks": checks}
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
