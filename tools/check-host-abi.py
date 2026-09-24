#!/usr/bin/env python3
"""检查功能包引用的共享运行库类是否都被跑跑桌面的 R8 规则保留。

正式版桌面会压缩代码，只保留 proguard-rules.pro 里「音乐功能包共享运行库 ABI」那一段列出的类；
功能包用到清单外的类，在正式版桌面上会 NoClassDefFoundError / NoSuchMethodError。

用法：
  tools/check-host-abi.py --rules <carhome-app>/app/proguard-rules.pro [--emit] <classes.jar>...
不传 jar 时自动使用三个模块的 release classes.jar（需先 bundleReleaseClassesToRuntimeJar）。
--emit 直接打印缺少的 keep 行，便于粘进规则文件。
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_JARS = [
    f"{ROOT}/{m}/build/intermediates/runtime_app_classes_jar/release/bundleReleaseClassesToRuntimeJar/classes.jar"
    for m in ("app", "qqmusic", "kugoumusic")
]
# 功能包自己的代码，以及安卓框架（桌面不压缩框架类）
OWN = ("com/cloudmusic/", "com/qqmusic/", "com/kugoumusic/", "com/paopao/music/")
FRAMEWORK = ("android/", "java/", "javax/", "dalvik/", "org/json/", "org/w3c/", "org/xml/", "com/android/")
# 只在编译期存在的注解
COMPILE_ONLY = ("androidx/annotation/", "org/jetbrains/annotations/")
# 只经由 InnerClasses 属性或内联门面出现、运行时不会单独解析的类（旧版功能包就有，正式桌面实测可用）
KNOWN_BENIGN = {
    "androidx/compose/material/icons/Icons", "androidx/compose/material/icons/Icons$AutoMirrored",
    "androidx/media3/datasource/DataSource", "androidx/media3/datasource/DefaultDataSource",
    "androidx/media3/datasource/ResolvingDataSource", "androidx/media3/datasource/cache/CacheDataSource",
    "androidx/media3/datasource/okhttp/OkHttpDataSource", "androidx/media3/exoplayer/source/MediaSource",
    "com/google/common/util/concurrent/ListenableFuture", "kotlin/comparisons/ComparisonsKt__ComparisonsKt",
}
ABI_START = "音乐功能包共享运行库 ABI"
ABI_END = "音乐功能包共享运行库 ABI 结束"


def referenced_classes(jar):
    refs = set()
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(jar) as z:
            names = [n for n in z.namelist() if n.endswith(".class")]
        # 打进功能包里的类（含各库的 R 类）不需要桌面提供
        defined = {n[:-len(".class")] for n in names}
        with zipfile.ZipFile(jar) as z:
            z.extractall(tmp, names)
        paths = [os.path.join(tmp, n) for n in names]
        for i in range(0, len(paths), 200):
            out = subprocess.run(["javap", "-v", "-p", *paths[i:i + 200]], capture_output=True, text=True, check=True).stdout
            # 只看常量池里真正会被链接的条目：类引用 + 方法/字段引用（含描述符里的类型）。
            # Kotlin 元数据、注解里的类名只是字符串，运行时不解析。
            for line in out.splitlines():
                m = re.search(r"= (Class|Methodref|InterfaceMethodref|Fieldref)\s.*?//\s*(.*)$", line)
                if not m:
                    continue
                ref = m.group(2)
                if m.group(1) == "Class":
                    refs.add(re.sub(r"^\[+L?|;$", "", ref.strip().strip('"')))
                else:
                    refs.add(ref.split(".", 1)[0].strip('"'))
                    refs.update(re.findall(r"L([\w/$]+);", ref))
    return {
        r for r in refs
        if "/" in r and r not in defined and not r.startswith(OWN + FRAMEWORK + COMPILE_ONLY)
    }


def kept_classes(rules):
    kept, inside = set(), False
    wildcards = []
    for line in open(rules, encoding="utf-8"):
        if ABI_END in line:
            inside = False
        elif ABI_START in line:
            inside = True
        elif inside:
            m = re.match(r"-keep class ([\w.$*]+)", line.strip())
            if m:
                name = m.group(1).replace(".", "/")
                if "*" in name:
                    wildcards.append(re.compile("^" + re.escape(name).replace(r"\*", "[^/]*") + "$"))
                else:
                    kept.add(name)
    return kept, wildcards


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rules", required=True)
    ap.add_argument("--emit", action="store_true")
    ap.add_argument("jars", nargs="*")
    args = ap.parse_args()
    jars = args.jars or DEFAULT_JARS
    for jar in jars:
        if not os.path.exists(jar):
            sys.exit(f"缺少 {jar}，先运行 ./gradlew bundleReleaseClassesToRuntimeJar")
    refs = set().union(*(referenced_classes(j) for j in jars))
    kept, wildcards = kept_classes(args.rules)
    missing = sorted(
        r for r in refs
        if r not in kept and r not in KNOWN_BENIGN and not any(w.match(r) for w in wildcards)
    )
    if args.emit:
        for r in missing:
            print(f"-keep class {r.replace('/', '.')} {{ *; }}")
    else:
        print(f"功能包引用共享库类 {len(refs)} 个，桌面已保留 {len(refs) - len(missing)} 个，缺 {len(missing)} 个")
        for r in missing:
            print("  " + r.replace("/", "."))
    sys.exit(1 if missing else 0)


if __name__ == "__main__":
    main()
