import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED


SOURCE = Path(__file__).resolve().parent / "lsp"


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def build(sdk, xposed_api, output, driver):
    android = sdk / "platforms/android-35/android.jar"
    tools = sdk / "build-tools/34.0.0"
    exe = ".exe" if os.name == "nt" else ""
    aapt = tools / f"aapt2{exe}"
    align = tools / f"zipalign{exe}"
    d8 = tools / "lib/d8.jar"
    signer = tools / "lib/apksigner.jar"
    for file in (android, xposed_api, aapt, align, d8, signer):
        if not file.is_file():
            raise SystemExit(f"Missing Android build input: {file}")

    version = ET.parse(SOURCE / "AndroidManifest.xml").getroot().get("{http://schemas.android.com/apk/res/android}versionName")
    stem = f"warsaw-Fans-Control-v{version}"
    output.mkdir(exist_ok=True)
    with TemporaryDirectory(dir=output) as tmp:
        work = Path(tmp)
        classes = work / "classes"
        dex = work / "dex"
        classes.mkdir()
        dex.mkdir()
        run(aapt, "compile", "--dir", SOURCE / "res", "-o", work / "resources.zip")
        unsigned = work / "unsigned.apk"
        run(aapt, "link", "--manifest", SOURCE / "AndroidManifest.xml", "-I", android,
            "-A", SOURCE / "assets", "-o", unsigned, work / "resources.zip")
        run("javac", "-encoding", "UTF-8", "--release", "11", "-classpath",
            os.pathsep.join(map(str, (android, xposed_api))), "-d", classes, *sorted((SOURCE / "java").rglob("*.java")))
        bytecode = work / "classes.jar"
        run("jar", "--create", "--file", bytecode, "-C", classes, ".")
        run("java", "-cp", d8, "com.android.tools.r8.D8", "--lib", android, "--classpath", xposed_api,
            "--min-api", "28", "--output", dex, bytecode)
        with ZipFile(unsigned, "a") as apk:
            apk.write(dex / "classes.dex", "classes.dex", compress_type=ZIP_DEFLATED)
            apk.write(driver, "assets/k90_fan_cap.ko", compress_type=ZIP_DEFLATED)
        aligned = work / "aligned.apk"
        run(align, "-f", "4", unsigned, aligned)

        key = Path.home() / ".android/debug.keystore"
        if not key.exists():
            key.parent.mkdir(parents=True, exist_ok=True)
            run("keytool", "-genkeypair", "-keystore", key, "-storepass", "android", "-keypass", "android",
                "-alias", "androiddebugkey", "-dname", "CN=Android Debug,O=Android,C=US",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000")
        signed = work / f"{stem}.apk"
        run("java", "-jar", signer, "sign", "--ks", key, "--ks-pass", "pass:android", "--key-pass",
            "pass:android", "--ks-key-alias", "androiddebugkey", "--out", signed, aligned)
        run("java", "-jar", signer, "verify", "--verbose", signed)
        run(align, "-c", "4", signed)
        package = work / f"{stem}.zip"
        with ZipFile(package, "w", ZIP_DEFLATED) as archive:
            archive.write(signed, signed.name)
            archive.write(SOURCE / "安装说明.txt", "安装说明.txt")
        with ZipFile(package) as archive:
            if archive.testzip() is not None:
                raise SystemExit("ZIP verification failed")
        signed.replace(output / signed.name)
        return package.replace(output / package.name)
