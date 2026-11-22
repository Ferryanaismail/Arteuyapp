package io.github.artcraftandroid.procraft.model

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap

enum class TrackKind(val label: String) {
    PAINT("Paint Track"),
    VIDEO("Video Track"),
    AUDIO("Audio Track"),
    GROUP("Group Track"),
}

enum class TrackColorLabel(val label: String, val argb: Int) {
    BLUE("Blue", 0xFF0A84FF.toInt()),
    PURPLE("Purple", 0xFFBF5AF2.toInt()),
    PINK("Pink", 0xFFFF375F.toInt()),
    RED("Red", 0xFFFF453A.toInt()),
    ORANGE("Orange", 0xFFFF9F0A.toInt()),
    YELLOW("Yellow", 0xFFFFD60A.toInt()),
    GREEN("Green", 0xFF30D158.toInt()),
    TEAL("Teal", 0xFF64D2FF.toInt()),
    SLATE("Slate", 0xFF8E8E93.toInt()),
}

data class TimelineClip(
    val id: String,
    val startFrame: Int,
    val durationFrames: Int,
    val label: String = "",
    val bitmap: Bitmap? = null,
    val thumbnail: ImageBitmap? = null,
    val videoFrames: List<Bitmap> = emptyList(),
    val sourceFrameOffset: Int = 0,
    val mediaUri: String? = null,
    val audioFilePath: String? = null,
    val audioSampleRate: Int = 22050,
    val waveformPeaks: FloatArray = FloatArray(0),
    val orderTargetTrackId: String? = null,
) {
    val endFrameExclusive: Int get() = startFrame + durationFrames.coerceAtLeast(1)

    fun coversFrame(frame: Int): Boolean =
        frame >= startFrame && frame < endFrameExclusive

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TimelineClip) return false
        return id == other.id &&
            startFrame == other.startFrame &&
            durationFrames == other.durationFrames &&
            label == other.label &&
            bitmap === other.bitmap &&
            thumbnail == other.thumbnail &&
            videoFrames === other.videoFrames &&
            sourceFrameOffset == other.sourceFrameOffset &&
            mediaUri == other.mediaUri &&
            audioFilePath == other.audioFilePath &&
            audioSampleRate == other.audioSampleRate &&
            waveformPeaks.contentEquals(other.waveformPeaks) &&
            orderTargetTrackId == other.orderTargetTrackId
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + startFrame
        result = 31 * result + durationFrames
        result = 31 * result + label.hashCode()
        result = 31 * result + (bitmap?.hashCode() ?: 0)
        result = 31 * result + sourceFrameOffset
        result = 31 * result + (mediaUri?.hashCode() ?: 0)
        result = 31 * result + (audioFilePath?.hashCode() ?: 0)
        result = 31 * result + (orderTargetTrackId?.hashCode() ?: 0)
        return result
    }
}

data class TimelineTrack(
    val id: String,
    val name: String,
    val kind: TrackKind,
    val colorLabel: TrackColorLabel = TrackColorLabel.BLUE,
    val visible: Boolean = true,
    val muted: Boolean = false,
    val locked: Boolean = false,
    val opacity: Float = 1f,
    val isLayerOrderGroup: Boolean = false,
    val layerId: String? = null,
    val groupId: String? = null,
    val expanded: Boolean = true,
    val clips: List<TimelineClip> = emptyList(),
) {
    fun clipAt(frame: Int): TimelineClip? = clips.firstOrNull { it.coversFrame(frame) }
}

data class CubicBezierControl(
    val x1: Float = 0.25f,
    val y1: Float = 0.1f,
    val x2: Float = 0.25f,
    val y2: Float = 1.0f,
)

enum class TweenCurveKind(val label: String) {
    LINEAR("Linear"),
    EASE_IN("Ease In"),
    EASE_OUT("Ease Out"),
    EASE_IN_OUT("Ease In-Out"),
    CUBIC_BEZIER("Cubic Bezier"),
    OVERSHOOT("Overshoot"),
    BOUNCE("Bounce"),
    SPRING("Spring"),
    STEP("Step");

