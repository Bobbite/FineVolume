package com.example.finevolume.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the foreground service back up after a reboot. The accessibility
 * service is re-bound by the system on its own, but until something holds a
 * foreground component the process is an easy target for OEM freezing the
 * first time the screen goes off.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            context?.let { VolumeOverlayService.ensureRunning(it) }
        }
    }
}
