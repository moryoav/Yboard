package com.yboard.keyboard.keyboard

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.yboard.keyboard.settings.YboardSettings
import kotlin.math.roundToInt

/** Key-press vibration and click sounds. */
class Feedback(private val context: Context) {
    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: Exception) {
        null
    }
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun keyDown(type: KeyType?, s: YboardSettings, light: Boolean = false) {
        if (s.haptics) vibrate(if (light) s.hapticStrength * 0.6f else s.hapticStrength)
        if (s.sound && !light) click(type, s)
    }

    /** Plays one click in the chosen style (used for the settings preview too). */
    fun preview(s: YboardSettings) = click(KeyType.CHAR, s)

    fun vibrate(strength: Float) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val st = strength.coerceIn(0.05f, 1f)
        val duration = (6 + st * 22).roundToInt().toLong()
        try {
            if (v.hasAmplitudeControl()) {
                v.vibrate(VibrationEffect.createOneShot(duration, (st * 254 + 1).roundToInt().coerceIn(1, 255)))
            } else {
                v.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {
        }
    }

    private fun click(type: KeyType?, s: YboardSettings) {
        val am = audio ?: return
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val volume = s.soundVolume
        if (s.soundStyle != "system") {
            try {
                KeySounds.play(context, s.soundStyle, type, volume)
            } catch (_: Exception) {
            }
            return
        }
        val fx = when (type) {
            KeyType.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
            KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
            KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        am.playSoundEffect(fx, volume.coerceIn(0.05f, 1f))
    }
}
