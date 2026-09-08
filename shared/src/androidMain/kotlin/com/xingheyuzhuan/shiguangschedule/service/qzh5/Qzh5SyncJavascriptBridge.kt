package com.xingheyuzhuan.shiguangschedule.service.qzh5

import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface

class Qzh5SyncJavascriptBridge(context: Context) {
    private val appContext = context.applicationContext
    private val credentialStore = Qzh5CredentialStore(appContext)

    @JavascriptInterface
    fun enableAutoSync(userNo: String, encryptedPwd: String, tableId: String): Boolean {
        return runCatching {
            credentialStore.save(userNo, encryptedPwd, tableId)
            credentialStore.setAutoSyncEnabled(true)
            appContext.sendBroadcast(
                Intent(ACTION_QZH5_SYNC_CONFIGURED).setPackage(appContext.packageName)
            )
            true
        }.getOrDefault(false)
    }

    @JavascriptInterface
    fun disableAutoSync(): Boolean {
        return runCatching {
            credentialStore.setAutoSyncEnabled(false)
            appContext.sendBroadcast(
                Intent(ACTION_QZH5_SYNC_DISABLED).setPackage(appContext.packageName)
            )
            true
        }.getOrDefault(false)
    }

    @JavascriptInterface
    fun isAutoSyncEnabled(): Boolean =
        credentialStore.hasCredentials() && credentialStore.isAutoSyncEnabled()

    companion object {
        const val ACTION_QZH5_SYNC_CONFIGURED =
            "com.xingheyuzhuan.shiguangschedule.ACTION_QZH5_SYNC_CONFIGURED"
        const val ACTION_QZH5_SYNC_DISABLED =
            "com.xingheyuzhuan.shiguangschedule.ACTION_QZH5_SYNC_DISABLED"
        const val ACTION_QZH5_SYNC_NOW =
            "com.xingheyuzhuan.shiguangschedule.ACTION_QZH5_SYNC_NOW"
    }
}
