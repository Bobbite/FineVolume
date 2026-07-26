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

class AudioGainManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("fine_volume_prefs", Context.MODE_PRIVATE)

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var lastSelfAppliedTime: Long = 0
    private var expectedSystemVolumeIndex: Int = -1
    private var lastDeviceKey: String = ""

    /**
     * Opt-in. When on, a MediaSession with a remote VolumeProvider is held
     * while the keyguard is up so volume keys still reach us; MagicOS stops
     * delivering them to the accessibility service once locked. Off by default
     * because a session in the routing chain risks capturing headphone
     * transport buttons.
     */
    var lockscreenFineControlEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCKSCREEN_FINE_CONTROL, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LOCKSCREEN_FINE_CONTROL, value).apply()
        }

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

                if (isDeviceLockedOrOff) {
                    val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                    if (streamType == AudioManager.STREAM_MUSIC) {
                        val newVol = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                        val maxSys = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

                        // Our own setStreamVolume echoes back here. Without this
                        // guard the echo is re-mapped onto the coarse hardware
                        // grid, quantising away every fine step we just applied.
                        val isSelfInflicted = newVol == expectedSystemVolumeIndex &&
                                System.currentTimeMillis() - lastSelfAppliedTime < SELF_CHANGE_WINDOW_MS

                        if (newVol >= 0 && maxSys > 0 && !isSelfInflicted) {
                            val ratio = newVol.toFloat() / maxSys.toFloat()
                            val mappedStep = (ratio * maxSteps).roundToInt().coerceIn(0, maxSteps)
                            setCurrentStepInternal(mappedStep, applyToSystem = false)
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
            CurveMode.valueOf(prefs.getString(KEY_CURVE_MODE, CurveMode.LINEAR.name)!!)
        } catch (e: Exception) {
            CurveMode.LINEAR
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
        val fraction = getGainFraction(step)
        val maxSystemVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetSystemIndex = (fraction * maxSystemVol).roundToInt().coerceIn(0, maxSystemVol)

        try {
            if (updateSystemVolume) {
                expectedSystemVolumeIndex = targetSystemIndex
                lastSelfAppliedTime = System.currentTimeMillis()
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    targetSystemIndex,
                    0
                )
            }

            loudnessEnhancer?.let { enhancer ->
                val targetMb = ((fraction - 0.5f) * 1200).toInt().coerceIn(-1500, 1500)
                enhancer.setTargetGain(targetMb)
                if (!enhancer.enabled) {
                    enhancer.enabled = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying audio gain: ${e.message}")
        }
    }

    fun stepUp(): Int {
        val next = (currentStep + 1).coerceAtMost(maxSteps)
        currentStep = next
        return next
    }

    fun stepDown(): Int {
        val prev = (currentStep - 1).coerceAtLeast(0)
        currentStep = prev
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
        private const val KEY_LOCKSCREEN_FINE_CONTROL = "lockscreen_fine_control"

        // A volume change we caused ourselves echoes back through
        // VOLUME_CHANGED_ACTION within a few ms; anything inside this window
        // matching our expected index is ours, not a hardware key press.
        private const val SELF_CHANGE_WINDOW_MS = 750L

        @Volatile
        private var instance: AudioGainManager? = null

        /**
         * Process-wide shared instance. Every component must use this: each
         * separate instance would register its own VOLUME_CHANGED_ACTION
         * receiver and attach its own LoudnessEnhancer to session 0, so
         * duplicates cause multi-stepping and fighting DSP effects.
         */
        fun getInstance(context: Context): AudioGainManager =
            instance ?: synchronized(this) {
                instance ?: AudioGainManager(context.applicationContext).also { instance = it }
            }
    }
}
