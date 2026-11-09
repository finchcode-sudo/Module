package io.github.libxposed.api;

import java.lang.reflect.Executable;

public interface XposedInterface {
    HookBuilder hook(Executable executable);
    void log(int priority, String tag, String message);
    void log(int priority, String tag, String message, Throwable throwable);

    interface Hooker {
        Object intercept(Chain chain) throws Throwable;
    }

    interface Chain {
        Executable getExecutable();
        Object getThisObject();
        Object[] getArgs();
        Object getResult();
        void setResult(Object result);
        Throwable getThrowable();
        void setThrowable(Throwable throwable);
        Object proceed();
    }

    interface HookHandle {
        void unhook();
    }

    interface HookBuilder {
        HookBuilder intercept(Hooker hooker);
    }
}
