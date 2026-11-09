package io.github.libxposed.api;

public interface XposedModuleInterface {

    interface ModuleLoadedParam {
        boolean isSystemServer();
    }

    interface PackageLoadedParam {
        String getPackageName();
        ClassLoader getClassLoader();
        boolean isFirstPackage();
    }

    interface PackageReadyParam extends PackageLoadedParam {
        ClassLoader getClassLoader();
        String getPackageName();
    }

    interface SystemServerStartingParam {
    }

    interface HotReloadingParam {
    }

    interface HotReloadedParam {
    }
}
