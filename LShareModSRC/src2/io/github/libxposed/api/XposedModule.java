package io.github.libxposed.api;

public abstract class XposedModule implements XposedModuleInterface {
    public XposedModule() {}

    public void log(String message) {}
    public void log(String tag, String message) {}
    public void log(String message, Throwable throwable) {}
    public void log(int priority, String tag, String message) {}
    public void log(int priority, String tag, String message, Throwable throwable) {}

    public XposedInterface.HookBuilder hook(Object executable) { return null; }

    public void onModuleLoaded(ModuleLoadedParam param) {}
    public void onPackageLoaded(PackageLoadedParam param) {}
    public void onPackageReady(PackageReadyParam param) {}
    public void onSystemServerStarting(SystemServerStartingParam param) {}
    public void onHotReloading(HotReloadingParam param) {}
    public void onHotReloaded(HotReloadedParam param) {}
}
