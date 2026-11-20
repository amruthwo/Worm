package com.worm.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After Worm is updated, Android drops widgets' pending redraws and they can sit on a spinner.
 * This redraws all of Worm's widgets as soon as the new version is installed.
 */
class WidgetRefresher : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED || intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val pending = goAsync()
            Widgets.refreshAll(context)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ pending.finish() }, 4_000)
        }
    }
}
