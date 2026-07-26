package com.example.finevolume.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.finevolume.R
import com.example.finevolume.audio.AudioGainManager
import com.example.finevolume.ui.VerticalVolumeBar

class VolumeOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var volumeBar: VerticalVolumeBar? = null
    private var textVolumePercent: TextView? = null
    private var textStepCount: TextView? = null

    private lateinit var audioGainManager: AudioGainManager
    private val handler = Handler(Looper.getMainLooper())
    private var audioTrack: android.media.AudioTrack? = null

    private val hideRunnable = Runnable {
        hideOverlay()
    }

    private var mediaSession: android.media.session.MediaSession? = null
    private var volumeProvider: android.media.VolumeProvider? = null

    override fun onCreate() {
        super.onCreate()
        audioGainManager = AudioGainManager(this)
        startForegroundServiceNotification()
        startSilentAudioSession()
        initMediaSessionVolumeProvider()
    }

    private fun initMediaSessionVolumeProvider() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                volumeProvider = object : android.media.VolumeProvider(
                    VOLUME_CONTROL_RELATIVE,
                    audioGainManager.maxSteps,
                    audioGainManager.currentStep
                ) {
                    override fun onAdjustVolume(direction: Int) {
                        val stepsToChange = kotlin.math.abs(direction)
                        var lastStep = audioGainManager.currentStep
                        if (direction > 0) {
                            repeat(stepsToChange) {
                                lastStep = audioGainManager.stepUp()
                            }
                            notifyOverlayIfUnlocked(lastStep)
                        } else if (direction < 0) {
                            repeat(stepsToChange) {
                                lastStep = audioGainManager.stepDown()
                            }
                            notifyOverlayIfUnlocked(lastStep)
                        }
                    }

                    override fun onSetVolumeTo(volume: Int) {
                        audioGainManager.currentStep = volume
                    }
                }

                mediaSession = android.media.session.MediaSession(this, "FineVolumeSession").apply {
                    val state = android.media.session.PlaybackState.Builder()
                        .setActions(android.media.session.PlaybackState.ACTION_PLAY or
                                android.media.session.PlaybackState.ACTION_PAUSE or
                                android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
                                android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
                                android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                        .setState(android.media.session.PlaybackState.STATE_PLAYING, 0, 1.0f)
                        .build()
                    setPlaybackState(state)

                    setCallback(object : android.media.session.MediaSession.Callback() {
                        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                            val keyEvent = mediaButtonIntent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
                            if (keyEvent != null && keyEvent.keyCode != android.view.KeyEvent.KEYCODE_VOLUME_UP && keyEvent.keyCode != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
                                // Disable our session temporarily
                                isActive = false
                                // Dispatch the event to the system (which will route it to Spotify/YouTube)
                                val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                                audioManager.dispatchMediaKeyEvent(keyEvent)
                                // Re-enable our session
                                isActive = true
                                return true
                            }
                            return super.onMediaButtonEvent(mediaButtonIntent)
                        }

                        // Also override direct actions just in case
                        override fun onPlay() { forwardKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PLAY) }
                        override fun onPause() { forwardKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE) }
                        override fun onSkipToNext() { forwardKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_NEXT) }
                        override fun onSkipToPrevious() { forwardKeyEvent(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) }

                        private fun forwardKeyEvent(keyCode: Int) {
                            isActive = false
                            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                            val downEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode)
                            val upEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode)
                            audioManager.dispatchMediaKeyEvent(downEvent)
                            audioManager.dispatchMediaKeyEvent(upEvent)
                            isActive = true
                        }
                    })

                    volumeProvider?.let { setPlaybackToRemote(it) }
                    isActive = true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun notifyOverlayIfUnlocked(currentStep: Int) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val isLockedOrOff = (powerManager != null && !powerManager.isInteractive) ||
                (keyguardManager != null && keyguardManager.isKeyguardLocked)

        if (!isLockedOrOff) {
            handler.post {
                showOrUpdateOverlay(currentStep, audioGainManager.maxSteps)
            }
        }
    }

    private fun startSilentAudioSession() {
        try {
            val sampleRate = 44100
            val minBufferSize = android.media.AudioTrack.getMinBufferSize(
                sampleRate,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize > 0) {
                audioTrack = android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        android.media.AudioFormat.Builder()
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufferSize)
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .build()

                val silentBuffer = ByteArray(minBufferSize)
                audioTrack?.write(silentBuffer, 0, silentBuffer.size)
                audioTrack?.setLoopPoints(0, silentBuffer.size / 2, -1)
                audioTrack?.play()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW_OVERLAY) {
            val step = intent.getIntExtra(EXTRA_CURRENT_STEP, audioGainManager.currentStep)
            val max = intent.getIntExtra(EXTRA_MAX_STEPS, audioGainManager.maxSteps)
            showOrUpdateOverlay(step, max)
        }
        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channelId = "fine_volume_overlay_channel"
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("FineVolume Active")
            .setContentText("Granular volume control active")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun showOrUpdateOverlay(currentStep: Int, maxSteps: Int) {
        if (!Settings.canDrawOverlays(this)) return

        if (windowManager == null) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }

        if (overlayView == null) {
            createOverlayView()
        }

        volumeBar?.maxSteps = maxSteps
        volumeBar?.currentStep = currentStep

        val percent = (audioGainManager.getGainFraction(currentStep) * 100).toInt()
        textVolumePercent?.text = "$percent%"
        textStepCount?.text = "$currentStep/$maxSteps"

        if (overlayView?.windowToken == null) {
            val density = resources.displayMetrics.density
            val overlayWidthPx = (44 * density).toInt()
            val overlayHeightPx = (210 * density).toInt()

            val params = WindowManager.LayoutParams(
                overlayWidthPx,
                overlayHeightPx,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                x = (12 * density).toInt()
                y = (-100 * density).toInt()
            }

            try {
                windowManager?.addView(overlayView, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        scheduleHideTimer()
    }

    private fun createOverlayView() {
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_volume_slider, null)

        volumeBar = overlayView?.findViewById(R.id.verticalVolumeBar)
        textVolumePercent = overlayView?.findViewById(R.id.textVolumePercent)
        textStepCount = overlayView?.findViewById(R.id.textStepCount)

        volumeBar?.onProgressChangeListener = { progress, fromUser ->
            if (fromUser) {
                audioGainManager.currentStep = progress
                val percent = (audioGainManager.getGainFraction(progress) * 100).toInt()
                textVolumePercent?.text = "$percent%"
                textStepCount?.text = "$progress/${audioGainManager.maxSteps}"
                scheduleHideTimer()
            }
        }

        val imageVolumeIcon: View? = overlayView?.findViewById(R.id.imageVolumeIcon)
        imageVolumeIcon?.setOnClickListener {
            hideOverlay()
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_SAME,
                    AudioManager.FLAG_SHOW_UI
                )
            } catch (e: Exception) {
                try {
                    val soundIntent = Intent(Settings.ACTION_SOUND_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(soundIntent)
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            }
        }

        overlayView?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hideOverlay()
                true
            } else {
                false
            }
        }
    }

    private fun scheduleHideTimer() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    private fun hideOverlay() {
        try {
            if (overlayView != null && overlayView?.windowToken != null) {
                windowManager?.removeView(overlayView)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioGainManager.release()
    }

    companion object {
        const val ACTION_SHOW_OVERLAY = "com.example.finevolume.ACTION_SHOW_OVERLAY"
        const val EXTRA_CURRENT_STEP = "extra_current_step"
        const val EXTRA_MAX_STEPS = "extra_max_steps"

        private const val NOTIFICATION_ID = 1001
        private const val HIDE_DELAY_MS = 2500L
    }
}
