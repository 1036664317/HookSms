package com.example.smsimshook;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * LSPosed 模块入口类：Android 11 SMS IMS Fallback 降级拦截器
 * 针对 Quectel EC20 等旧版 RIL 库未实现 RIL_REQUEST_IMS_SEND_SMS (Request 113) 的问题，
 * 将 IMS 短信发送强制重定向至传统 RIL_REQUEST_SEND_SMS (Request 25 / AT+CMGS)。
 */
public class HookInit implements IXposedHookLoadPackage {

    private static final String TAG = "SMS_IMS_HOOK";

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        // 只在 com.android.phone 或 android 框架层进程中生效
        if (!"com.android.phone".equals(lpparam.packageName) && !"android".equals(lpparam.packageName)) {
            return;
        }

        log("模块初始化成功，目标进程: " + lpparam.packageName);

        // 获取包含 BootClassPath 的 ClassLoader
        ClassLoader targetClassLoader = resolveClassLoader(lpparam.classLoader);

        // 执行 Tier 1 拦截：修改 Dispatcher 的布尔状态
        applyTier1Hooks(targetClassLoader);

        // 执行 Tier 2 拦截：硬拦截 RIL.imsSendSMS 并重定向至 sendSMS
        applyTier2Hooks(targetClassLoader);
    }

    /**
     * 解决 BootClassLoader 查找问题
     * Android 11 中 com.android.internal.telephony.* 属于 BootClassPath (telephony-common.jar)，
     * 优先从传入的 ClassLoader 查找，失败时回退至 ClassLoader.getSystemClassLoader()
     */
    private ClassLoader resolveClassLoader(ClassLoader defaultClassLoader) {
        if (defaultClassLoader != null) {
            try {
                defaultClassLoader.loadClass("com.android.internal.telephony.RIL");
                log("使用 lpparam.classLoader 成功找到 telephony 类");
                return defaultClassLoader;
            } catch (ClassNotFoundException ignored) {
            }
        }

        ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
        if (systemClassLoader != null) {
            try {
                systemClassLoader.loadClass("com.android.internal.telephony.RIL");
                log("使用 SystemClassLoader 成功找到 telephony 类");
                return systemClassLoader;
            } catch (ClassNotFoundException ignored) {
            }
        }

        log("警告: 无法预先验证 RIL 类所在 ClassLoader，将优先使用 defaultClassLoader");
        return defaultClassLoader != null ? defaultClassLoader : systemClassLoader;
    }

    /**
     * Tier 1: 拦截并覆盖 Telephony 调度器的 isIms() 与 isAvailable() 方法，始终返回 false
     */
    private void applyTier1Hooks(ClassLoader classLoader) {
        log("开始配置 Tier 1 拦截 (Dispatcher 布尔标记替换)...");

        // 1. Hook GsmSMSDispatcher.isIms() -> false
        hookMethodReturnFalse(classLoader, "com.android.internal.telephony.GsmSMSDispatcher", "isIms");

        // 2. Hook SMSDispatcher.isIms() -> false
        hookMethodReturnFalse(classLoader, "com.android.internal.telephony.SMSDispatcher", "isIms");

        // 3. Hook ImsSmsDispatcher.isIms() -> false
        hookMethodReturnFalse(classLoader, "com.android.internal.telephony.ImsSmsDispatcher", "isIms");

        // 4. Hook ImsSmsDispatcher.isAvailable() -> false
        hookMethodReturnFalse(classLoader, "com.android.internal.telephony.ImsSmsDispatcher", "isAvailable");
    }

    /**
     * 辅助工具：寻找指定类的方法并将返回值强制替换为 false
     */
    private void hookMethodReturnFalse(ClassLoader classLoader, String className, String methodName) {
        try {
            Class<?> clazz = XposedHelpers.findClass(className, classLoader);
            XposedHelpers.findAndHookMethod(clazz, methodName, XC_MethodReplacement.returnConstant(false));
            log("Tier 1 成功: Hook " + className + "." + methodName + "() -> return false");
        } catch (Throwable t) {
            logError("Tier 1 失败: Hook " + className + "." + methodName + "() 出现异常", t);
        }
    }

    /**
     * Tier 2: 拦截 RIL.imsSendSMS(...) 硬定向降级为 sendSMS(...)
     */
    private void applyTier2Hooks(ClassLoader classLoader) {
        log("开始配置 Tier 2 拦截 (RIL.imsSendSMS 强制重定向)...");

        try {
            Class<?> rilClass = XposedHelpers.findClass("com.android.internal.telephony.RIL", classLoader);

            // 拦截 imsSendSMS 的所有重载
            XposedBridge.hookAllMethods(rilClass, "imsSendSMS", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    log("Tier 2 捕获到 imsSendSMS 调用，准备拦截并重定向到 RIL_REQUEST_SEND_SMS!");

                    if (param.args == null || param.args.length < 3) {
                        logError("imsSendSMS 参数异常，长度不足 3", null);
                        return;
                    }

                    // 提取短信 PDU 参数
                    String smscPdu = (String) param.args[0];
                    String pdu = (String) param.args[1];
                    // 最后一个参数通常是 Handler 消息回调 result Message
                    Object result = param.args[param.args.length - 1];

                    log("短信 PDU 信息: smscPdu=" + smscPdu + ", pdu length=" + (pdu != null ? pdu.length() : 0));

                    try {
                        // 反射调用 sendSMS (即 RIL_REQUEST_SEND_SMS / Request 25 / AT+CMGS)
                        XposedHelpers.callMethod(param.thisObject, "sendSMS", smscPdu, pdu, result);
                        log("成功转交至 RIL.sendSMS(...) 执行完成");

                        // 阻止原 imsSendSMS 方法继续下发 Request 113 (RIL_REQUEST_IMS_SEND_SMS)
                        param.setResult(null);
                        log("原 imsSendSMS 执行已被截断 (setResult=null)");
                    } catch (Throwable t) {
                        logError("调用 sendSMS 或拦截过程发生异常", t);
                    }
                }
            });

            log("Tier 2 成功: hookAllMethods(RIL, imsSendSMS) 已注册完成");
        } catch (Throwable t) {
            logError("Tier 2 失败: 无法找到或 Hook RIL.imsSendSMS", t);
        }
    }

    /**
     * 双通道日志输出
     */
    private static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log("[" + TAG + "] " + message);
    }

    private static void logError(String message, Throwable t) {
        if (t != null) {
            Log.e(TAG, message, t);
            XposedBridge.log("[" + TAG + "] " + message + "\n" + Log.getStackTraceString(t));
        } else {
            Log.e(TAG, message);
            XposedBridge.log("[" + TAG + "] " + message);
        }
    }
}
