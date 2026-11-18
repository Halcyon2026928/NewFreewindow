package io.github.halcyon2026928.freewindow;

/** legacy LoadPackageParam 的等价形态，只保留模块实际用到的字段。 */
public final class XC_LoadPackage {

    private XC_LoadPackage() {
    }

    public static final class LoadPackageParam {
        public final String packageName;
        public final ClassLoader classLoader;
        public final boolean isFirstApplication;
        public final String processName;

        public LoadPackageParam(String packageName, ClassLoader classLoader, String processName,
                                boolean isFirstApplication) {
            this.packageName = packageName;
            this.classLoader = classLoader;
            this.processName = processName;
            this.isFirstApplication = isFirstApplication;
        }
    }
}
