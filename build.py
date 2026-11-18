import argparse
import os
import shutil
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

from build_lsp import build


ROOT = Path(__file__).resolve().parent
KERNEL = ROOT / "kernel"
OUTPUT = ROOT / "dist"


def build_driver(zig, workdir):
    obj = workdir / "fan_cap.o"
    driver = workdir / "k90_fan_cap.ko"
    subprocess.run(
        [
            zig, "cc", "-target", "aarch64-freestanding-none",
            "-mgeneral-regs-only", "-mbranch-protection=pac-ret+bti", "-fsanitize=kcfi",
            "-ffreestanding", "-fno-builtin", "-fno-pic", "-fno-pie",
            "-fno-stack-protector", "-fno-unwind-tables", "-fno-asynchronous-unwind-tables",
            "-fno-addrsig", "-O2", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
            "-c", str(KERNEL / "fan_cap.c"), "-o", str(obj),
        ],
        check=True,
    )
    subprocess.run(
        [
            zig, "ld.lld", "-m", "aarch64elf", "-r", "--build-id=sha1", "-T",
            str(KERNEL / "module.lds"), "-o", str(driver), str(obj),
        ],
        check=True,
    )
    return driver


def main():
    parser = argparse.ArgumentParser(description="Build the LSPosed APK and its companion ZIP.")
    parser.add_argument("--zig", help="Path to Zig 0.15.2 for the fan driver")
    parser.add_argument("--android-sdk", type=Path, default=os.getenv("ANDROID_HOME") or os.getenv("ANDROID_SDK_ROOT"))
    parser.add_argument("--xposed-api", type=Path, default=os.getenv("XPOSED_API_JAR"))
    arguments = parser.parse_args()
    if not arguments.android_sdk or not arguments.xposed_api:
        parser.error("Supply the Android SDK and Xposed API JAR via --android-sdk/--xposed-api or the environment.")
    zig = arguments.zig or os.getenv("ZIG") or shutil.which("zig")
    if not zig:
        parser.error("Supply --zig or put Zig 0.15.2 on PATH.")
    with TemporaryDirectory(prefix="k90-fan-build-") as workdir:
        driver = build_driver(zig, Path(workdir))
        print(build(arguments.android_sdk, arguments.xposed_api, OUTPUT, driver))


if __name__ == "__main__":
    main()