    fun evaluate(rawT: Float, bezier: CubicBezierControl = CubicBezierControl()): Float {
        val t = rawT.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> t
            EASE_IN -> t * t * t
            EASE_OUT -> {
                val inv = 1f - t
                1f - inv * inv * inv
            }
            EASE_IN_OUT -> {
                if (t < 0.5f) 4f * t * t * t
                else {
                    val f = -2f * t + 2f
                    1f - (f * f * f) * 0.5f
                }
            }
            CUBIC_BEZIER -> evaluateCubicBezier(t, bezier.x1, bezier.y1, bezier.x2, bezier.y2)
            OVERSHOOT -> {
                val c1 = 1.70158f
                val c3 = c1 + 1f
                val tm1 = t - 1f
                1f + c3 * tm1 * tm1 * tm1 + c1 * tm1 * tm1
            }
            BOUNCE -> {
                val n1 = 7.5625f
                val d1 = 2.75f
                when {
                    t < 1f / d1 -> n1 * t * t
                    t < 2f / d1 -> {
                        val u = t - 1.5f / d1
                        n1 * u * u + 0.75f
                    }
                    t < 2.5f / d1 -> {
                        val u = t - 2.25f / d1
                        n1 * u * u + 0.9375f
                    }
                    else -> {
                        val u = t - 2.625f / d1
                        n1 * u * u + 0.984375f
                    }
                }
            }
            SPRING -> {
                val damp = kotlin.math.exp((-6.0 * t).toDouble()).toFloat()
                val osc = kotlin.math.cos((t * Math.PI * 3.5).toDouble()).toFloat()
                1f - damp * osc
            }
            STEP -> if (t >= 0.999f) 1f else 0f
        }
    }
}

private fun sampleBezierAxis(a1: Float, a2: Float, u: Float): Float {
    val c = 3f * a1
    val b = 3f * (a2 - a1) - c
    val a = 1f - c - b
    return ((a * u + b) * u + c) * u
}

private fun sampleBezierSlope(a1: Float, a2: Float, u: Float): Float {
    val c = 3f * a1
    val b = 3f * (a2 - a1) - c
    val a = 1f - c - b
    return (3f * a * u + 2f * b) * u + c
}

private fun evaluateCubicBezier(t: Float, rawX1: Float, y1: Float, rawX2: Float, y2: Float): Float {
    if (t <= 0f) return 0f
    if (t >= 1f) return 1f
    val x1 = rawX1.coerceIn(0f, 1f)
    val x2 = rawX2.coerceIn(0f, 1f)
    var u = t
    for (i in 0 until 6) {
        val xErr = sampleBezierAxis(x1, x2, u) - t
        if (kotlin.math.abs(xErr) < 1e-5f) {
            return sampleBezierAxis(y1, y2, u)
        }
        val slope = sampleBezierSlope(x1, x2, u)
        if (kotlin.math.abs(slope) < 1e-5f) break
        u = (u - xErr / slope).coerceIn(0f, 1f)
    }
    var lo = 0f
    var hi = 1f
    u = t
    for (i in 0 until 12) {
        val x = sampleBezierAxis(x1, x2, u)
        if (kotlin.math.abs(x - t) < 1e-5f) break
        if (t > x) lo = u else hi = u
        u = (lo + hi) * 0.5f
    }
    return sampleBezierAxis(y1, y2, u)
}

data class MotionKeyframe(
    val id: String,
    val trackId: String,
    val frameIndex: Int,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDeg: Float = 0f,
    val anchorX: Float? = null,
    val anchorY: Float? = null,
    val boundsLeft: Float? = null,
    val boundsTop: Float? = null,
    val boundsRight: Float? = null,
    val boundsBottom: Float? = null,
    val curve: TweenCurveKind = TweenCurveKind.EASE_IN_OUT,
    val bezier: CubicBezierControl = CubicBezierControl(),
    val sourceBitmap: Bitmap? = null,
    val baseBitmap: Bitmap? = null,
)

