package com.example.finevolume.service

import android.content.Context
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.util.Log
import com.example.finevolume.audio.AudioGainManager

/**
 * Keeps fine volume stepping alive while the keyguard is up.
 *
 * MagicOS stops routing volume keys to the accessibility service once the
 * device locks, and a MediaSession holding a remote [VolumeProvider] is the
 * only mechanism that still sees them over the keyguard.
 *
 * The session is deliberately kept out of the media-button routing chain:
 *
 *  - it never publishes a PlaybackState, so it can never become the session
 *    that last reported STATE_PLAYING, which is what Android picks as the
 *    media button session;
 *  - it registers no [MediaSession.Callback], so it has nothing that could
 *    consume a transport key even if one were routed here;
 *  - it exists only while locked and is released on unlock, keeping the
 *    exposure window as small as possible.
 *
 * Volume-key routing is chosen separately, by remote-playback type, which is
 * why the split is expected to hold. It has not been verified on MagicOS, so
 * the whole feature sits behind [AudioGainManager.lockscreenFineControlEnabled].
 */
class LockscreenVolumeSession(
    private val context: Context,
    private val gain: AudioGainManager,
    private val onStepChanged: (Int) -> Unit
) {

    private var session: MediaSession? = null

    val isActive: Boolean
        get() = session != null

    private fun buildVolumeProvider(): VolumeProvider {
        val max = gain.maxSteps
        val current = gain.currentStep.coerceIn(0, max)

        return object : VolumeProvider(VOLUME_CONTROL_RELATIVE, max, current) {
            override fun onAdjustVolume(direction: Int) {
                val newStep = when {
                    direction > 0 -> gain.stepUp()
                    direction < 0 -> gain.stepDown()
                    else -> gain.currentStep
                }
                currentVolume = newStep
                onStepChanged(newStep)
            }

            override fun onSetVolumeTo(volume: Int) {
                val clamped = volume.coerceIn(0, gain.maxSteps)
                gain.currentStep = clamped
                currentVolume = clamped
                onStepChanged(clamped)
            }
        }
    }

    fun start() {
        if (session != null) return
        if (!gain.lockscreenFineControlEnabled) return

        try {
            session = MediaSession(context, TAG).apply {
                setPlaybackToRemote(buildVolumeProvider())
                // No setCallback and no setPlaybackState: see the class docs.
                isActive = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start lockscreen volume session: ${e.message}")
            session = null
        }
    }

    fun stop() {
        try {
            session?.apply {
                isActive = false
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing lockscreen volume session: ${e.message}")
        }
        session = null
    }

    companion object {
        private const val TAG = "FineVolumeLockscreen"
    }
}
