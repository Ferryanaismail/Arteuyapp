package io.github.artcraftandroid.procraft.model

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap

enum class ToolKind { PAINT, SMUDGE, ERASE }

enum class Panel { NONE, LAYERS, COLOR, BRUSHES, ACTIONS, ADJUSTMENTS }

enum class CanvasAssistMode { NONE, ANIMATION, PAGE_ASSIST }

enum class GallerySortMode(val label: String) {
    RECENT("Recent"),
    NAME("Name"),
}

enum class TransformInterpolation(val label: String) {
    BILINEAR("Bilinear"),
    NEAREST("Nearest"),
}

enum class CurveChannel(val label: String) {
    ALL("RGB"),
    RED("Red"),
    GREEN("Green"),
    BLUE("Blue"),
}

enum class LayerBlendMode(val label: String, val group: String) {
    NORMAL("Normal", "Normal"),
    MULTIPLY("Multiply", "Darken"),
    DARKEN("Darken", "Darken"),
    COLOR_BURN("Color Burn", "Darken"),
    LINEAR_BURN("Linear Burn", "Darken"),
    LIGHTEN("Lighten", "Lighten"),
    SCREEN("Screen", "Lighten"),
    COLOR_DODGE("Color Dodge", "Lighten"),
    ADD("Add", "Lighten"),
    OVERLAY("Overlay", "Contrast"),
    SOFT_LIGHT("Soft Light", "Contrast"),
    HARD_LIGHT("Hard Light", "Contrast"),
    DIFFERENCE("Difference", "Difference"),
    EXCLUSION("Exclusion", "Difference"),
    SUBTRACT("Subtract", "Difference"),
    DIVIDE("Divide", "Difference"),
    HUE("Hue", "Color"),
    SATURATION("Saturation", "Color"),
    COLOR("Color", "Color"),
    LUMINOSITY("Luminosity", "Color"),
}

enum class BrushShapeKind(val label: String) {
    ROUND("Round"),
    SOFT_AIR("Soft Airbrush"),
    FLAT_BRISTLE("Flat Bristle"),
    GRAINY_CHALK("Grainy Chalk"),
    CALLIGRAPHY_FLAT("Calligraphy Nib"),
    SPECKLE_SPRAY("Speckle Spray"),
    WATERCOLOR_BLOOM("Watercolor"),
    INK_NIB("Technical Ink"),
}

data class BrushSettings(
    val size: Float = 0.28f, // 0f..1f normalized
    val opacity: Float = 1f, // 0f..1f
    val spacing: Float = 0.12f, // 0.02f..0.8f
    val hardness: Float = 0.85f, // 0f..1f
    val streamline: Float = 0.35f, // 0f..1f
    val scatter: Float = 0f, // 0f..1f
    val jitter: Float = 0f, // 0f..1f size jitter
    val taperStart: Float = 0.15f, // 0f..1f
    val taperEnd: Float = 0.25f, // 0f..1f
    val pressureSize: Float = 0.65f, // 0f..1f
    val pressureOpacity: Float = 0.2f, // 0f..1f
    val roundness: Float = 1f, // 0.15f..1f
    val angleDeg: Float = 0f, // -90f..90f
    val wetMix: Float = 0f, // 0f..1f smudge pull
    val grainAmount: Float = 0f, // 0f..1f texture grain
    val grainScale: Float = 1f, // 0.3f..2.5f
    val grainMoving: Boolean = false,
    val hueJitter: Float = 0f, // 0f..1f
    val saturationJitter: Float = 0f, // 0f..1f
    val shapeKind: BrushShapeKind = BrushShapeKind.ROUND,
    val quickShapeEnabled: Boolean = true,
)

data class BrushPreset(
    val id: String,
    val name: String,
    val setId: String,
    val settings: BrushSettings,
    val isCustom: Boolean = false,
)

data class BrushSet(
    val id: String,
    val name: String,
    val brushes: List<BrushPreset>,
    val isCustom: Boolean = false,
)

enum class AdjustmentKind(val label: String, val group: String) {
    HSB("Hue, Saturation, Brightness", "Color"),
    COLOR_BALANCE("Color Balance", "Color"),
    CURVES("Curves", "Color"),
    GRADIENT_MAP("Gradient Map", "Color"),
    GAUSSIAN_BLUR("Gaussian Blur", "Blur"),
    MOTION_BLUR("Motion Blur", "Blur"),
    PERSPECTIVE_BLUR("Perspective Blur", "Blur"),
    NOISE("Noise", "Effects"),
    SHARPEN("Sharpen", "Effects"),
    BLOOM("Bloom", "Effects"),
    GLITCH("Glitch", "Effects"),
    HALFTONE("Halftone", "Effects"),
    CHROMATIC_ABERRATION("Chromatic Aberration", "Effects"),
    LIQUIFY("Liquify", "Warp"),
    CLONE("Clone", "Paint"),
}

