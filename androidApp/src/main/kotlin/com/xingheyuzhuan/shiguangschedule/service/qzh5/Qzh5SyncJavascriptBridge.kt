package com.xingheyuzhuan.shiguangschedule.service.qzh5

import android.content.Context
import android.webkit.JavascriptInterface

/**
 * 仅暴露给拾光内置 WebView 的 qzh5 自动同步桥接。
 */
class Qzh5SyncJavascriptBridge(context: Context) {
    private val appContext = context.applicationContext
    private val credentialStore = Qzh5CredentialStore(appContext)

    @JavascriptInterface
    fun enableAutoSync(userNo: String, encryptedPwd: String, tableId: String): Boolean {
        return runCatching {
            credentialStore.save(userNo, encryptedPwd, tableId)
            Qzh5AutoSyncScheduler.schedule(appContext)
            true
        }.getOrDefault(false)
    }

    @JavascriptInterface
    fun disableAutoSync(): Boolean {
        return runCatching {
            credentialStore.clear()
            Qzh5AutoSyncScheduler.cancel(appContext)
            true
        }.getOrDefault(false)
    }

    @JavascriptInterface
    fun isAutoSyncEnabled(): Boolean = credentialStore.hasCredentials()
}
