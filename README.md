# SmsImsHook - Android 11 SMS IMS Fallback LSPosed 模块

专为 **RK3568 嵌入式开发板 (Android 11 / API 30)** 打造的 LSPosed 模块。用于解决连接 4G Quectel EC20 LTE 模组时，RIL 库 (`libril-quectel.so`) 未实现 `RIL_REQUEST_IMS_SEND_SMS` (Request 113) 导致短信发送报错 `CommandException: REQUEST_NOT_SUPPORTED error 6` 的问题。

---

## 核心机制

模块采用 **双层拦截策略 (Multi-Tier Interception Strategy)**：

1. **Tier 1 (Dispatcher 布尔标记覆盖)**：
   - 拦截 `com.android.internal.telephony.GsmSMSDispatcher.isIms()` -> 强行返回 `false`
   - 拦截 `com.android.internal.telephony.SMSDispatcher.isIms()` -> 强行返回 `false`
   - 拦截 `com.android.internal.telephony.ImsSmsDispatcher.isIms()` -> 强行返回 `false`
   - 拦截 `com.android.internal.telephony.ImsSmsDispatcher.isAvailable()` -> 强行返回 `false`

2. **Tier 2 (RIL.java 硬拦截重定向 - 最终保底)**：
   - 拦截 `com.android.internal.telephony.RIL.imsSendSMS(...)`
   - 提取参数 `smscPdu`、`pdu` 和回调 `Message`
   - 调用 `RIL.sendSMS(...)` 降级转交至 `RIL_REQUEST_SEND_SMS` (Request 25 / `AT+CMGS`) 路径发送
   - 调用 `param.setResult(null)` 截断原始 `imsSendSMS` 流程，避免产生 Request 113

---

## GitHub Actions 自动编译使用指南

无需在本地配置复杂的 Android Studio 及 SDK 环境，只需将本仓库推送到 GitHub：

1. **Push 代码到 GitHub**：
   ```bash
   git init
   git add .
   git commit -m "Initial commit for SmsImsHook"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/SmsImsHook.git
   git push -u origin main
   ```
2. **下载编译产物**：
   - 进入 GitHub 仓库页面，点击 **Actions** 选项卡。
   - 点击最新一次触发的 **Build LSPosed Module APK** 工作流。
   - 在页面最下方的 **Artifacts** 中下载 `SmsImsHook-APKs.zip`。
   - 解压 zip 包即可获得 `app-release-unsigned.apk` 或 `app-debug.apk`。

---

## RK3568 板卡安装与调试

1. **安装 APK**：
   ```bash
   adb install -r app-debug.apk
   ```
2. **在 LSPosed 中激活**：
   - 打开 LSPosed Manager。
   - 在模块列表中找到 **SMS IMS Fallback Hook** 并开启。
   - 确认 Hook 作用域包含 `Phone (com.android.phone)` 和 `系统框架 (android)`。
3. **生效方式**：
   - 重启 `com.android.phone` 进程或直接重启板卡：
   ```bash
   adb shell killall com.android.phone
   # 或
   adb reboot
   ```
4. **查看拦截日志**：
   ```bash
   adb logcat -s SMS_IMS_HOOK
   ```
   预期的输出日志示例：
   ```text
   SMS_IMS_HOOK: 模块初始化成功，目标进程: com.android.phone
   SMS_IMS_HOOK: 使用 SystemClassLoader 成功找到 telephony 类
   SMS_IMS_HOOK: Tier 1 成功: Hook com.android.internal.telephony.GsmSMSDispatcher.isIms() -> return false
   SMS_IMS_HOOK: Tier 2 捕获到 imsSendSMS 调用，准备拦截并重定向到 RIL_REQUEST_SEND_SMS!
   SMS_IMS_HOOK: 成功转交至 RIL.sendSMS(...) 执行完成
   SMS_IMS_HOOK: 原 imsSendSMS 执行已被截断 (setResult=null)
   ```
