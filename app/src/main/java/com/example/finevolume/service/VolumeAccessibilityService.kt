package com.example.finevolume.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.example.finevolume.audio.AudioGainManager

class VolumeAccessibilityService : AccessibilityService() {

    private lateinit var audioGainManager: AudioGainManager
    private val handler = Handler(Looper.getMainLooper())
    private var activeKeyCode: Int = 0
    private var isHolding = false

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (isHolding && activeKeyCode != 0) {
                val newStep = if (activeKeyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    audioGainManager.stepUp()
                } else {
                    audioGainManager.stepDown()
                }
                showOverlay(newStep)
                handler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioGainManager = AudioGainManager(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyEvent(event)
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    stopRepeatHold()
                    activeKeyCode = keyCode
                    isHolding = true

                    // Initial single step
                    val newStep = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        audioGainManager.stepUp()
                    } else {
                        audioGainManager.stepDown()
                    }
                    showOverlay(newStep)

                    // Schedule repeating hold after INITIAL_DELAY_MS
                    handler.postDelayed(repeatRunnable, INITIAL_DELAY_MS)
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                stopRepeatHold()
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    private fun stopRepeatHold() {
        isHolding = false
        activeKeyCode = 0
        handler.removeCallbacks(repeatRunnable)
    }

    private fun showOverlay(currentStep: Int) {
        val intent = Intent(this, VolumeOverlayService::class.java).apply {
            action = VolumeOverlayService.ACTION_SHOW_OVERLAY
            putExtra(VolumeOverlayService.EXTRA_CURRENT_STEP, currentStep)
            putExtra(VolumeOverlayService.EXTRA_MAX_STEPS, audioGainManager.maxSteps)
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopRepeatHold()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRepeatHold()
        isServiceRunning = false
        audioGainManager.release()
    }

    companion object {
        var isServiceRunning = false
            private set

        private const val INITIAL_DELAY_MS = 350L // Natural hold delay before continuous repeat
        private const val REPEAT_INTERVAL_MS = 100L // Repeat interval (10 steps per second)
    }
}
