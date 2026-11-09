# LShareMod - 桌面多选分享 / 卸载模块

为 ColorOS / Oplus 系统桌面（`com.android.launcher`）的多选模式底部栏注入「分享」按钮，
并为「移除」按钮增加长按批量卸载能力。

## 版本

v6.9 (versionCode 45)

## 功能

| 操作 | 效果 |
|---|---|
| 多选 -> 点「分享」 | 把选中应用的 APK 一起分享出去，文件名 `应用名_版本名 (版本号).apk`（与 Appkit 默认导出规则一致） |
| 长按图标 -> 「分享」 | 同上，hook `AppShare.onClick` 接管 |
| 多选 -> 长按「移除」 | 弹出系统批量卸载面板 |
| 多选 -> 点「移除」 | 保持原生行为（从桌面移除图标） |

分享细节：

- 多个应用用 `ACTION_SEND_MULTIPLE`，单个用 `ACTION_SEND`
- 文件复制到 `cache/share_apk/` 并重命名为「应用名.apk」，否则对方只看到 base.apk
- 自动跳过系统应用和没有 APK 的条目

## 测试环境

- 设备：OnePlus Ace5 至尊版 (Android 16)
- 桌面：com.android.launcher 16.3.12
- 框架：LSPosed v2.2.0 (7854)

## 目录结构

```
AndroidManifest.xml              模块清单
src/com/operit/lshare/
  MainHook.java                  模块入口 + Hook + 分享/卸载逻辑（约 646 行）
  MainActivity.java              说明页（仅为了让模块有启动图标）
src2/io/github/libxposed/api/    libxposed API 编译桩（不入 dex，运行时由 LSPosed 提供）
res/values/
  strings.xml                    模块名
  arrays.xml                     xposed_scope 数组
assets/xposed_init               旧版入口（兼容保留）
metainf/xposed/
  java_init.list                 v2.2.0 入口类
  module.prop                    minApiVersion / targetApiVersion / staticScope
  scope.list                     作用域包名
build.sh                         构建脚本
```

## LSPosed v2.2.0 模块规范（重要）

v2.2.0 已全面迁移到 libxposed 新 API，与旧规范完全不同：

| 项 | 旧规范 | v2.2.0 |
|---|---|---|
| 入口 | assets/xposed_init | META-INF/xposed/java_init.list |
| 作用域 | manifest xposedscope | META-INF/xposed/scope.list |
| 元数据 | manifest meta-data | META-INF/xposed/module.prop |
| 基类 | IXposedHookLoadPackage | io.github.libxposed.api.XposedModule |
| 回调 | handleLoadPackage | onPackageReady |
| Hook | XposedBridge.hookAllMethods | this.hook(Executable).intercept(Hooker) |

注意：`hook()` 的返回类型在 LSPosed 内部是**混淆包名**：

```
pqdKbQu.iB.Yph.Hdo.E.Jd.XposedInterface$HookBuilder
```

不能直接强转，必须用反射按「方法名 + 参数个数」匹配。MainHook 里已做通用反射处理。

## 核心实现要点

### 0. 长按分享（v6.9）

长按菜单的「分享」**不走** `Launcher.shareApkFile(File)`，
而是 `OplusBaseSystemShortcut$AppShare#onClick` 内部自己构造 Intent，
直接用 `base.apk` 原路径：

```
content://com.android.launcher.oplus_file_provider/root/data/app/.../base.apk
```

所以必须 hook `AppShare.onClick`：

1. 反射取实例字段 `mItemInfo`、`mTarget`
2. 由 ItemInfo → 包名 → `sourceDir`
3. 复制为「应用名_版本名 (版本号).apk」
4. 模块自己 `startActivity(chooser)`
5. 直接 return，**不调用原生 proceed()**

注意：libxposed 的 `Chain` **没有 `setArgs`**，只有
`getArg/getArgs/getExecutable/getThisObject/proceed/proceedWith`。
想改参数必须用 `proceedWith(Object[])`。

### 1. Hook 点

```
com.android.launcher.pagepreview.PagePreviewButtonContainer#onButtonLayoutInflateFinished()V
```

### 2. 容器选择（关键，踩过坑）

视图层级：

```
PagePreviewButtonContainer (LinearLayout, 水平)
└── ConstraintLayout            <-- 真正的容器
    ├── ll_form_folder
    └── ll_remove
```

必须把按钮加在 `ll_form_folder.getParent()` 上。
如果加到 Hook 回调的 `thisObject`（那个 LinearLayout）上，会变成三份并排导致溢出屏幕。

### 3. 布局重排（ConstraintSet）

```java
ConstraintSet cs = new ConstraintSet();
cs.clone(constraintLayout);              // 参数是 ConstraintLayout，不是 ViewGroup
cs.connect(shareId, START, folderId, END, 0);
cs.connect(shareId, END, removeId, START, 0);
cs.applyTo(constraintLayout);             // 参数也是 ConstraintLayout
```

`ConstraintLayout.LayoutParams` 约束字段的真实名称（反射用）：

```
startToEnd, startToStart, endToStart, endToEnd,
leftToRight, leftToLeft, rightToLeft, rightToRight,
topToTop, topToBottom, bottomToTop
```

**没有** `startToEndOf` / `endToStartOf` 这种带 `Of` 后缀的写法。

`ConstraintSet.clone` 的真实重载：

```
clone(Context), clone(ConstraintLayout), clone(ConstraintSet), clone(Constraints)
```

### 4. 分享实现

