package app.animego.inc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.animego.inc.core.download.DownloadScheduler

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pendingResult = goAsync()
        Thread {
            try {
                DownloadScheduler.enqueuePending(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
