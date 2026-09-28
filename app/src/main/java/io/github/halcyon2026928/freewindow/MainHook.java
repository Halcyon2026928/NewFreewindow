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

                                // 菜单按钮往上平移20dp
                                float density = ((View) thisObj).getResources().getDisplayMetrics().density;
                                int offsetPx = (int) (-20 * density + 0.5f);

                                View controlView = (View) XposedHelpers.getObjectField(thisObj, "mControlView");
                                View controlBackgroundView = (View) XposedHelpers.getObjectField(thisObj, "mControlBackgroundView");

                                if (controlView != null) {
                                    controlView.setTranslationY(offsetPx);
                                }
                                if (controlBackgroundView != null) {
                                    controlBackgroundView.setTranslationY(offsetPx);
                                }

                                XposedBridge.log(TAG + " hidden bottom bar, moved menu up 20dp");
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