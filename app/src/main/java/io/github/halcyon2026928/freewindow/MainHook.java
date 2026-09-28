package io.github.halcyon2026928.freewindow;

import android.view.View;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[freewindow-hook]";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("android".equals(lpparam.packageName)) {
            hookSystemServer(lpparam);
        }
    }

    private void hookSystemServer(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> captionViewClass = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleTaskCaptionView", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(captionViewClass, "initCaptionView",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object thisObj = param.thisObject;
                                View bottomFrame = (View) XposedHelpers.getObjectField(thisObj, "mBottomFrame");
                                View bottomHandleView = (View) XposedHelpers.getObjectField(thisObj, "mBottomHandleView");
                                View bottomHandleLongView = (View) XposedHelpers.getObjectField(thisObj, "mBottomHandleLongView");

                                if (bottomFrame != null) bottomFrame.setVisibility(View.GONE);
                                if (bottomHandleView != null) bottomHandleView.setVisibility(View.GONE);
                                if (bottomHandleLongView != null) bottomHandleLongView.setVisibility(View.GONE);

                                XposedBridge.log(TAG + " hidden freeform bottom handle bar");
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " initCaptionView hook error: " + t.getMessage());
                            }
                        }
                    });

            XposedBridge.log(TAG + " FlexibleTaskCaptionView hook success");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook FlexibleTaskCaptionView failed: " + t.getMessage());
        }
    }
}