1. `BatchDragViewManager.getSelectedViewList()` 取选中列表
2. 每项 `getTag()` 拿 ItemInfo，`ShortcutContainerInfo` 需先 `getApplicationItem()`
3. `getTargetComponent().getPackageName()` 拿包名
4. `getApplicationInfo(pkg, 0).sourceDir` 拿 APK 路径
5. **复制到 cache/share_apk/ 并重命名为「应用名.apk」**
6. `FileProvider.getUriForFile()` 生成 content URI
7. 组 Intent 分享

FileProvider authority 不要硬编码，从已注册 provider 里找：

```java
PackageInfo pi = pm.getPackageInfo(pkgName, GET_PROVIDERS);
// 优先找 OplusShareFileProvider
```

实际值：`com.android.launcher.oplus_file_provider`

### 5. 批量卸载

容器类 `PagePreviewButtonContainer` 上有 `uninstallApps()` 方法，但是它：

- 声明在父类 `AdaptiveScreenRotateLinearLayout` 链上
- 用 `getDeclaredMethods()` 找不到

必须用 `getMethods()`（含继承）或逐级遍历父类。

备选入口：Launcher 的 `showUninstallPanel()` / `startActivityForUninstall()` / `uninstallOrDisableApplication()`

## 线程模型（v6.0 起）

分享流程拆成两段，避免主线程做 IO：

```
主线程
  └─ doShare()
       仅遍历选中项收集包名（纯内存操作，很快）
       └─ 启动 "lshare-copy" 子线程
子线程
  ├─ findAuthority()   探测 FileProvider authority
  ├─ cleanCache()      删除超过 7 天的旧缓存
  ├─ copyFile()        8KB buffer 流式复制
  ├─ FileProvider.getUriForFile()
  └─ runOnUiThread → startActivity(chooser)
```

缓存文件命名带包名后缀，避免多开分身互相覆盖：

```
cache/share_apk/微信_com.tencent.mm.apk
cache/share_apk/微信_com.tencent.mm.dual.apk
```

## 可调参数

`MainHook.java` 顶部常量：

| 常量 | 默认值 | 说明 |
|---|---|---|
| `GAP_DP` | 32 | 三个按钮之间的间距（dp） |
| `CACHE_MAX_AGE_MS` | 7 天 | 分享缓存保留时长 |
| `FLAG_SYSTEM_APP` | 0x81 | FLAG_SYSTEM \| FLAG_UPDATED_SYSTEM_APP，用于跳过系统应用 |

## 编译

签名口令不进源码，必须通过环境变量传入：

```bash
export KS_PATH=/path/to/your.keystore
export KS_PASS=你的密码
export KS_ALIAS=你的别名
```

然后编译：

```bash
chmod +x build.sh
./build.sh
```

输出：`build/LShareMod.apk`

依赖（已验证可用）：

- `/opt/android-sdk/platforms/android-36/android.jar`
- `build-tools/34.0.0/aapt2`（36/37 版 aapt2 在 ARM 上会 Illegal instruction）
- `build-tools/36.0.0/d8`
- `build-tools/37.0.0/zipalign` + `apksigner`（34 版的 zipalign 也会崩）


## 安装

```bash
cp build/LShareMod.apk /data/local/tmp/m.apk
chmod 644 /data/local/tmp/m.apk
pm install -r -t /data/local/tmp/m.apk
```

LSPosed 里启用模块并勾选「系统桌面」作用域，然后重启桌面：

```bash
am force-stop com.android.launcher
am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

## 日志

TAG 为 `OperitShareMod`。

```bash
ls -t /data/adb/lspd/log/verbose_*.log | head -1 | xargs grep OperitShareMod
```

## 已知问题

v5.4 起按钮间距由 `GAP_DP` 控制；若桌面更新后仍被 `setItemMaxWidth()` 覆盖，
布局监听最多重新应用 6 次。

## 变更记录

| 版本 | 说明 |
|---|---|
| 6.9+ | 分享文件名改为与 Appkit 默认规则一致：「应用名_版本名 (版本号).apk」，非法字符直接删除 |
| v6.1 | 分享文件名恢复为「应用名.apk」，改用按包名分子目录防覆盖，不再加包名后缀 |
| v6.9 | 长按分享改 hook AppShare.onClick，文件名统一「应用名_版本号.apk」 |
| v6.8 | Chain 改用 proceedWith 传参 |
| v6.7 | 修 Chain.getArgs 返回 List 的类型转换 |
| v6.6 | 增加长按分享 hook（shareApkFile，实测不触发） |
| v6.5 | 文件名加版本号 |
| v6.4 | 修正 FileProvider authority 匹配 androidx 实现 |
| v6.3 | 分享目录改 CE 存储 |
| v6.0 | 分享改子线程、文件名防覆盖、缓存自动清理、密钥改环境变量 |
| v5.9 | 清理死代码，653 行 → 587 行，APK 25KB → 21KB |
| v5.8 | GAP_DP=32dp，按钮间距定稿 |
| v5.7 | GAP_DP=28 |
| v5.6 | GAP_DP=24 |
| v5.5 | GAP_DP=20 |
| v5.4 | 三按钮改为 packed 链居中靠拢（全 wrap_content，间距 GAP_DP=32dp），并监听布局变化抵抗 setItemMaxWidth 重设 |
| v5.3 | 卸载改用 getMethods() 查找，修复找不到 uninstallApps |
| v5.2 | 增加方法名诊断 |
| v5.1 | 卸载入口改为长按「移除」，移除独立按钮，恢复三按钮布局 |
| v5.0 | 增加独立卸载按钮（布局混乱，已废弃） |
| v4.4 | 补回被误删的 getUriForFile 逻辑 |
| v4.3 | safeName 改用字符码点判断，避免正则转义问题 |
| v4.0 | 改用 ConstraintSet 三段布局 |
| v2.0 | 迁移到 libxposed API |
| v1.0 | 初版 |