enum class LiquifyMode(val label: String) {
    PUSH("Push"),
    TWIRL_CW("Twirl Right"),
    TWIRL_CCW("Twirl Left"),
    PINCH("Pinch"),
    EXPAND("Expand"),
}

enum class SelectionKind(val label: String) {
    AUTOMATIC("Automatic"),
    FREEHAND("Freehand"),
    RECTANGLE("Rectangle"),
    ELLIPSE("Ellipse"),
}

enum class TransformKind(val label: String) {
    FREEFORM("Freeform"),
    UNIFORM("Uniform"),
    DISTORT("Distort"),
    WARP("Warp"),
}

enum class GuideKind(val label: String) {
    GRID_2D("2D Grid"),
    ISOMETRIC("Isometric"),
    PERSPECTIVE("Perspective"),
    SYMMETRY_VERTICAL("Vertical Symmetry"),
    SYMMETRY_HORIZONTAL("Horizontal Symmetry"),
    SYMMETRY_QUADRANT("Quadrant Symmetry"),
    SYMMETRY_RADIAL("Radial Symmetry"),
}

data class GuideConfig(
    val enabled: Boolean = false,
    val assisted: Boolean = false,
    val kind: GuideKind = GuideKind.GRID_2D,
    val gridSpacingPx: Float = 80f,
    val opacity: Float = 0.55f,
    val thickness: Float = 1.5f,
    val colorRgb: Int = 0xFF4BA3FF.toInt(),
    val radialSegments: Int = 6,
    val perspectivePoints: List<Offset> = listOf(Offset(0.25f, 0.5f), Offset(0.75f, 0.5f)),
)

enum class CanvasUnit(val label: String) {
    PX("px"),
    MM("mm"),
    CM("cm"),
    IN("in"),
}

data class CanvasPreset(
    val id: String,
    val name: String,
    val width: Float,
    val height: Float,
    val unit: CanvasUnit,
    val dpi: Int = 300,
    val colorProfile: String = "sRGB IEC61966-2.1",
    val backgroundRgb: Int = 0xFFFFFFFF.toInt(),
    val timelapseEnabled: Boolean = true,
    val isCustom: Boolean = false,
) {
    val pixelWidth: Int
        get() = when (unit) {
            CanvasUnit.PX -> width.toInt().coerceIn(64, 4096)
            CanvasUnit.IN -> (width * dpi).toInt().coerceIn(64, 4096)
            CanvasUnit.CM -> ((width / 2.54f) * dpi).toInt().coerceIn(64, 4096)
            CanvasUnit.MM -> ((width / 25.4f) * dpi).toInt().coerceIn(64, 4096)
        }

    val pixelHeight: Int
        get() = when (unit) {
            CanvasUnit.PX -> height.toInt().coerceIn(64, 4096)
            CanvasUnit.IN -> (height * dpi).toInt().coerceIn(64, 4096)
            CanvasUnit.CM -> ((height / 2.54f) * dpi).toInt().coerceIn(64, 4096)
            CanvasUnit.MM -> ((height / 25.4f) * dpi).toInt().coerceIn(64, 4096)
        }

    val dimensionLabel: String
        get() = "${pixelWidth} × ${pixelHeight} px"
}

enum class ColorMode(val label: String) {
    DISC("Disc"),
    CLASSIC("Classic"),
    HARMONY("Harmony"),
    VALUE("Value"),
    PALETTES("Palettes"),
}

enum class HarmonyKind(val label: String, val offsetsDeg: List<Float>) {
    COMPLEMENTARY("Complementary", listOf(180f)),
    SPLIT_COMPLEMENTARY("Split Complementary", listOf(150f, 210f)),
    ANALOGOUS("Analogous", listOf(-30f, 30f)),
    TRIADIC("Triadic", listOf(120f, 240f)),
    TETRADIC("Tetradic", listOf(90f, 180f, 270f)),
}

data class ColorPalette(
    val id: String,
    val name: String,
    val swatches: List<Int?>,
    val isDefault: Boolean = false,
)

