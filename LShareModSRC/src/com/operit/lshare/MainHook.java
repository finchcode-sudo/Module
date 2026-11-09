package com.operit.lshare;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public class MainHook extends XposedModule {

    private static final String TAG = "OperitShareMod";
    private static final String PKG_LAUNCHER = "com.android.launcher";
    private static final int ID_SHARE_BTN = 0x7ffe0f01;
    private static final int ID_UNINSTALL_BTN = 0x7ffe0f02;

    /** ApplicationInfo.FLAG_SYSTEM | FLAG_UPDATED_SYSTEM_APP */
    private static final int FLAG_SYSTEM_APP = 0x81;
    /** 缓存目录最大保留时间：7 天 */
    private static final long CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        SELF.set(this);
        log(android.util.Log.INFO, TAG, "ready: " + pkg);
        if (!PKG_LAUNCHER.equals(pkg)) return;

        ClassLoader cl = param.getClassLoader();
        try {
            Class<?> cls = Class.forName("com.android.launcher.pagepreview.PagePreviewButtonContainer", false, cl);
            Method m = null;
            for (Method mm : cls.getDeclaredMethods()) {
                if (mm.getName().equals("onButtonLayoutInflateFinished")) { m = mm; break; }
            }
            if (m == null) { log(android.util.Log.ERROR, TAG, "method not found"); return; }
            final Method target = m;
            Object hb = null;
            // 逐级探测 hook(Executable) 的真实重载
            for (Class<?> c = this.getClass(); c != null && hb == null; c = c.getSuperclass()) {
                for (Method hm : c.getDeclaredMethods()) {
                    if (!hm.getName().equals("hook")) continue;
                    if (hm.getParameterTypes().length != 1) continue;
                    hm.setAccessible(true);
                    try {
                        hb = hm.invoke(this, target);
                        log(android.util.Log.INFO, TAG, "hook overload: " + hm);
                        if (hb != null) break;
                    } catch (Throwable ignored) {}
                }
            }
            if (hb == null) { log(android.util.Log.ERROR, TAG, "hook builder null"); return; }

            log(android.util.Log.INFO, TAG, "builder class: " + hb.getClass().getName());
            Object handle = null;
            for (Method im : hb.getClass().getMethods()) {
                if (!im.getName().equals("intercept")) continue;
                if (im.getParameterTypes().length != 1) continue;
                Class<?> pt = im.getParameterTypes()[0];
                Object hooker = makeHookerFor(pt);
                if (hooker == null) continue;
                im.setAccessible(true);
                try {
                    handle = im.invoke(hb, hooker);
                    log(android.util.Log.INFO, TAG, "intercept via " + im + " -> " + handle);
                    break;
                } catch (Throwable e) {
                    log(android.util.Log.ERROR, TAG, "intercept fail: " + e);
                }
            }
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "hook error: " + t);
        }

        hookNativeShare(cl, param.getClassLoader());
    }

    /**
     * 拦截原生单图标长按菜单的分享入口，让文件名同样带上版本号。
     * 目标：Launcher.shareApkFile(File)
     */
    private void hookNativeShare(ClassLoader cl, ClassLoader paramCl) {
        try {
            Class<?> clsLauncher = Class.forName("com.android.launcher.Launcher", false, cl);
            Method target = null;
            for (Class<?> c = clsLauncher; c != null && target == null; c = c.getSuperclass()) {
                for (Method mm : c.getDeclaredMethods()) {
                    if (mm.getName().equals("shareApkFile")
                            && mm.getParameterTypes().length == 1
                            && mm.getParameterTypes()[0] == File.class) {
                        target = mm; break;
                    }
                }
            }
            if (target == null) { log(android.util.Log.ERROR, TAG, "shareApkFile not found"); return; }

            final Method tm = target;
            doHook(target, new HookHandler() {
                @Override public void handle(Object chain) throws Throwable {
                    try {
                        File orig = null;
                        Object self = invokeNoArg(chain, "getThisObject");
                        Object raw = invokeNoArg(chain, "getArgs");
                        if (raw instanceof java.util.List) {
                            java.util.List<?> l = (java.util.List<?>) raw;
                            if (l.size() == 1 && l.get(0) instanceof File) orig = (File) l.get(0);
                        } else if (raw instanceof Object[]) {
                            Object[] a = (Object[]) raw;
                            if (a.length == 1 && a[0] instanceof File) orig = (File) a[0];
                        }

                        if (orig != null) {
                            File renamed = renameForShare((Activity) self, orig);
                            if (renamed != null && renamed != orig) {
                                // Chain 不支持改参数，必须用 proceedWith 传入新参数
                                boolean ok = proceedWith(chain, new Object[]{renamed});
                                if (ok) {
                                    log(android.util.Log.INFO, TAG, "proceedWith renamed: " + renamed.getName());
                                    return;
                                }
                                log(android.util.Log.ERROR, TAG, "proceedWith failed");
                            }
                        }
                    } catch (Throwable t) {
                        log(android.util.Log.ERROR, TAG, "shareApkFile patch fail: " + t);
                    }
                    invokeNoArg(chain, "proceed");
                }
            });
            log(android.util.Log.INFO, TAG, "hooked " + tm);
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "hookNativeShare fail: " + t);
        }

        hookAppShareOnClick(cl);
    }

    /**
     * 长按菜单的「分享」实际走 OplusBaseSystemShortcut.AppShare#onClick，
     * 它自己构造 Intent 用的是 base.apk，所以必须在 onClick 前替换 mItemInfo 对应的源文件。
     * 做法：hook onClick，进入前把 AppShare 的 mItemInfo 临时换成一个指向副本的 ItemInfo，
     *      proceed 后再还原。更简单可靠的做法是直接拦 Launcher.shareApkFile 之外的
     *      “构造 Intent”点，这里选择接管 onClick，自行拉起带重命名的分享。
     */
    private void hookAppShareOnClick(ClassLoader cl) {
        try {
            Class<?> cls = Class.forName(
                "com.android.launcher3.popup.OplusBaseSystemShortcut$AppShare", false, cl);
            Method onClick = null;
            for (Class<?> c = cls; c != null && onClick == null; c = c.getSuperclass()) {
                for (Method mm : c.getDeclaredMethods()) {
                    if (mm.getName().equals("onClick")
                            && mm.getParameterTypes().length == 1
                            && View.class.isAssignableFrom(mm.getParameterTypes()[0])) {
                        onClick = mm; break;
                    }
                }
            }
            if (onClick == null) { log(android.util.Log.ERROR, TAG, "AppShare.onClick not found"); return; }

            doHook(onClick, new HookHandler() {
                @Override public void handle(Object chain) throws Throwable {
                    try {
                        Object self = invokeNoArg(chain, "getThisObject");
                        if (self != null) {
                            Object itemInfo = getField(self, "mItemInfo");
                            Object target = getField(self, "mTarget");
                            if (itemInfo != null && target instanceof Activity) {
                                Activity act = (Activity) target;
                                final File apk = apkFileOf(act, itemInfo);
                                if (apk != null && itemHasSplits(act, itemInfo)) {
                                    // 拆分包应用要打 .apks，耗时较长，放子线程，避免卡住桌面主线程
                                    final Activity actF = act;
                                    toastMain(actF, "正在打包…");
                                    new Thread(new Runnable() {
                                        @Override public void run() {
                                            final File out = renameForShare(actF, apk);
                                            actF.runOnUiThread(new Runnable() {
                                                @Override public void run() {
                                                    if (out == null || out == apk) {
                                                        toastMain(actF, "打包失败");
                                                    } else {
                                                        shareFiles(actF, new File[]{out});
                                                    }
                                                }
                                            });
                                        }
                                    }, "lshare-apks").start();
                                    log(android.util.Log.INFO, TAG, "AppShare handled by module (apks)");
                                    return;
                                }
                                if (apk != null) {
                                    File renamed = renameForShare(act, apk);
                                    if (renamed != null && renamed != apk) {
                                        // 自行拉起分享，带重命名副本；然后返回不执行原生逻辑
                                        shareFiles(act, new File[]{renamed});
                                        log(android.util.Log.INFO, TAG, "AppShare handled by module");
                                        return;
                                    }
                                }
                            }
                        }
                    } catch (Throwable t) {
                        log(android.util.Log.ERROR, TAG, "AppShare patch fail: " + t);
                    }
                    invokeNoArg(chain, "proceed");
                }
            });
            log(android.util.Log.INFO, TAG, "hooked " + onClick);
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "hookAppShareOnClick fail: " + t);
        }
    }

    /** ItemInfo 对应的应用是否带拆分包 */
    private static boolean itemHasSplits(Activity act, Object itemInfo) {
        try {
            Object comp = call(itemInfo, "getTargetComponent");
            if (comp == null) return false;
            String pkg = (String) call(comp, "getPackageName");
            if (pkg == null) return false;
            return hasSplits(act.getPackageManager().getApplicationInfo(pkg, 0));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从 ItemInfo 取 APK 文件 */
    private static File apkFileOf(Activity act, Object itemInfo) {
        try {
            Object comp = call(itemInfo, "getTargetComponent");
            if (comp == null) return null;
            String pkg = (String) call(comp, "getPackageName");
            if (pkg == null) return null;
            android.content.pm.ApplicationInfo ai = act.getPackageManager().getApplicationInfo(pkg, 0);
            if (ai == null || ai.sourceDir == null) return null;
            if ((ai.flags & FLAG_SYSTEM_APP) != 0) return null;
            File f = new File(ai.sourceDir);
            return f.exists() ? f : null;
        } catch (Throwable t) {
            slog(android.util.Log.ERROR, TAG, "apkFileOf fail: " + t);
            return null;
        }
    }

    /** 自行拉起分享 */
    private static void shareFiles(Activity act, File[] files) {
        try {
            String authority = findAuthority(act);
            if (authority == null) { toastMain(act, "未找到 FileProvider"); return; }
            Class<?> clsFP = Class.forName("androidx.core.content.FileProvider", false,
                    act.getClassLoader());
            Method getUri = null;
            for (Method mm : clsFP.getDeclaredMethods()) {
                if (mm.getName().equals("getUriForFile")) { getUri = mm; break; }
            }
            if (getUri == null) { toastMain(act, "FileProvider 不可用"); return; }
            getUri.setAccessible(true);

            ArrayList<Uri> uris = new ArrayList<Uri>();
            for (File f : files) {
                Uri u = (Uri) getUri.invoke(null, act, authority, f);
                if (u != null) uris.add(u);
            }
            if (uris.isEmpty()) return;

            Intent intent;
            if (uris.size() == 1) {
                intent = new Intent(Intent.ACTION_SEND);
                intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            } else {
                intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                intent.putExtra(Intent.EXTRA_STREAM, uris);
            }
            boolean anyApks = false;
            for (File f : files) if (f.getName().endsWith(".apks")) { anyApks = true; break; }
            intent.setType(anyApks ? "*/*" : "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            int titleId = resId(act, "share_action", "string");
            String title = titleId != 0 ? act.getString(titleId) : "分享";
            act.startActivity(Intent.createChooser(intent, title));
            slog(android.util.Log.INFO, TAG, "shareFiles ok: " + uris.size());
        } catch (Throwable t) {
            slog(android.util.Log.ERROR, TAG, "shareFiles fail: " + t);
        }
    }

    /** 反射读字段 */
    private static Object getField(Object obj, String name) {
        if (obj == null) return null;
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 把 base.apk 改名为「应用名_版本名 (版本号).apk」副本（带拆分包则打成 .apks），返回新文件；失败返回原文件 */
    private static File renameForShare(Activity act, File orig) {
        try {
            if (orig == null) return null;
            File dir = resolveShareDir(act);
            if (dir == null) return orig;

            // 通过 APK 路径反查包名
            String path = orig.getAbsolutePath();
            String pkg = null;
            for (android.content.pm.ApplicationInfo a : act.getPackageManager()
                    .getInstalledApplications(0)) {
                if (a.sourceDir != null && a.sourceDir.equals(path)) { pkg = a.packageName; break; }
            }
            if (pkg == null) { slog(android.util.Log.ERROR, TAG, "pkg not found for " + path); return orig; }

            android.content.pm.ApplicationInfo ai = act.getPackageManager().getApplicationInfo(pkg, 0);
            File out = prepareShareFile(act, pkg, ai, dir);
            if (out != null) {
                slog(android.util.Log.INFO, TAG, "native share renamed -> " + out.getName());
                return out;
            }
        } catch (Throwable t) {
            slog(android.util.Log.ERROR, TAG, "renameForShare fail: " + t);
        }
        return orig;
    }

    private void inject(ViewGroup root) {
        if (root == null) return;
        if (root.findViewById(ID_SHARE_BTN) != null) return;

        final Context ctx = root.getContext();
        int idFolder = resId(ctx, "ll_form_folder", "id");
        int idRemove = resId(ctx, "ll_remove", "id");
        if (idFolder == 0 || idRemove == 0) { log(android.util.Log.ERROR, TAG, "ids missing"); return; }

        final View llFolder = root.findViewById(idFolder);
        final View llRemove = root.findViewById(idRemove);
        if (llFolder == null || llRemove == null) { log(android.util.Log.ERROR, TAG, "views null"); return; }

        // 真正的 ConstraintLayout 是 llFolder 的 parent，不是 root(LinearLayout)
        ViewGroup host = root;
        try {
            android.view.ViewParent vp = llFolder.getParent();
            if (vp instanceof ViewGroup) host = (ViewGroup) vp;
            log(android.util.Log.INFO, TAG, "root=" + root.getClass().getSimpleName()
                + " host=" + host.getClass().getName());
        } catch (Throwable ignored) {}
        final ViewGroup container = host;

        int layoutItem = resId(ctx, "toggle_bar_main_ui_item", "layout");
        if (layoutItem == 0) { log(android.util.Log.ERROR, TAG, "layout missing"); return; }

        View btn = LayoutInflater.from(ctx).inflate(layoutItem, root, false);
        btn.setId(ID_SHARE_BTN);

        int idIcon = resId(ctx, "item_icon", "id");
        if (idIcon != 0) {
            ImageView iv = (ImageView) btn.findViewById(idIcon);
            int dw = resId(ctx, "launcher_system_shortcut_ic_app_share", "drawable");
            if (iv != null && dw != 0) {
                try { iv.setImageDrawable(ctx.getDrawable(dw)); } catch (Throwable ignored) {}
            }
        }
        int idTitle = resId(ctx, "title", "id");
        if (idTitle != 0) {
            TextView tv = (TextView) btn.findViewById(idTitle);
            if (tv != null) {
                int strId = resId(ctx, "share_action", "string");
                tv.setText(strId != 0 ? ctx.getString(strId) : "\u5206\u4eab");
            }
        }

        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doShare(root); }
        });

        // 方案：布局顺序变为 [组成文件夹][分享][移除]
        // 1) 新按钮约束在 folder.end 与 remove.start 之间，宽 0dp
        // 2) 用 ConstraintSet 把 remove.start 重接到 share.end
        //    只改约束关系，不替换任何原按钮的 LayoutParams -> 不触发 onMeasure 崩溃
        try {
            Class<?> clzLP = Class.forName(
                "androidx.constraintlayout.widget.ConstraintLayout$LayoutParams", false,
                container.getClass().getClassLoader());
            Object lp = clzLP.getConstructor(int.class, int.class)
                    .newInstance(0, ViewGroup.LayoutParams.WRAP_CONTENT);
            setF(lp, "startToEnd", Integer.valueOf(llFolder.getId()));
            setF(lp, "leftToRight", Integer.valueOf(llFolder.getId()));
            setF(lp, "endToStart", Integer.valueOf(llRemove.getId()));
            setF(lp, "rightToLeft", Integer.valueOf(llRemove.getId()));
            setF(lp, "topToTop", Integer.valueOf(0));
            container.addView(btn, (ViewGroup.LayoutParams) lp);
            log(android.util.Log.INFO, TAG, "added between folder and remove");

            // 三个按钮打包居中靠拢：[文件夹][分享][移除]
            final int fId = llFolder.getId();
            final int sId = btn.getId();
            final int rId = llRemove.getId();
            applyPackedLayout(container, fId, sId, rId);
            watchLayout(container, llFolder, btn, fId, sId, rId);
        } catch (Throwable e) {
            log(android.util.Log.ERROR, TAG, "add fail: " + e);
            int sw0 = root.getResources().getDisplayMetrics().widthPixels;
            container.addView(btn, new ViewGroup.LayoutParams(sw0 / 4, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        log(android.util.Log.INFO, TAG, "share button injected");

        // 长按「移除」= 批量卸载
        try {
            llRemove.setLongClickable(true);
            llRemove.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    doUninstall(root);
                    return true;
                }
            });
            log(android.util.Log.INFO, TAG, "long-press remove -> uninstall ready");
        } catch (Throwable tl) {
            log(android.util.Log.ERROR, TAG, "longclick fail: " + tl);
        }
    }


    /** 三按钮之间的间距(dp)，想更紧/更松改这里 */
    private static final int GAP_DP = 32;

    /**
     * 用 ConstraintSet 把三个按钮做成「packed 链」居中：
     * 全部 wrap_content，不再让分享按钮 0dp 撑满中间（这是原先间距过大的根因）。
     */
    private static boolean applyPackedLayout(ViewGroup container, int fId, int sId, int rId) {
        try {
            ClassLoader cl = container.getClass().getClassLoader();
            Class<?> clzSet = Class.forName("androidx.constraintlayout.widget.ConstraintSet", false, cl);
            Class<?> clzCL = Class.forName("androidx.constraintlayout.widget.ConstraintLayout", false, cl);
            Object cs = clzSet.getDeclaredConstructor().newInstance();
            java.lang.reflect.Method cm = clzSet.getDeclaredMethod("clone", clzCL);
            cm.setAccessible(true);
            cm.invoke(cs, container);

            java.lang.reflect.Method connect = clzSet.getMethod(
                "connect", int.class, int.class, int.class, int.class, int.class);
            java.lang.reflect.Method cw = clzSet.getMethod("constrainWidth", int.class, int.class);
            java.lang.reflect.Method setMargin = clzSet.getMethod(
                "setMargin", int.class, int.class, int.class);

            final int START = 6, END = 7, TOP = 3, PARENT = 0;
            int wrap = ViewGroup.LayoutParams.WRAP_CONTENT;
            cw.invoke(cs, fId, wrap);
            cw.invoke(cs, sId, wrap);
            cw.invoke(cs, rId, wrap);

            // folder -> share -> remove，首尾接 parent，形成水平链
            connect.invoke(cs, fId, START, PARENT, START, 0);
            connect.invoke(cs, fId, END, sId, START, 0);
            connect.invoke(cs, sId, START, fId, END, 0);
            connect.invoke(cs, sId, END, rId, START, 0);
            connect.invoke(cs, rId, START, sId, END, 0);
            connect.invoke(cs, rId, END, PARENT, END, 0);
            connect.invoke(cs, sId, TOP, PARENT, TOP, 0);

            // 链头设为 packed，整体居中
            clzSet.getMethod("setHorizontalChainStyle", int.class, int.class)
                  .invoke(cs, fId, 2 /* CHAIN_PACKED */);
            clzSet.getMethod("setHorizontalBias", int.class, float.class)
                  .invoke(cs, fId, 0.5f);

            // 按钮之间的间距
            int gap = (int) (container.getResources().getDisplayMetrics().density * GAP_DP);
            setMargin.invoke(cs, sId, START, gap);
            setMargin.invoke(cs, rId, START, gap);

            clzSet.getMethod("applyTo", clzCL).invoke(cs, container);
            android.util.Log.i(TAG, "packed layout applied gapDp=" + GAP_DP);
            return true;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "packed layout fail: " + t);
            return false;
        }
    }

    /**
     * 桌面的 PagePreviewButtonContainer.setItemMaxWidth() 会在布局完成后重设宽度/边距，
     * 把我们的设置冲掉。这里监听布局变化，发现间距又变大就重新应用（最多 6 次防止死循环）。
     */
    private static void watchLayout(final ViewGroup container, final View folder, final View share,
                                    final int fId, final int sId, final int rId) {
        final int[] left = {6};
        final boolean[] busy = {false};
        container.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                if (busy[0] || left[0] <= 0) return;
                if (folder.getWidth() == 0 || share.getWidth() == 0) return;
                int tol = (int) (container.getResources().getDisplayMetrics().density * (GAP_DP + 6));
                int realGap = share.getLeft() - folder.getRight();
                if (realGap <= tol) return;
                busy[0] = true;
                left[0]--;
                container.post(new Runnable() {
                    @Override public void run() {
                        try { applyPackedLayout(container, fId, sId, rId); }
                        finally { busy[0] = false; }
                    }
                });
            }
        });
    }

    private static void setF(Object lp, String name, Object val) {
        String[] cands = new String[]{
            name,
            "layout_constraint" + name,
            "m" + Character.toUpperCase(name.charAt(0)) + name.substring(1)
        };
        for (String n : cands) {
            try {
                java.lang.reflect.Field f = lp.getClass().getField(n);
                f.set(lp, val);
                return;
            } catch (Throwable ignored) {}
        }
        // 尝试所有父类与声明字段
        for (Class<?> c = lp.getClass(); c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (!f.getName().equalsIgnoreCase(name)) continue;
                try {
                    f.setAccessible(true);
                    f.set(lp, val);
                    return;
                } catch (Throwable ignored) {}
            }
        }
    }

    private void doUninstall(ViewGroup root) {
        try {
            Activity act = (Activity) root.getContext();
            Method target = null;
            Object targetObj = null;

            // 1) 先在容器上找 uninstallApps（包括继承方法）
            for (Method mm : root.getClass().getMethods()) {
                if (mm.getName().equals("uninstallApps")) {
                    target = mm; targetObj = root; break;
                }
            }
            if (target == null) {
                for (Class<?> c = root.getClass(); c != null && target == null; c = c.getSuperclass()) {
                    for (Method mm : c.getDeclaredMethods()) {
                        if (mm.getName().equals("uninstallApps")) {
                            target = mm; targetObj = root; break;
                        }
                    }
                }
            }

            // 2) 容器上找不到，退到 Launcher 的 showUninstallPanel
            if (target == null) {
                for (Method mm : act.getClass().getMethods()) {
                    if (mm.getName().equals("showUninstallPanel")) {
                        target = mm; targetObj = act; break;
                    }
                }
            }

            if (target == null) {
                log(android.util.Log.ERROR, TAG, "no uninstall entry");
                toast(act, "当前桌面不支持批量卸载");
                return;
            }

            target.setAccessible(true);
            log(android.util.Log.INFO, TAG, "invoke " + target.getName()
                + " params=" + target.getParameterTypes().length);
            if (target.getParameterTypes().length == 0) {
                target.invoke(targetObj);
            } else {
                log(android.util.Log.ERROR, TAG, "need params, skip");
                toast(act, "卸载方法需要参数");
                return;
            }
            log(android.util.Log.INFO, TAG, "uninstall invoked ok");
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "doUninstall fail: " + t);
            try { toast((Activity) root.getContext(), "卸载调用失败"); } catch (Throwable ig) {}
        }
    }

    private void doShare(final ViewGroup root) {
        final Activity act = (Activity) root.getContext();
        Object mgr = call(act, "getBatchDragViewManager");
        if (mgr == null) { toast(act, "无法获取选中列表"); return; }
        List<?> sel = (List<?>) call(mgr, "getSelectedViewList");
        if (sel == null || sel.isEmpty()) { toast(act, "请先选择应用"); return; }

        // 主线程：只收集包名（快）
        final ArrayList<String> pkgs = new ArrayList<String>();
        for (int i = 0; i < sel.size(); i++) {
            Object tag = call(sel.get(i), "getTag");
            if (tag == null) continue;
            if (tag.getClass().getName().endsWith("ShortcutContainerInfo")) {
                Object app = call(tag, "getApplicationItem");
                if (app != null) tag = app;
            }
            Object comp = call(tag, "getTargetComponent");
            if (comp == null) continue;
            String pkg = (String) call(comp, "getPackageName");
            if (pkg != null) pkgs.add(pkg);
        }
        if (pkgs.isEmpty()) { toast(act, "所选项目不含应用"); return; }

        // 子线程：复制 APK / 打包 .apks + 生成 URI（IO 重活）
        final boolean[] hasApks = new boolean[1];
        new Thread(new Runnable() {
            @Override public void run() {
                final ArrayList<Uri> uris = new ArrayList<Uri>();
                try {
                    String authority = findAuthority(act);
                    if (authority == null) { toastMain(act, "未找到 FileProvider"); return; }
                    Class<?> clsFP = Class.forName("androidx.core.content.FileProvider", false,
                            act.getClassLoader());
                    Method getUri = null;
                    for (Method mm : clsFP.getDeclaredMethods()) {
                        if (mm.getName().equals("getUriForFile")) { getUri = mm; break; }
                    }
                    if (getUri == null) { toastMain(act, "FileProvider 不可用"); return; }
                    getUri.setAccessible(true);

                    File dir = resolveShareDir(act);
                    if (dir == null) { toastMain(act, "无法创建分享目录"); return; }
                    cleanCache(dir);

                    for (int i = 0; i < pkgs.size(); i++) {
                        String pkg = pkgs.get(i);
                        android.content.pm.ApplicationInfo ai;
                        try { ai = act.getPackageManager().getApplicationInfo(pkg, 0); }
                        catch (Throwable e) { continue; }
                        if (ai == null || ai.sourceDir == null) continue;
                        if ((ai.flags & FLAG_SYSTEM_APP) != 0) continue;
                        File src = new File(ai.sourceDir);
                        if (!src.exists() || !src.isFile()) continue;

                        File f = src;
                        boolean split = hasSplits(ai);
                        try {
                            // 文件名格式与 Appkit 默认导出规则一致：应用名_版本名 (版本号).apk
                            // 带拆分包的应用打成 .apks，否则对方装不上
                            File got = prepareShareFile(act, pkg, ai, dir);
                            if (got != null) {
                                f = got;
                                if (split) hasApks[0] = true;
                            } else if (split) {
                                log(android.util.Log.ERROR, TAG, "apks build fail, skip " + pkg);
                                toastMain(act, "打包失败：" + pkg);
                                continue;
                            }
                        } catch (Throwable t) {
                            log(android.util.Log.ERROR, TAG, "copy fail " + pkg + ": " + t);
                            if (split) continue;
                        }

                        try {
                            Uri uri = (Uri) getUri.invoke(null, act, authority, f);
                            if (uri != null) uris.add(uri);
                        } catch (Throwable t) {
                            log(android.util.Log.ERROR, TAG, "uri fail " + pkg + ": " + t);
                        }
                    }
                } catch (Throwable t) {
                    log(android.util.Log.ERROR, TAG, "doShare bg fail: " + t);
                }

                // 回主线程拉起分享面板
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (uris.isEmpty()) { toast(act, "所选应用均不可分享（系统应用或无 APK）"); return; }
                        try {
                            Intent intent;
                            if (uris.size() == 1) {
                                intent = new Intent(Intent.ACTION_SEND);
                                intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                            } else {
                                intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                                intent.putExtra(Intent.EXTRA_STREAM, uris);
                            }
                            // 含 .apks 时不能用 apk 的 MIME，否则会被路由到系统安装器
                            intent.setType(hasApks[0] ? "*/*" : "application/vnd.android.package-archive");
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            int titleId = resId(act, "share_action", "string");
                            String title = titleId != 0 ? act.getString(titleId) : "分享";
                            act.startActivity(Intent.createChooser(intent, title));
                            log(android.util.Log.INFO, TAG, "shared " + uris.size());
                        } catch (Throwable t) {
                            log(android.util.Log.ERROR, TAG, "startActivity fail: " + t);
                        }
                    }
                });
            }
        }, "lshare-copy").start();
    }

    /** 从已注册 provider 中探测 FileProvider authority */
    private static String findAuthority(Activity act) {
        try {
            android.content.pm.PackageInfo pi = act.getPackageManager()
                    .getPackageInfo(act.getPackageName(),
                        android.content.pm.PackageManager.GET_PROVIDERS);
            if (pi == null || pi.providers == null) return null;
            // 必须匹配 androidx.core.content.FileProvider 这个实现类的 authority，
            // 因为后面用它的 getUriForFile() 生成 URI。
            // 用 OplusShareFileProvider 的 authority 会因路径映射表不同而解析失败。
            for (android.content.pm.ProviderInfo p : pi.providers) {
                if (p.name != null && p.name.equals("androidx.core.content.FileProvider")) {
                    slog(android.util.Log.INFO, TAG, "authority(androidx) = " + p.authority);
                    return p.authority;
                }
            }
            // 其次尝试同名后缀但排除 Oplus 专用实现
            for (android.content.pm.ProviderInfo p : pi.providers) {
                if (p.name == null) continue;
                if (p.name.endsWith("OplusShareFileProvider")) continue;
                if (p.name.endsWith("FileProvider")) {
                    slog(android.util.Log.INFO, TAG, "authority(fallback) = " + p.authority + " / " + p.name);
                    return p.authority;
                }
            }
        } catch (Throwable e) {
            slog(android.util.Log.ERROR, TAG, "authority probe fail: " + e);
        }
        return null;
    }

    /** 流式复制，不整块读入内存 */
    private static boolean copyFile(File src, File dst) {
        java.io.FileInputStream in = null;
        java.io.FileOutputStream out = null;
        try {
            in = new java.io.FileInputStream(src);
            out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            return dst.length() == src.length();
        } catch (Throwable t) {
            slog(android.util.Log.ERROR, TAG, "copyFile fail: " + t);
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 分享文件名，与 Appkit 默认导出规则一致：「应用名_版本名 (版本号).apk」
     * （Appkit 模板 ?N_?V (?C)）。非法字符直接删除而不是替换成下划线，与 Appkit 一致。
     * 取不到版本名时退化为「应用名 (版本号).apk」。
     * 带拆分包（splitSourceDirs 非空）的应用只分享 base.apk 会装不上（INSTALL_FAILED_MISSING_SPLIT），
     * 所以这类应用打成 .apks（base.apk + 全部 split），扩展名为 .apks。
     */
    private static String shareFileName(Activity act, String pkg, android.content.pm.ApplicationInfo ai) {
        CharSequence label = null;
        try { label = act.getPackageManager().getApplicationLabel(ai); } catch (Throwable ignored) {}
        String appName = removeIllegal(label == null ? "" : label.toString());
        if (appName.length() == 0) appName = removeIllegal(pkg);
        if (appName.length() == 0) appName = "app";

        String vn = null;
        int vc = 0;
        try {
            android.content.pm.PackageInfo pi = act.getPackageManager().getPackageInfo(pkg, 0);
            if (pi != null) {
                vn = pi.versionName;
                vc = pi.versionCode;
            }
        } catch (Throwable t) {
            slog(android.util.Log.ERROR, TAG, "shareFileName version fail " + pkg + ": " + t);
        }
        vn = vn == null ? "" : removeIllegal(vn);

        StringBuilder sb = new StringBuilder(appName);
        if (vn.length() > 0) sb.append('_').append(vn);
        if (vc > 0) sb.append(" (").append(vc).append(')');
        return sb.append(hasSplits(ai) ? ".apks" : ".apk").toString();
    }

    /** 与 Appkit EnvironmentUtil.removeIllegalFileNameCharacters 一致：直接删除 ? \ / : * " < > | */
    private static String removeIllegal(String in) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            boolean bad = (c == 0x3F) || (c == 0x5C) || (c == 0x2F) || (c == 0x3A) || (c == 0x2A)
                    || (c == 0x22) || (c == 0x3C) || (c == 0x3E) || (c == 0x7C) || (c < 32);
            if (!bad) sb.append(c);
        }
        return sb.toString().trim();
    }

    /** 应用是否带拆分包 */
    private static boolean hasSplits(android.content.pm.ApplicationInfo ai) {
        return ai != null && ai.splitSourceDirs != null && ai.splitSourceDirs.length > 0;
    }

    /**
     * 生成待分享文件（放在 dir/包名/ 下）：
     * 无拆分包 → 复制 base.apk；有拆分包 → 打成 .apks。失败返回 null。
     */
    private static File prepareShareFile(Activity act, String pkg,
                                         android.content.pm.ApplicationInfo ai, File dir) {
        File src = new File(ai.sourceDir);
        // 包名单独子目录，防同名不同包互相覆盖
        File pkgDir = new File(dir, safeName(pkg));
        if (!pkgDir.exists()) pkgDir.mkdirs();
        File dst = new File(pkgDir, shareFileName(act, pkg, ai));
        if (hasSplits(ai)) {
            return buildApks(src, ai.splitSourceDirs, dst) ? dst : null;
        }
        if (!dst.exists() || dst.length() != src.length()) {
            if (!copyFile(src, dst)) {
                // 复制失败就删掉残片，避免下次误用
                try { dst.delete(); } catch (Throwable ig) {}
                return null;
            }
        }
        // 只有尺寸完全一致才用缓存副本
        return (dst.exists() && dst.length() == src.length()) ? dst : null;
    }

    /**
     * 把 base.apk + 全部 split 打成 .apks（zip，根目录放 apk，与 Appkit 导出的 .apks 结构一致）。
     * apk 本身已是压缩包，不再压缩（level 0），速度快。先写 .tmp 再改名，避免留下残缺文件；
     * 目标文件比所有源文件都新时直接复用。
     */
    private static boolean buildApks(File base, String[] splits, File dst) {
        File tmp = new File(dst.getPath() + ".tmp");
        try {
            long newest = base.lastModified();
            for (String sp : splits) newest = Math.max(newest, new File(sp).lastModified());
            if (dst.exists() && dst.length() > 0 && dst.lastModified() >= newest) return true;
        } catch (Throwable ignored) {}

        java.util.zip.ZipOutputStream zos = null;
        try {
            zos = new java.util.zip.ZipOutputStream(new java.io.BufferedOutputStream(
                    new java.io.FileOutputStream(tmp), 65536));
            zos.setLevel(0);
            addZipEntry(zos, "base.apk", base);
            for (String sp : splits) {
                File f = new File(sp);
                if (f.exists() && f.isFile()) addZipEntry(zos, f.getName(), f);
            }
            zos.close();
            zos = null;
            if (dst.exists()) dst.delete();
            return tmp.renameTo(dst);
        } catch (Throwable t) {
            slog(android.util.Log.ERROR, TAG, "buildApks fail: " + t);
            try { tmp.delete(); } catch (Throwable ig) {}
            return false;
        } finally {
            if (zos != null) try { zos.close(); } catch (Throwable ignored) {}
        }
    }

    private static void addZipEntry(java.util.zip.ZipOutputStream zos, String name, File f) throws Exception {
        zos.putNextEntry(new java.util.zip.ZipEntry(name));
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
        zos.closeEntry();
    }

    /**
     * 选择分享文件目录。
     * 关键：必须放在 CE 存储（/data/user/0/），因为 OplusShareFileProvider
     * 的 paths 白名单基于 CE 路径。桌面是 Direct Boot 感知应用，
     * getCacheDir()/getNoBackupFilesDir() 默认返回 DE 存储（/data/user_de/0/），
     * 那些位置生成的 content URI 对方应用打不开，会退回 base.apk。
     */
    private static File resolveShareDir(Activity act) {
        File[] cands = new File[]{
            new File("/data/user/0/" + act.getPackageName() + "/cache/share_apk"),
            new File("/data/data/" + act.getPackageName() + "/cache/share_apk"),
            new File(act.getCacheDir(), "share_apk"),
        };
        for (File d : cands) {
            try {
                if (!d.exists() && !d.mkdirs()) continue;
                if (!d.isDirectory() || !d.canWrite()) continue;
                slog(android.util.Log.INFO, TAG, "share dir = " + d.getAbsolutePath());
                return d;
            } catch (Throwable t) {
                slog(android.util.Log.ERROR, TAG, "dir try fail " + d + ": " + t);
            }
        }
        return null;
    }

    /** 清理超过保留期的旧缓存（含按包名分的子目录，空目录一并删除） */
    private static void cleanCache(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            long now = System.currentTimeMillis();
            for (File f : files) {
                if (f.isDirectory()) {
                    cleanCache(f);
                    String[] left = f.list();
                    if (left == null || left.length == 0) f.delete();
                } else if (now - f.lastModified() > CACHE_MAX_AGE_MS) {
                    if (f.delete()) slog(android.util.Log.INFO, TAG, "cache purged: " + f.getName());
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 任意线程弹 toast */
    private static void toastMain(final Activity act, final String msg) {
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                try { android.widget.Toast.makeText(act, msg, android.widget.Toast.LENGTH_SHORT).show(); }
                catch (Throwable ignored) {}
            }
        });
    }


    /** Hook 回调处理器 */
    private interface HookHandler {
        void handle(Object chain) throws Throwable;
    }

    /** 通用 hook：反射调用 libxposed 的 hook().intercept() */
    private Object doHook(java.lang.reflect.Executable target, final HookHandler handler) {
        try {
            Object hb = null;
            for (Class<?> c = this.getClass(); c != null && hb == null; c = c.getSuperclass()) {
                for (Method hm : c.getDeclaredMethods()) {
                    if (!hm.getName().equals("hook")) continue;
                    if (hm.getParameterTypes().length != 1) continue;
                    hm.setAccessible(true);
                    try {
                        hb = hm.invoke(this, target);
                        if (hb != null) break;
                    } catch (Throwable ignored) {}
                }
            }
            if (hb == null) { log(android.util.Log.ERROR, TAG, "hook builder null"); return null; }
            for (Method im : hb.getClass().getMethods()) {
                if (!im.getName().equals("intercept")) continue;
                if (im.getParameterTypes().length != 1) continue;
                Object hooker = makeHookerWith(im.getParameterTypes()[0], handler);
                if (hooker == null) continue;
                im.setAccessible(true);
                try { return im.invoke(hb, hooker); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG, "doHook fail: " + t);
        }
        return null;
    }

    private static Object makeHookerWith(Class<?> iface, final HookHandler handler) {
        try {
            if (iface == null || !iface.isInterface()) return null;
            return java.lang.reflect.Proxy.newProxyInstance(
                iface.getClassLoader(), new Class<?>[]{iface},
                new java.lang.reflect.InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method method, Object[] a) throws Throwable {
                        if (!method.getName().equals("intercept")) return defaultValue(method.getReturnType());
                        Object chain = (a != null && a.length > 0) ? a[0] : null;
                        handler.handle(chain);
                        return defaultValue(method.getReturnType());
                    }
                });
        } catch (Throwable t) {
            slog(android.util.Log.ERROR, TAG, "makeHookerWith fail: " + iface + ": " + t);
            return null;
        }
    }

    private static Object makeHookerFor(Class<?> iface) {
        try {
            if (iface == null || !iface.isInterface()) return null;
            return java.lang.reflect.Proxy.newProxyInstance(
                iface.getClassLoader(), new Class<?>[]{iface},
                new java.lang.reflect.InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method method, Object[] a) throws Throwable {
                        if (!method.getName().equals("intercept")) return defaultValue(method.getReturnType());
                        Object chain = (a != null && a.length > 0) ? a[0] : null;
                        Object r = invokeNoArg(chain, "proceed");
                        try {
                            Object self = invokeNoArg(chain, "getThisObject");
                            android.util.Log.i(TAG, "intercept: self=" + self);
                            if (self instanceof ViewGroup) {
                                try { injectStatic((ViewGroup) self); }
                                catch (Throwable t2) { android.util.Log.e(TAG, "inject inner fail", t2); }
                            }
                        } catch (Throwable t) {
                            android.util.Log.e(TAG, "intercept fail", t);
                        }
                        return r;
                    }
                });
        } catch (Throwable t) {
            android.util.Log.e(TAG, "makeHookerFor fail: " + iface, t);
            return null;
        }
    }

    private static Object defaultValue(Class<?> t) {
        if (t == void.class) return null;
        if (t == boolean.class) return Boolean.FALSE;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return (char) 0;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        return null;
    }


    /** Chain 没有 setArgs，只能用 proceedWith(Object[]) 传新参数 */
    private static boolean proceedWith(Object chain, Object[] args) {
        if (chain == null) return false;
        for (Method m : chain.getClass().getMethods()) {
            if (!m.getName().equals("proceedWith")) continue;
            Class<?>[] pts = m.getParameterTypes();
            StringBuilder sb = new StringBuilder();
            for (Class<?> pc : pts) sb.append(pc.getName()).append(",");
            slog(android.util.Log.INFO, TAG, "proceedWith overload: (" + sb + ")");
            if (pts.length != 1 || !pts[0].isArray()) continue;
            m.setAccessible(true);
            try {
                Class<?> comp = pts[0].getComponentType();
                Object arr;
                if (comp == Object.class) {
                    arr = args;
                } else {
                    arr = java.lang.reflect.Array.newInstance(comp, args.length);
                    for (int i = 0; i < args.length; i++) java.lang.reflect.Array.set(arr, i, args[i]);
                }
                m.invoke(chain, arr);
                return true;
            } catch (Throwable t) {
                slog(android.util.Log.ERROR, TAG, "proceedWith fail: " + t);
            }
        }
        return false;
    }

    private static Object invokeNoArg(Object obj, String name) throws Throwable {
        if (obj == null) return null;
        for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
            for (Method mm : c.getDeclaredMethods()) {
                if (mm.getName().equals(name) && mm.getParameterTypes().length == 0) {
                    mm.setAccessible(true);
                    return mm.invoke(obj);
                }
            }
        }
        return null;
    }

    /** 静态上下文下打日志（转调 XposedModule.log） */
    private static void slog(int priority, String tag, String msg) {
        MainHook h = SELF.get();
        if (h != null) h.log(priority, tag, msg);
    }

    private static final java.util.concurrent.atomic.AtomicReference<MainHook> SELF =
        new java.util.concurrent.atomic.AtomicReference<MainHook>();

    private static void injectStatic(final ViewGroup root) {
        final MainHook h = SELF.get();
        if (h == null) return;
        try {
            root.post(new Runnable() {
                @Override public void run() {
                    try { h.inject(root); }
                    catch (Throwable t) { android.util.Log.e(TAG, "inject async fail", t); }
                }
            });
        } catch (Throwable t) {
            android.util.Log.e(TAG, "post fail", t);
        }
    }

    /** 去掉文件名非法字符，不用正则避免转义问题 */
    private static String safeName(String in) {
        if (in == null || in.length() == 0) return "app";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            boolean bad = (c == 0x5C) || (c == 0x2F) || (c == 0x3A) || (c == 0x2A)
                    || (c == 0x3F) || (c == 0x22) || (c == 0x3C) || (c == 0x3E)
                    || (c == 0x7C) || (c < 32);
            sb.append(bad ? (char) 0x5F : c);
        }
        String r = sb.toString().trim();
        return r.length() == 0 ? "app" : r;
    }

    private static Object call(Object obj, String name) {
        if (obj == null) return null;
        try {
            for (Class<?> c = obj.getClass(); c != null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterTypes().length == 0) {
                        m.setAccessible(true);
                        return m.invoke(obj);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void toast(Activity a, String s) {
        try { android.widget.Toast.makeText(a, s, android.widget.Toast.LENGTH_SHORT).show(); }
        catch (Throwable ignored) {}
    }

    private static int resId(Context ctx, String name, String type) {
        try { return ctx.getResources().getIdentifier(name, type, ctx.getPackageName()); }
        catch (Throwable t) { return 0; }
    }
}
