package io.github.halcyon2026928.freewindow;

/** legacy 入口接口的等价形态：入口类仍然声明 handleLoadPackage(...)，由 FreeWindowModule 转调。 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
