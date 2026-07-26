package com.example.finevolume.audio

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlin.math.pow
import kotlin.math.roundToInt

enum class CurveMode {
    LOW_RANGE_FINE, // Preset: fine control in 0-30% range
    LINEAR          // Equal step distribution
}

class AudioGainManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("fine_volume_prefs", Context.MODE_PRIVATE)

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var lastSelfAppliedTime: Long = 0
    private var expectedSystemVolumeIndex: Int = -1
    private var lastDeviceKey: String = ""

    var perDeviceMemoryEnabled: Boolean
        get() = prefs.getBoolean(KEY_PER_DEVICE_MEMORY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_PER_DEVICE_MEMORY, value).apply()
            if (value) {
                checkAndSwitchDeviceVolume()
            }
        }

    private val audioDeviceCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                checkAndSwitchDeviceVolume()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                checkAndSwitchDeviceVolume()
            }
        }
    } else null

    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == "android.media.VOLUME_CHANGED_ACTION") {
                val powerManager = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val keyguardManager = context?.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

                val isDeviceLockedOrOff = (powerManager != null && !powerManager.isInteractive) ||
                        (keyguardManager != null && keyguardManager.isKeyguardLocked)

                // Only process VOLUME_CHANGED_ACTION when screen is locked or off
                if (!isDeviceLockedOrOff) {
                    return
                }

                val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                if (streamType == AudioManager.STREAM_MUSIC) {
                    val newVol = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                    val prevVol = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)

                    if (newVol != -1 && prevVol != -1 && newVol != prevVol) {
                        // Check if this change was triggered by our own setStreamVolume
                        if (newVol == expectedSystemVolumeIndex && (System.currentTimeMillis() - lastSelfAppliedTime < 1000)) {
                            // This is our own programmatic change being echoed back
                            expectedSystemVolumeIndex = -1 // consume it
                            return
                        }

                        if (newVol > prevVol) {
                            stepUp(updateSystemVolume = false)
                        } else if (newVol < prevVol) {
                            stepDown(updateSystemVolume = false)
                        }
                    }
                }
            } else if (action == Intent.ACTION_USER_PRESENT || action == Intent.ACTION_SCREEN_ON) {
                // Re-apply digital gain smoothly upon unlock without modifying currentStep
                applyStepGain(currentStep)
            } else if (action == Intent.ACTION_HEADSET_PLUG ||
                action == AudioManager.ACTION_AUDIO_BECOMING_NOISY ||
                action == "android.bluetooth.device.action.ACL_CONNECTED" ||
                action == "android.bluetooth.device.action.ACL_DISCONNECTED"
            ) {
                checkAndSwitchDeviceVolume()
            }
        }
    }

    var maxSteps: Int
        get() = prefs.getInt(KEY_MAX_STEPS, 100)
        set(value) {
            val clamped = value.coerceIn(10, 200)
            val oldMax = maxSteps
            val oldStep = currentStep

            prefs.edit().putInt(KEY_MAX_STEPS, clamped).apply()

            val newStep = if (clamped < oldMax) {
                (clamped / 2).coerceIn(0, clamped)
            } else {
                ((oldStep.toFloat() / oldMax.coerceAtLeast(1)) * clamped).roundToInt().coerceIn(0, clamped)
            }

            currentStep = newStep
        }

    var curveMode: CurveMode
        get() = try {
            CurveMode.valueOf(prefs.getString(KEY_CURVE_MODE, CurveMode.LOW_RANGE_FINE.name)!!)
        } catch (e: Exception) {
            CurveMode.LOW_RANGE_FINE
        }
        set(value) {
            prefs.edit().putString(KEY_CURVE_MODE, value.name).apply()
            applyStepGain(currentStep)
        }

    var currentStep: Int
        get() {
            val activeKey = getActiveDeviceKey()
            return if (perDeviceMemoryEnabled) {
                prefs.getInt("step_$activeKey", prefs.getInt(KEY_CURRENT_STEP, 30)).coerceIn(0, maxSteps)
            } else {
                prefs.getInt(KEY_CURRENT_STEP, 30).coerceIn(0, maxSteps)
            }
        }
        set(value) {
            setCurrentStepInternal(value, applyToSystem = true)
        }

    private fun setCurrentStepInternal(value: Int, applyToSystem: Boolean) {
        val clamped = value.coerceIn(0, maxSteps)
        prefs.edit().putInt(KEY_CURRENT_STEP, clamped).apply()

        if (perDeviceMemoryEnabled) {
            val activeKey = getActiveDeviceKey()
            prefs.edit().putInt("step_$activeKey", clamped).apply()
            lastDeviceKey = activeKey
        }

        applyStepGain(clamped, updateSystemVolume = applyToSystem)
    }

    fun getActiveDeviceKey(): String {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isLocked = (powerManager != null && !powerManager.isInteractive) ||
                (keyguardManager != null && keyguardManager.isKeyguardLocked)

        if (isLocked && lastDeviceKey.isNotBlank()) {
            return lastDeviceKey
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in devices) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_HEARING_AID -> {
                        val name = device.productName.toString().trim().takeIf { it.isNotBlank() } ?: "Bluetooth Device"
                        val key = "BT_$name"
                        lastDeviceKey = key
                        return key
                    }
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE -> {
                        val key = "WIRED_HEADPHONES"
                        lastDeviceKey = key
                        return key
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            if (audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn) {
                val key = "BT_Device"
                lastDeviceKey = key
                return key
            }
            @Suppress("DEPRECATION")
            if (audioManager.isWiredHeadsetOn) {
                val key = "WIRED_HEADPHONES"
                lastDeviceKey = key
                return key
            }
        }
        val key = "BUILTIN_SPEAKER"
        lastDeviceKey = key
        return key
    }

    fun getActiveDeviceDisplayName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in devices) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_HEARING_AID -> {
                        val name = device.productName.toString().trim().takeIf { it.isNotBlank() } ?: "Bluetooth Headphones"
                        return "🎧 $name"
                    }
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE -> {
                        return "🎧 Wired Headphones"
                    }
                }
            }
        }
        return "🔊 Phone Speaker"
    }

    fun checkAndSwitchDeviceVolume() {
        if (!perDeviceMemoryEnabled) return

        val newKey = getActiveDeviceKey()
        if (newKey != lastDeviceKey) {
            lastDeviceKey = newKey
            val defaultStep = (maxSteps * 0.30f).roundToInt()
            val savedStep = prefs.getInt("step_$newKey", defaultStep).coerceIn(0, maxSteps)
            setCurrentStepInternal(savedStep, applyToSystem = true)
        }
    }

    init {
        initAudioFx()
        registerReceivers()
        checkAndSwitchDeviceVolume()
    }

    private fun registerReceivers() {
        try {
            val filter = IntentFilter().apply {
                addAction("android.media.VOLUME_CHANGED_ACTION")
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction("android.bluetooth.device.action.ACL_CONNECTED")
                addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            }
            context.registerReceiver(volumeChangeReceiver, filter)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
                audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering volume and device receivers: ${e.message}")
        }
    }

    private fun initAudioFx() {
        try {
            loudnessEnhancer = LoudnessEnhancer(0).apply {
                enabled = true
            }
            applyStepGain(currentStep)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing LoudnessEnhancer AudioEffect: ${e.message}")
        }
    }

    fun getGainFraction(step: Int = currentStep): Float {
        val rawRatio = step.toFloat() / maxSteps.coerceAtLeast(1)
        return when (curveMode) {
            CurveMode.LOW_RANGE_FINE -> {
                rawRatio.toDouble().pow(1.7).toFloat()
            }
            CurveMode.LINEAR -> rawRatio
        }
    }

    fun applyStepGain(step: Int, updateSystemVolume: Boolean = true) {
        val targetFraction = getGainFraction(step)
        val maxSystemVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        
        var currentSystemVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        
        if (updateSystemVolume) {
            // When updating system volume natively (unlocked/headset switch), 
            // force native volume to max to prevent OS volume drop-offs 
            // and do all attenuation purely via DSP LoudnessEnhancer.
            currentSystemVol = maxSystemVol
        }

        // Native Volume Fraction (what the hardware is currently set to)
        val nativeFraction = currentSystemVol.toFloat() / maxSystemVol.toFloat()
        
        // Desired Acoustic Energy vs Current Hardware Energy
        // If target is 0.5 and native is 0.6, we need LoudnessEnhancer to be 0.5/0.6 = 0.83 (-1.6dB).
        var requiredLeFraction = if (nativeFraction > 0.01f) {
            targetFraction / nativeFraction
        } else {
            0.0f
        }

        // Clamp requiredLeFraction to 1.0 (0 dB) to avoid positive gain distortion.
        requiredLeFraction = requiredLeFraction.coerceIn(0.0f, 1.0f)
        
        // Convert multiplier to millibels: mB = 2000 * log10(fraction)
        val targetMb = if (requiredLeFraction > 0.001f) {
            (2000.0 * kotlin.math.log10(requiredLeFraction.toDouble())).toInt().coerceIn(-10000, 0)
        } else {
            -10000 // effectively mute
        }

        try {
            loudnessEnhancer?.let { enhancer ->
                enhancer.setTargetGain(targetMb)
                if (!enhancer.enabled) {
                    enhancer.enabled = true
                }
            }

            if (updateSystemVolume && audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) != maxSystemVol) {
                expectedSystemVolumeIndex = maxSystemVol
                lastSelfAppliedTime = System.currentTimeMillis()
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    maxSystemVol,
                    0
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying audio gain: ${e.message}")
        }
    }

    fun stepUp(updateSystemVolume: Boolean = true): Int {
        val next = (currentStep + 1).coerceAtMost(maxSteps)
        setCurrentStepInternal(next, applyToSystem = updateSystemVolume)
        return next
    }

    fun stepDown(updateSystemVolume: Boolean = true): Int {
        val prev = (currentStep - 1).coerceAtLeast(0)
        setCurrentStepInternal(prev, applyToSystem = updateSystemVolume)
        return prev
    }

    fun release() {
        try {
            context.unregisterReceiver(volumeChangeReceiver)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            }
        } catch (e: Exception) {
            // Ignored if already unregistered
        }
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing LoudnessEnhancer: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AudioGainManager"
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_CURVE_MODE = "curve_mode"
        private const val KEY_CURRENT_STEP = "current_step"
        private const val KEY_PER_DEVICE_MEMORY = "per_device_memory"
    }
}
