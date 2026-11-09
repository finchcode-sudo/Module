#!/bin/bash
# LShareMod 构建脚本
# 依赖: android-36 platform + build-tools 34.0.0(aapt2) + 36.0.0(d8) + 37.0.0(zipalign/apksigner)
set -e
cd "$(dirname "$0")"

SDK=/opt/android-sdk
AAPT2=$SDK/build-tools/34.0.0/aapt2
D8=$SDK/build-tools/36.0.0/d8
ZIPALIGN=$SDK/build-tools/37.0.0/zipalign
APKSIGNER=$SDK/build-tools/37.0.0/apksigner
ANDROID_JAR=$SDK/platforms/android-36/android.jar
# 签名配置必须由环境变量提供，不写入源码仓库：
#   export KS_PATH=/path/to/your.keystore
#   export KS_PASS=你的密码
#   export KS_ALIAS=你的别名
KS_PATH=${KS_PATH:?请设置 KS_PATH 环境变量指向 keystore 文件}
KS_PASS=${KS_PASS:?请设置 KS_PASS 环境变量}
KS_ALIAS=${KS_ALIAS:?请设置 KS_ALIAS 环境变量}

OUT=build
rm -rf $OUT
mkdir -p $OUT/classes $OUT/dex $OUT/res $OUT/gen

echo "[1/6] 编译资源 + 生成 R.java"
$AAPT2 compile --dir res -o $OUT/res/
$AAPT2 link -o $OUT/base.apk -I $ANDROID_JAR --manifest AndroidManifest.xml -R $OUT/res/*.flat --auto-add-overlay --java $OUT/gen

echo "[2/6] 编译 Java"
javac -encoding UTF-8 -source 8 -target 8 -cp $ANDROID_JAR -d $OUT/classes $(find src src2 $OUT/gen -name "*.java")

echo "[3/6] 转 dex（只打包自己的类，不含 libxposed 桩）"
$D8 --release --min-api 26 --lib $ANDROID_JAR --output $OUT/dex $OUT/classes/com/operit/lshare/*.class

echo "[4/6] 组装 APK"
cp $OUT/base.apk $OUT/fin.apk
python3 - <<"PY"
import zipfile
z = zipfile.ZipFile("build/fin.apk", "a", zipfile.ZIP_DEFLATED)
for src, dst in [
    ("build/dex/classes.dex", "classes.dex"),
    ("assets/xposed_init", "assets/xposed_init"),
    ("metainf/xposed/java_init.list", "META-INF/xposed/java_init.list"),
    ("metainf/xposed/module.prop", "META-INF/xposed/module.prop"),
    ("metainf/xposed/scope.list", "META-INF/xposed/scope.list"),
]:
    z.write(src, dst)
z.close()
print("  packed")
PY

echo "[5/6] 对齐"
$ZIPALIGN -f -p 4 $OUT/fin.apk $OUT/aligned.apk

echo "[6/6] 签名"
$APKSIGNER sign --ks "$KS_PATH" --ks-pass pass:$KS_PASS --key-pass pass:$KS_PASS --ks-key-alias $KS_ALIAS --out $OUT/LShareMod.apk $OUT/aligned.apk

$APKSIGNER verify $OUT/LShareMod.apk && echo "" && echo "BUILD OK: $OUT/LShareMod.apk"
