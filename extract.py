"""把下载好的三个 zip 解包并摆成 aapt2 / javac 期望的目录结构。

路径全部相对于本文件，换一台机器也能直接用。
"""
import os
import shutil
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
DL = os.path.join(ROOT, "dl")
TC = os.path.join(ROOT, "toolchain")


def rm(path):
    if os.path.isdir(path):
        shutil.rmtree(path, ignore_errors=True)
    elif os.path.isfile(path):
        os.remove(path)


def extract(zip_path, dest):
    rm(dest)
    os.makedirs(dest, exist_ok=True)
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(dest)
    return dest


def top_dirs(path):
    return [os.path.join(path, n) for n in os.listdir(path) if os.path.isdir(os.path.join(path, n))]


def find_dir_with(root, needle):
    for dirpath, dirnames, filenames in os.walk(root):
        if needle in filenames or needle in dirnames:
            return dirpath
    return None


def main():
    os.makedirs(TC, exist_ok=True)
    os.makedirs(DL, exist_ok=True)

    # ---- JDK ----
    jdk_target = os.path.join(TC, "jdk")
    if not os.path.isfile(os.path.join(jdk_target, "bin", "javac.exe")):
        tmp = extract(os.path.join(DL, "jdk17.zip"), os.path.join(TC, "_jdk_tmp"))
        tops = top_dirs(tmp)
        if len(tops) != 1:
            print("JDK 解包后不是单一目录：" + repr(tops))
            sys.exit(1)
        rm(jdk_target)
        os.rename(tops[0], jdk_target)
        rm(tmp)
        print("JDK  -> " + jdk_target)
    else:
        print("JDK already in place")

    # ---- build-tools ----
    bt_target = os.path.join(TC, "sdk", "build-tools", "34.0.0")
    if not os.path.isfile(os.path.join(bt_target, "aapt2.exe")):
        tmp = extract(os.path.join(DL, "build-tools-34.zip"), os.path.join(TC, "_bt_tmp"))
        holder = find_dir_with(tmp, "aapt2.exe")
        if not holder:
            print("build-tools 里找不到 aapt2.exe")
            sys.exit(1)
        rm(bt_target)
        os.makedirs(os.path.dirname(bt_target), exist_ok=True)
        shutil.move(holder, bt_target)
        rm(tmp)
        print("build-tools -> " + bt_target)
    else:
        print("build-tools already in place")

    # ---- platform (android.jar) ----
    pf_target = os.path.join(TC, "sdk", "platforms", "android-34")
    if not os.path.isfile(os.path.join(pf_target, "android.jar")):
        tmp = extract(os.path.join(DL, "platform-34.zip"), os.path.join(TC, "_pf_tmp"))
        holder = find_dir_with(tmp, "android.jar")
        if not holder:
            print("platform 里找不到 android.jar")
            sys.exit(1)
        rm(pf_target)
        os.makedirs(os.path.dirname(pf_target), exist_ok=True)
        shutil.move(holder, pf_target)
        rm(tmp)
        print("platform -> " + pf_target)
    else:
        print("platform already in place")

    print("TOOLCHAIN_READY")


if __name__ == "__main__":
    main()
