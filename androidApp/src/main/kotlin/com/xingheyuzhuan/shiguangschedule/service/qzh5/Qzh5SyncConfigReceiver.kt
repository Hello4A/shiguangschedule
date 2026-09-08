package com.xingheyuzhuan.shiguangschedule.service.qzh5

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class Qzh5SyncConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Qzh5SyncJavascriptBridge.ACTION_QZH5_SYNC_CONFIGURED -> {
                Qzh5AutoSyncScheduler.schedule(context)
            }
            Qzh5SyncJavascriptBridge.ACTION_QZH5_SYNC_DISABLED -> {
                Qzh5AutoSyncScheduler.cancel(context)
            }
            Qzh5SyncJavascriptBridge.ACTION_QZH5_SYNC_NOW -> {
                Qzh5AutoSyncScheduler.syncNow(context)
            }
        }
    }
}
