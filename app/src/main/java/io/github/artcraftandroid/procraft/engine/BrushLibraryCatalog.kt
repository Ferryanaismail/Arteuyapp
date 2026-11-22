package io.github.artcraftandroid.procraft.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.artcraftandroid.procraft.model.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object BrushLibraryCatalog {
    fun defaultBrushSets(): List<BrushSet> = listOf(
        BrushSet(
            id = "sketching",
            name = "Sketching",
            brushes = listOf(
                preset("sketch_hb", "HB Pencil", "sketching", BrushSettings(size = 0.14f, opacity = 0.85f, spacing = 0.08f, hardness = 0.65f, grainAmount = 0.45f, pressureSize = 0.55f, pressureOpacity = 0.6f, shapeKind = BrushShapeKind.GRAINY_CHALK)),
                preset("sketch_6b", "6B Pencil", "sketching", BrushSettings(size = 0.24f, opacity = 0.92f, spacing = 0.06f, hardness = 0.5f, grainAmount = 0.65f, pressureSize = 0.7f, pressureOpacity = 0.5f, shapeKind = BrushShapeKind.GRAINY_CHALK)),
                preset("sketch_technical", "Technical Pencil", "sketching", BrushSettings(size = 0.10f, opacity = 0.9f, spacing = 0.05f, hardness = 0.85f, grainAmount = 0.2f, pressureSize = 0.35f, shapeKind = BrushShapeKind.INK_NIB)),
                preset("sketch_derwent", "Graphite Sketch", "sketching", BrushSettings(size = 0.32f, opacity = 0.7f, spacing = 0.10f, hardness = 0.4f, grainAmount = 0.72f, scatter = 0.15f, shapeKind = BrushShapeKind.GRAINY_CHALK)),
            ),
        ),
        BrushSet(
            id = "inking",
            name = "Inking",
            brushes = listOf(
                preset("ink_studio", "Studio Pen", "inking", BrushSettings(size = 0.22f, opacity = 1f, spacing = 0.04f, hardness = 0.98f, streamline = 0.65f, taperStart = 0.25f, taperEnd = 0.35f, pressureSize = 0.85f, shapeKind = BrushShapeKind.INK_NIB)),
                preset("ink_technical", "Technical Pen", "inking", BrushSettings(size = 0.14f, opacity = 1f, spacing = 0.04f, hardness = 1f, streamline = 0.55f, pressureSize = 0.2f, shapeKind = BrushShapeKind.ROUND)),
                preset("ink_syrup", "Syrup", "inking", BrushSettings(size = 0.30f, opacity = 1f, spacing = 0.04f, hardness = 0.96f, streamline = 0.78f, taperStart = 0.3f, taperEnd = 0.4f, pressureSize = 0.9f, shapeKind = BrushShapeKind.INK_NIB)),
                preset("ink_dry", "Dry Ink", "inking", BrushSettings(size = 0.26f, opacity = 0.95f, spacing = 0.08f, hardness = 0.82f, grainAmount = 0.55f, jitter = 0.18f, streamline = 0.45f, shapeKind = BrushShapeKind.GRAINY_CHALK)),
            ),
        ),
        BrushSet(
            id = "calligraphy",
            name = "Calligraphy",
            brushes = listOf(
                preset("cal_monoline", "Monoline", "calligraphy", BrushSettings(size = 0.20f, opacity = 1f, spacing = 0.04f, hardness = 0.98f, streamline = 0.82f, pressureSize = 0.05f, shapeKind = BrushShapeKind.ROUND)),
                preset("cal_script", "Script", "calligraphy", BrushSettings(size = 0.26f, opacity = 1f, spacing = 0.04f, hardness = 0.95f, streamline = 0.72f, roundness = 0.45f, angleDeg = 30f, pressureSize = 0.85f, shapeKind = BrushShapeKind.CALLIGRAPHY_FLAT)),
                preset("cal_brushpen", "Brush Pen", "calligraphy", BrushSettings(size = 0.34f, opacity = 1f, spacing = 0.05f, hardness = 0.92f, streamline = 0.65f, roundness = 0.55f, angleDeg = 20f, taperStart = 0.35f, taperEnd = 0.45f, pressureSize = 0.95f, shapeKind = BrushShapeKind.CALLIGRAPHY_FLAT)),
                preset("cal_chisel", "Chisel Marker", "calligraphy", BrushSettings(size = 0.38f, opacity = 0.88f, spacing = 0.05f, hardness = 0.9f, streamline = 0.5f, roundness = 0.25f, angleDeg = -35f, shapeKind = BrushShapeKind.CALLIGRAPHY_FLAT)),
            ),
        ),
        BrushSet(
            id = "painting",
            name = "Painting",
            brushes = listOf(
                preset("paint_round", "Round Brush", "painting", BrushSettings(size = 0.36f, opacity = 0.95f, spacing = 0.07f, hardness = 0.75f, wetMix = 0.25f, pressureSize = 0.65f, pressureOpacity = 0.4f, shapeKind = BrushShapeKind.ROUND)),
                preset("paint_flat", "Flat Brush", "painting", BrushSettings(size = 0.45f, opacity = 0.92f, spacing = 0.08f, hardness = 0.8f, roundness = 0.4f, wetMix = 0.35f, grainAmount = 0.25f, shapeKind = BrushShapeKind.FLAT_BRISTLE)),
                preset("paint_oil", "Oil Paint", "painting", BrushSettings(size = 0.48f, opacity = 0.96f, spacing = 0.07f, hardness = 0.78f, roundness = 0.55f, wetMix = 0.55f, grainAmount = 0.35f, shapeKind = BrushShapeKind.FLAT_BRISTLE)),
                preset("paint_acrylic", "Acrylic", "painting", BrushSettings(size = 0.40f, opacity = 0.9f, spacing = 0.08f, hardness = 0.85f, roundness = 0.65f, grainAmount = 0.3f, shapeKind = BrushShapeKind.FLAT_BRISTLE)),
            ),
        ),
        BrushSet(
            id = "airbrushing",
            name = "Airbrushing",
            brushes = listOf(
                preset("air_soft", "Soft Brush", "airbrushing", BrushSettings(size = 0.55f, opacity = 0.65f, spacing = 0.06f, hardness = 0.08f, pressureOpacity = 0.75f, shapeKind = BrushShapeKind.SOFT_AIR)),
                preset("air_medium", "Medium Brush", "airbrushing", BrushSettings(size = 0.42f, opacity = 0.75f, spacing = 0.06f, hardness = 0.35f, pressureOpacity = 0.65f, shapeKind = BrushShapeKind.SOFT_AIR)),
                preset("air_hard", "Hard Airbrush", "airbrushing", BrushSettings(size = 0.35f, opacity = 0.85f, spacing = 0.05f, hardness = 0.65f, pressureOpacity = 0.55f, shapeKind = BrushShapeKind.SOFT_AIR)),
            ),
        ),
        BrushSet(
            id = "watercolour",
            name = "Watercolour",
            brushes = listOf(
                preset("wc_wash", "Watercolor Wash", "watercolour", BrushSettings(size = 0.58f, opacity = 0.45f, spacing = 0.10f, hardness = 0.25f, wetMix = 0.4f, grainAmount = 0.4f, jitter = 0.15f, shapeKind = BrushShapeKind.WATERCOLOR_BLOOM)),
                preset("wc_wet", "Wet Bleed", "watercolour", BrushSettings(size = 0.44f, opacity = 0.55f, spacing = 0.09f, hardness = 0.35f, wetMix = 0.65f, scatter = 0.12f, shapeKind = BrushShapeKind.WATERCOLOR_BLOOM)),
            ),
        ),
        BrushSet(
            id = "charcoals",
            name = "Charcoals",
            brushes = listOf(
                preset("char_vine", "Vine Charcoal", "charcoals", BrushSettings(size = 0.42f, opacity = 0.85f, spacing = 0.10f, hardness = 0.45f, grainAmount = 0.8f, scatter = 0.18f, jitter = 0.25f, shapeKind = BrushShapeKind.GRAINY_CHALK)),
                preset("char_compressed", "Compressed", "charcoals", BrushSettings(size = 0.34f, opacity = 0.95f, spacing = 0.08f, hardness = 0.65f, grainAmount = 0.7f, shapeKind = BrushShapeKind.GRAINY_CHALK)),
            ),
        ),
        BrushSet(
            id = "textures",
            name = "Textures & Effects",
            brushes = listOf(
                preset("tex_spray", "Splatter Spray", "textures", BrushSettings(size = 0.52f, opacity = 0.85f, spacing = 0.18f, hardness = 0.85f, scatter = 0.85f, jitter = 0.65f, shapeKind = BrushShapeKind.SPECKLE_SPRAY)),
                preset("tex_stipple", "Fine Stipple", "textures", BrushSettings(size = 0.35f, opacity = 0.9f, spacing = 0.14f, hardness = 0.9f, scatter = 0.6f, jitter = 0.45f, shapeKind = BrushShapeKind.SPECKLE_SPRAY)),
                preset("tex_chalk", "Pastel Chalk", "textures", BrushSettings(size = 0.48f, opacity = 0.88f, spacing = 0.09f, hardness = 0.5f, grainAmount = 0.85f, shapeKind = BrushShapeKind.GRAINY_CHALK)),
            ),
        ),
    )

    private fun preset(id: String, name: String, setId: String, settings: BrushSettings) =
        BrushPreset(id = id, name = name, setId = setId, settings = settings)

    fun standardPresets(): List<CanvasPreset> = listOf(
        CanvasPreset("screen", "Screen Size", 2048f, 2048f, CanvasUnit.PX, 300),
        CanvasPreset("square", "Square", 2048f, 2048f, CanvasUnit.PX, 300),
        CanvasPreset("4k", "4K Cinema", 3840f, 2160f, CanvasUnit.PX, 300),
        CanvasPreset("a4", "A4 Print", 210f, 297f, CanvasUnit.MM, 300),
        CanvasPreset("photo4x6", "4 × 6 Photo", 4f, 6f, CanvasUnit.IN, 300),
        CanvasPreset("paper", "Portrait Paper", 1600f, 2200f, CanvasUnit.PX, 300),
        CanvasPreset("comic", "Comic Page", 1988f, 3056f, CanvasUnit.PX, 300),
    )

    fun defaultPalettes(): List<ColorPalette> = listOf(
        ColorPalette(
            id = "pal_studio",
            name = "Studio Essentials",
            isDefault = true,
            swatches = listOf(
                0xFF141416.toInt(), 0xFF3A3A3F.toInt(), 0xFF7D7D85.toInt(), 0xFFC5C5CC.toInt(), 0xFFFFFFFF.toInt(),
                0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFCC00.toInt(), 0xFF34C759.toInt(), 0xFF00C7BE.toInt(),
                0xFF32ADE6.toInt(), 0xFF0A84FF.toInt(), 0xFF5856D6.toInt(), 0xFFAF52DE.toInt(), 0xFFFF2D55.toInt(),
                0xFFA2845E.toInt(), 0xFF6B4423.toInt(), 0xFFFFD1A9.toInt(), 0xFFE89B7C.toInt(), 0xFFB04A3F.toInt(),
                0xFF1B365D.toInt(), 0xFF2B6CB0.toInt(), 0xFF4FD1C5.toInt(), 0xFF9AE6B4.toInt(), 0xFFFBD38D.toInt(),
                0xFFF687B3.toInt(), 0xFFD6BCFA.toInt(), 0xFF718096.toInt(), 0xFF2D3748.toInt(), 0xFFEDF2F7.toInt(),
            ),
        ),
        ColorPalette(
            id = "pal_pastel",
            name = "Botanical Pastel",
            swatches = listOf(
                0xFFFDF2F8.toInt(), 0xFFFCE7F3.toInt(), 0xFFFBCFE8.toInt(), 0xFFF9A8D4.toInt(), 0xFFF472B6.toInt(),
                0xFFECFDF5.toInt(), 0xFFD1FAE5.toInt(), 0xFFA7F3D0.toInt(), 0xFF6EE7B7.toInt(), 0xFF34D399.toInt(),
                0xFFEFF6FF.toInt(), 0xFFDBEAFE.toInt(), 0xFFBFDBFE.toInt(), 0xFF93C5FD.toInt(), 0xFF60A5FA.toInt(),
                0xFFFFFBEB.toInt(), 0xFFFEF3C7.toInt(), 0xFFFDE68A.toInt(), 0xFFFCD34D.toInt(), 0xFFFBBF24.toInt(),
                0xFFF5F3FF.toInt(), 0xFFEDE9FE.toInt(), 0xFFDDD6FE.toInt(), 0xFFC4B5FD.toInt(), 0xFFA78BFA.toInt(),
            ),
        ),
        ColorPalette(
            id = "pal_ink",
            name = "Sumi & Ochre",
            swatches = listOf(
                0xFF0E0E10.toInt(), 0xFF232220.toInt(), 0xFF44403C.toInt(), 0xFF78716C.toInt(), 0xFFD6D3D1.toInt(),
                0xFF7C2D12.toInt(), 0xFF9A3412.toInt(), 0xFFC2410C.toInt(), 0xFFEA580C.toInt(), 0xFFFB923C.toInt(),
                0xFF713F12.toInt(), 0xFFA16207.toInt(), 0xFFCA8A04.toInt(), 0xFFEAB308.toInt(), 0xFFFACC15.toInt(),
                0xFF14532D.toInt(), 0xFF166534.toInt(), 0xFF15803D.toInt(), 0xFF16A34A.toInt(), 0xFF4ADE80.toInt(),
            ),
        ),
    )

    fun gradientPresets(): List<GradientPreset> = listOf(
        GradientPreset("grad_breeze", "Breeze", listOf(0xFF0E1B3B.toInt(), 0xFF2E78D2.toInt(), 0xFF98E2FF.toInt(), 0xFFFFFFFF.toInt())),
        GradientPreset("grad_sunset", "Velvet Dusk", listOf(0xFF1E0B36.toInt(), 0xFF8E24AA.toInt(), 0xFFFF6D00.toInt(), 0xFFFFE57F.toInt())),
        GradientPreset("grad_mono", "Noir", listOf(0xFF000000.toInt(), 0xFF555555.toInt(), 0xFFAAAAAA.toInt(), 0xFFFFFFFF.toInt())),
        GradientPreset("grad_neon", "Cyber", listOf(0xFF0B0B2E.toInt(), 0xFFFF0080.toInt(), 0xFF00F0FF.toInt(), 0xFFFFFFCC.toInt())),
        GradientPreset("grad_gold", "Bronze", listOf(0xFF23140A.toInt(), 0xFF8C5327.toInt(), 0xFFE6AC5E.toInt(), 0xFFFFF3D1.toInt())),
    )

    fun renderStrokePreview(brush: BrushPreset, colorRgb: Int, width: Int = 340, height: Int = 64): ImageBitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val s = brush.settings
        val rng = Random(brush.id.hashCode())
        val baseR = (height * 0.14f + height * 0.24f * s.size).coerceIn(3f, height * 0.34f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorRgb
        }
        val tipBmp = s.tipPath?.let { path -> BitmapFactory.decodeFile(path) }
        val tipPaint = if (tipBmp != null) {
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(colorRgb, PorterDuff.Mode.SRC_IN)
            }
        } else null
        val dstRect = RectF()
        val steps = 80
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = 22f + t * (width - 44f)
            val y = height * 0.5f + sin(t * 2.2f * PI.toFloat()) * (height * 0.24f)
            val pressure = (0.35f + 0.65f * sin(t * PI.toFloat())).coerceIn(0.15f, 1f)
            val taper = when {
                t < 0.15f -> (t / 0.15f).coerceAtLeast(1f - s.taperStart)
                t > 0.85f -> ((1f - t) / 0.15f).coerceAtLeast(1f - s.taperEnd)
                else -> 1f
            }
            val r = (baseR * (1f - s.pressureSize * (1f - pressure)) * taper).coerceAtLeast(1f)
            val alpha = (255 * s.opacity * (1f - s.pressureOpacity * (1f - pressure))).toInt().coerceIn(8, 255)
            if (tipBmp != null && tipPaint != null) {
                tipPaint.alpha = alpha
                dstRect.set(x - r, y - r * s.roundness, x + r, y + r * s.roundness)
                canvas.drawBitmap(tipBmp, null, dstRect, tipPaint)
                continue
            }
            paint.alpha = alpha
            when (s.shapeKind) {
                BrushShapeKind.SPECKLE_SPRAY -> {
                    for (k in 0..3) {
                        val ox = (rng.nextFloat() - 0.5f) * r * 2.2f
                        val oy = (rng.nextFloat() - 0.5f) * r * 2.2f
                        canvas.drawCircle(x + ox, y + oy, (r * 0.28f).coerceAtLeast(1f), paint)
                    }
                }
                BrushShapeKind.GRAINY_CHALK -> {
                    for (k in 0..2) {
                        val ox = (rng.nextFloat() - 0.5f) * r * 0.9f
                        val oy = (rng.nextFloat() - 0.5f) * r * 0.9f
                        paint.alpha = (alpha * (0.45f + 0.55f * rng.nextFloat())).toInt()
                        canvas.drawCircle(x + ox, y + oy, r * 0.68f, paint)
                    }
                }
                BrushShapeKind.CALLIGRAPHY_FLAT -> {
                    canvas.save()
                    canvas.translate(x, y)
                    canvas.rotate(s.angleDeg)
                    canvas.drawOval(-r, -r * s.roundness, r, r * s.roundness, paint)
                    canvas.restore()
                }
                BrushShapeKind.SOFT_AIR -> {
                    paint.alpha = (alpha * 0.35f).toInt().coerceAtLeast(6)
                    canvas.drawCircle(x, y, r * 1.15f, paint)
                }
                else -> {
                    canvas.drawCircle(x, y, r, paint)
                }
            }
        }
        tipBmp?.recycle()
        return bmp.asImageBitmap()
    }
}
