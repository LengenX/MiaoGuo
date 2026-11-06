"""把 classes.dex 追加进 aapt2 产出的 APK 根目录。"""
import shutil
import sys
import zipfile

src, dex, dst = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, "a", compression=zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
print("dex 已写入 " + dst)