enum class QuickAction(val label: String) {
    NEW_LAYER("New Layer"),
    COPY("Copy Layer"),
    PASTE("Paste"),
    CUT("Cut"),
    DUPLICATE_LAYER("Duplicate Layer"),
    MERGE_DOWN("Merge Down"),
    CLEAR_LAYER("Clear Layer"),
    FLIP_HORIZONTAL("Flip Horizontal"),
    FLIP_VERTICAL("Flip Vertical"),
    ALPHA_LOCK("Alpha Lock"),
    EYEDROPPER("Eyedropper"),
    FULL_SCREEN("Full Screen"),
    UNDO("Undo"),
    REDO("Redo"),
    COPY_PASTE_MENU("Copy & Paste"),
    SELECT_PIXELS("Select Pixels"),
}

data class GestureSettings(
    val quickMenuEnabled: Boolean = true,
    val quickMenuSlots: List<QuickAction> = listOf(
        QuickAction.NEW_LAYER,
        QuickAction.CLEAR_LAYER,
        QuickAction.FLIP_HORIZONTAL,
        QuickAction.COPY,
        QuickAction.ALPHA_LOCK,
        QuickAction.FULL_SCREEN,
    ),
    val eyedropperHoldMs: Int = 380,
    val twoFingerUndo: Boolean = true,
    val threeFingerRedo: Boolean = true,
    val threeFingerClear: Boolean = true,
    val threeFingerCopyPaste: Boolean = true,
    val fourFingerFullscreen: Boolean = true,
    val rightHanded: Boolean = false,
    val lightInterface: Boolean = false,
    val brushCursor: Boolean = true,
    val palmRejection: Boolean = false,
    val strokeSoundEnabled: Boolean = false,
    val zoomHudEnabled: Boolean = true,
    val colorDropThreshold: Float = 0.25f,
)

data class LayerState(
    val id: String,
    val name: String,
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val blendMode: LayerBlendMode = LayerBlendMode.NORMAL,
    val locked: Boolean = false,
    val alphaLock: Boolean = false,
    val clippingMask: Boolean = false,
    val isMask: Boolean = false,
    val isReference: Boolean = false,
    val bitmap: Bitmap,
    val thumbnail: ImageBitmap? = null,
    val isPrivate: Boolean = false,
)

data class TextLayerConfig(
    val text: String = "ProCraft Text",
    val xFraction: Float = 0.5f,
    val yFraction: Float = 0.5f,
    val fontSizePx: Float = 96f,
    val colorRgb: Int = 0xFF000000.toInt(),
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val alignment: Int = 1, // 0=left, 1=center, 2=right
)

data class ArtworkRecord(
    val id: String,
    val title: String,
    val width: Int,
    val height: Int,
    val dpi: Int = 300,
    val colorProfile: String = "sRGB IEC61966-2.1",
    val backgroundRgb: Int = 0xFFFFFFFF.toInt(),
    val backgroundVisible: Boolean = true,
    val timelapseEnabled: Boolean = true,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val totalStrokes: Long = 0L,
    val trackedSeconds: Long = 0L,
    val stackId: String? = null,
    val stackName: String? = null,
    val order: Int = 0,
    val thumbnailPath: String? = null,
)

sealed class GalleryItem {
    abstract val sortOrder: Int
    abstract val title: String
    abstract val updatedAtMs: Long

    data class SingleArtwork(val artwork: ArtworkRecord) : GalleryItem() {
        override val sortOrder: Int get() = artwork.order
        override val title: String get() = artwork.title
        override val updatedAtMs: Long get() = artwork.updatedAtMs
    }

    data class StackGroup(
        val stackId: String,
        val name: String,
        val items: List<ArtworkRecord>,
    ) : GalleryItem() {
        override val sortOrder: Int get() = items.minOfOrNull { it.order } ?: 0
        override val title: String get() = name
        override val updatedAtMs: Long get() = items.maxOfOrNull { it.updatedAtMs } ?: 0L
    }
}

data class GradientPreset(
    val id: String,
    val name: String,
    val colors: List<Int>,
)

data class CropState(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class PickLoupeState(
    val screenPos: Offset,
    val colorRgb: Int,
    val prevColorRgb: Int,
)

data class AnimationState(
    val enabled: Boolean = false,
    val assistMode: CanvasAssistMode = CanvasAssistMode.NONE,
    val playing: Boolean = false,
    val fps: Int = 12,
    val totalFrames: Int = 24,
    val onionSkinEnabled: Boolean = true,
    val onionSkinFrames: Int = 2,
    val onionSkinOpacity: Float = 0.35f,
    val currentFrameIndex: Int = 0,
    val audioScrubEnabled: Boolean = true,
    val selectedTrackId: String? = null,
    val selectedClipId: String? = null,
    val timelineZoom: Float = 1f,
) {
    val isAnimation: Boolean get() = enabled && assistMode == CanvasAssistMode.ANIMATION
    val isPageAssist: Boolean get() = enabled && assistMode == CanvasAssistMode.PAGE_ASSIST
}
