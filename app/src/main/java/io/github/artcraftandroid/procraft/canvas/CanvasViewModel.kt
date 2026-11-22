package io.github.artcraftandroid.procraft.canvas

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.procraft.engine.*
import io.github.artcraftandroid.procraft.model.*
import io.github.artcraftandroid.procraft.ui.CanvasSpace
import io.github.artcraftandroid.procraft.ui.Pc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.*

enum class Screen { GALLERY, CANVAS }

private sealed interface HistorySnapshot {
    fun recycle()
}

private data class FullSnapshot(
    val layers: List<LayerSnapshot>,
    val activeLayerIndex: Int,
    val backgroundRgb: Int,
    val backgroundVisible: Boolean,
) : HistorySnapshot {
    override fun recycle() {
        layers.forEach { if (!it.pixelsCopy.isRecycled) it.pixelsCopy.recycle() }
    }
}

private data class TilePatch(
    val tx: Int,
    val ty: Int,
    val left: Int,
    val top: Int,
    val w: Int,
    val h: Int,
    val bitmap: Bitmap,
)

private data class TilePatchSnapshot(
    val layerId: String,
    val layerIndex: Int,
    val clipId: String? = null,
    val width: Int,
    val height: Int,
    val patches: List<TilePatch>,
) : HistorySnapshot {
    override fun recycle() {
        patches.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
    }
}

private class TileUndoRecorder(
    private val layerBitmap: Bitmap,
    private val width: Int,
    private val height: Int,
    private val tileSize: Int = CanvasTileCache.TILE_SIZE,
) {
    private val cols = ((width + tileSize - 1) / tileSize).coerceAtLeast(1)
    private val rows = ((height + tileSize - 1) / tileSize).coerceAtLeast(1)
    private val captured = LinkedHashMap<Int, TilePatch>()

    fun captureRect(left: Float, top: Float, right: Float, bottom: Float) {
        val x0 = left.toInt().coerceIn(0, width - 1)
        val y0 = top.toInt().coerceIn(0, height - 1)
        val x1 = right.toInt().coerceIn(0, width - 1)
        val y1 = bottom.toInt().coerceIn(0, height - 1)
        if (x1 < x0 || y1 < y0) return

        val tx0 = (x0 / tileSize).coerceIn(0, cols - 1)
        val tx1 = (x1 / tileSize).coerceIn(0, cols - 1)
        val ty0 = (y0 / tileSize).coerceIn(0, rows - 1)
        val ty1 = (y1 / tileSize).coerceIn(0, rows - 1)

        for (ty in ty0..ty1) {
            val rowOff = ty * cols
            for (tx in tx0..tx1) {
                val idx = rowOff + tx
                if (captured.containsKey(idx)) continue
                val tl = tx * tileSize
                val tt = ty * tileSize
                val tw = min(tileSize, width - tl).coerceAtLeast(1)
                val th = min(tileSize, height - tt).coerceAtLeast(1)
                val patchBmp = Bitmap.createBitmap(layerBitmap, tl, tt, tw, th)
                captured[idx] = TilePatch(tx, ty, tl, tt, tw, th, patchBmp)
            }
        }
    }

    fun restoreToLayer(targetBitmap: Bitmap): List<Int> {
        val c = Canvas(targetBitmap)
        val p = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        val indices = ArrayList<Int>(captured.size)
        for ((idx, patch) in captured) {
            c.drawBitmap(patch.bitmap, patch.left.toFloat(), patch.top.toFloat(), p)
            indices.add(idx)
        }
        return indices
    }

    fun snapshotPatches(): List<TilePatch> = captured.values.toList()

    fun getCapturedPatch(tx: Int, ty: Int): TilePatch? = captured[ty * cols + tx]

    fun recycleAll() {
        captured.values.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        captured.clear()
    }
}

private data class LayerSnapshot(
    val meta: LayerState,
    val pixelsCopy: Bitmap,
)

class CanvasViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ArtworkRepository(app)

    var screen by mutableStateOf(Screen.GALLERY)
        private set

    var loadingArtwork by mutableStateOf(false)
        private set

    // Gallery & Presets
    var artworks by mutableStateOf<List<ArtworkRecord>>(emptyList())
        private set
    var openStackId by mutableStateOf<String?>(null)
        private set
    var presets by mutableStateOf<List<CanvasPreset>>(emptyList())
        private set
    var customCanvasOpen by mutableStateOf(false)
    var editingPresetIndex by mutableStateOf<Int?>(null)

    var gallerySearch by mutableStateOf("")
    var gallerySort by mutableStateOf(GallerySortMode.RECENT)

    val galleryItems: List<GalleryItem>
        get() {
            val query = gallerySearch.trim().lowercase()
            val stackOpen = openStackId
            if (stackOpen != null) {
                val filtered = artworks.filter {
                    it.stackId == stackOpen && (query.isEmpty() || it.title.lowercase().contains(query))
                }
                val sorted = when (gallerySort) {
                    GallerySortMode.RECENT -> filtered.sortedByDescending { it.updatedAtMs }
                    GallerySortMode.NAME -> filtered.sortedBy { it.title.lowercase() }
                }
                return sorted.map { GalleryItem.SingleArtwork(it) }
            }
            val result = mutableListOf<GalleryItem>()
            val stacks = artworks.filter { it.stackId != null }.groupBy { it.stackId!! }
            val seenStacks = mutableSetOf<String>()
            artworks.forEach { art ->
                val sid = art.stackId
                if (sid == null) {
                    if (query.isEmpty() || art.title.lowercase().contains(query)) {
                        result.add(GalleryItem.SingleArtwork(art))
                    }
                } else if (seenStacks.add(sid)) {
                    val items = stacks[sid].orEmpty()
                    val name = items.firstOrNull { !it.stackName.isNullOrBlank() }?.stackName ?: "Stack"
                    if (query.isEmpty() || name.lowercase().contains(query) || items.any { it.title.lowercase().contains(query) }) {
                        result.add(GalleryItem.StackGroup(sid, name, items))
                    }
                }
            }
            return when (gallerySort) {
                GallerySortMode.RECENT -> result.sortedByDescending { it.updatedAtMs }
                GallerySortMode.NAME -> result.sortedBy { it.title.lowercase() }
            }
        }

    // Active Canvas Document
    var currentArtwork by mutableStateOf<ArtworkRecord?>(null)
        private set
    var layers by mutableStateOf<List<LayerState>>(emptyList())
        private set
    var activeLayerIndex by mutableIntStateOf(0)
        private set
    var backgroundRgb by mutableIntStateOf(Color.WHITE)
        private set
    var backgroundVisible by mutableStateOf(true)
        private set
    var compositeBitmap by mutableStateOf<Bitmap?>(null)
        private set
    var tileCache by mutableStateOf<CanvasTileCache?>(null)
        private set
    private var compositeDirty = true
    var compositeTick by mutableIntStateOf(0)
        private set

    // Viewport
    var canvasAreaSize by mutableStateOf(Size(1080f, 1920f))
    var viewZoom by mutableFloatStateOf(1f)
    var viewPan by mutableStateOf(Offset.Zero)
    var viewRotationDeg by mutableFloatStateOf(0f)
    var fullscreen by mutableStateOf(false)
    var noticeText by mutableStateOf<String?>(null)
    private var noticeJob: Job? = null

    // History
    private val undoStack = ArrayDeque<HistorySnapshot>()
    private val redoStack = ArrayDeque<HistorySnapshot>()
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    // Tools & Brushes
    var tool by mutableStateOf(ToolKind.PAINT)
    var panel by mutableStateOf(Panel.NONE)
    var brushSets by mutableStateOf(BrushLibraryCatalog.defaultBrushSets())
    var paintBrush by mutableStateOf(brushSets[1].brushes[0]) // Studio Pen
    var smudgeBrush by mutableStateOf(brushSets[3].brushes[0])
    var eraseBrush by mutableStateOf(brushSets[4].brushes[2])
    var brushStudioTarget by mutableStateOf<BrushPreset?>(null)
    var sizeNotches by mutableStateOf(listOf(0.12f, 0.35f, 0.72f))

    val activeBrush: BrushPreset
        get() = when (tool) {
            ToolKind.PAINT -> paintBrush
            ToolKind.SMUDGE -> smudgeBrush
            ToolKind.ERASE -> eraseBrush
        }

    var brushSize: Float
        get() = activeBrush.settings.size
        set(v) {
            updateActiveBrushSettings { it.copy(size = v.coerceIn(0.02f, 1f)) }
        }

    var brushOpacity: Float
        get() = activeBrush.settings.opacity
        set(v) {
            updateActiveBrushSettings { it.copy(opacity = v.coerceIn(0.02f, 1f)) }
        }

    // Color
    var colorRgb by mutableIntStateOf(0xFF0A84FF.toInt())
        private set
    var prevColorRgb by mutableIntStateOf(0xFF141416.toInt())
        private set
    var hue by mutableFloatStateOf(210f)
    var saturation by mutableFloatStateOf(0.96f)
    var brightness by mutableFloatStateOf(1f)
    var colorHistory by mutableStateOf(listOf(0xFF0A84FF.toInt(), 0xFFFF6D42.toInt(), 0xFF141416.toInt(), 0xFFFFFFFF.toInt()))
    var palettes by mutableStateOf<List<ColorPalette>>(emptyList())
    var colorMode by mutableStateOf(ColorMode.DISC)
    var harmonyKind by mutableStateOf(HarmonyKind.COMPLEMENTARY)

    // Pick / Eyedropper & ColorDrop drag
    var pickNext by mutableStateOf(false)
    var pickLoupe by mutableStateOf<PickLoupeState?>(null)
    var colorDroppingPos by mutableStateOf<Offset?>(null)

    // Gesture & UI Preferences
    var gestureSettings by mutableStateOf(GestureSettings())
    var gestureControlsOpen by mutableStateOf(false)
    var canvasInfoOpen by mutableStateOf(false)
    var copyPasteMenuOpen by mutableStateOf(false)
    var animatedExportOpen by mutableStateOf(false)
    var transformNumericOpen by mutableStateOf(false)
    var shortcutsHelpOpen by mutableStateOf(false)
    var companionVisible by mutableStateOf(false)
    var quickMenuCenter by mutableStateOf<Offset?>(null)
    var heldLayerIds by mutableStateOf<Set<String>>(emptySet())

    // Adjustments
    var adjustingKind by mutableStateOf<AdjustmentKind?>(null)
    var adjustmentPaintMode by mutableStateOf(false)
    var adjustAmount by mutableFloatStateOf(0.5f)
    var adjustHsb by mutableStateOf(HsbParams())
    var adjustBalance by mutableStateOf(BalanceParams())
    var adjustCurves by mutableStateOf(listOf(0f, 0.25f, 0.5f, 0.75f, 1f))
    var adjustCurveChannel by mutableStateOf(CurveChannel.ALL)
    var adjustGradient by mutableStateOf(BrushLibraryCatalog.gradientPresets().first())
    var liquifyMode by mutableStateOf(LiquifyMode.PUSH)
    var cloneSource by mutableStateOf<Offset?>(null)
    private var adjustBackupBitmap: Bitmap? = null
    private var adjustFilteredBitmap: Bitmap? = null
    private var adjustBrushMask: ByteArray? = null

    // Selection & Transform
    var selecting by mutableStateOf(false)
    var selectionKind by mutableStateOf(SelectionKind.FREEHAND)
    var selectionSubtract by mutableStateOf(false)
    var selectionMask by mutableStateOf<SelectionMask?>(null)
    var selectionOverlay by mutableStateOf<Bitmap?>(null)
    var selectionVersion by mutableIntStateOf(0)
    var selectionAnchor by mutableStateOf<Offset?>(null)
    var anchorMoveMode by mutableStateOf(false)
    private var savedSelection: ByteArray? = null

    var transforming by mutableStateOf(false)
    var transformState by mutableStateOf(LayerTransformState())
    private var transformBackupBitmap: Bitmap? = null
    internal var transformStaticBaseBitmap: Bitmap? = null
    internal var transformSelectionScopedBitmap: Bitmap? = null
    internal val transformBackupRef: Bitmap? get() = transformBackupBitmap
    var motionKeyframes by mutableStateOf<List<MotionKeyframe>>(emptyList())
    var inBetweenDialogOpen by mutableStateOf(false)
    var selectionTweenCurve by mutableStateOf(TweenCurveKind.EASE_IN_OUT)
    var selectionCubicBezier by mutableStateOf(CubicBezierControl())
    var selectionCurveOverlayOpen by mutableStateOf(false)

    // Guides, Crop, Text, Animation, Reference, Timelapse
    var guideConfig by mutableStateOf(GuideConfig())
    var editingGuide by mutableStateOf(false)

    var cropping by mutableStateOf(false)
    var cropState by mutableStateOf<CropState?>(null)

    var textEditing by mutableStateOf(false)
    var textConfig by mutableStateOf(TextLayerConfig())

    var animationState by mutableStateOf(AnimationState())
    var timelineTracks by mutableStateOf<List<TimelineTrack>>(emptyList())
    var renderLayersCache by mutableStateOf<List<LayerState>>(emptyList())
        internal set
    var onionSkinCache by mutableStateOf<List<OnionSkinFrame>>(emptyList())
        internal set
    var importingMedia by mutableStateOf(false)
    var timelineClipHandlesId by mutableStateOf<String?>(null)
    var layerOrderPickerGroupId by mutableStateOf<String?>(null)
    var sideBarMoveMode by mutableStateOf(false)
    internal val audioEngine = TimelineAudioEngine()
    internal var animationPlayJob: Job? = null
    private var activeStrokeClipId: String? = null

    internal val repoRef: ArtworkRepository get() = repo

    internal fun setLayersDirect(newLayers: List<LayerState>) {
        layers = newLayers
    }

    internal fun setActiveLayerIndexDirect(idx: Int) {
        activeLayerIndex = idx
    }

    var referenceVisible by mutableStateOf(false)
    var referenceBitmap by mutableStateOf<Bitmap?>(null)

    var timelapseReplayOpen by mutableStateOf(false)
    var timelapseFrames by mutableStateOf<List<File>>(emptyList())

    private var clipboardBitmap: Bitmap? = null
    private var activeStroke: BrushStrokeSession? = null
    private var strokeTileRecorder: TileUndoRecorder? = null
    private val dirtyRectBuf = RectF()
    private var persistDebounceJob: Job? = null
    private var thumbJob: Job? = null
    private var sessionStartMs: Long = 0L
    private val strokeSound = StrokeSoundEngine(viewModelScope)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val loadedArt = repo.seedDemoArtworkIfEmpty()
            val loadedPresets = repo.loadPresets()
            val loadedPalettes = repo.loadPalettes()
            val loadedSettings = repo.loadGestureSettings()
            val loadedBrushSets = repo.loadBrushSets()
            withContext(Dispatchers.Main) {
                artworks = loadedArt
                presets = loadedPresets
                palettes = loadedPalettes
                gestureSettings = loadedSettings
                brushSets = loadedBrushSets
                paintBrush = loadedBrushSets.getOrNull(1)?.brushes?.firstOrNull() ?: loadedBrushSets.first().brushes.first()
                Pc.light = loadedSettings.lightInterface
                strokeSound.setEnabled(loadedSettings.strokeSoundEnabled)
            }
        }
    }

    override fun onCleared() {
        strokeSound.release()
        audioEngine.release()
        super.onCleared()
    }

    fun showNotice(msg: String) {
        noticeText = msg
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(2200)
            noticeText = null
        }
    }

    // --- Gallery Actions ---

    fun openStack(stackId: String?) {
        openStackId = stackId
    }

    fun newArtworkFromPreset(preset: CanvasPreset) {
        val w = preset.pixelWidth
        val h = preset.pixelHeight
        createArtwork(w, h, preset.dpi, preset.backgroundRgb, preset.colorProfile, preset.name)
    }

    fun createArtwork(
        width: Int,
        height: Int,
        dpi: Int = 300,
        bgRgb: Int = Color.WHITE,
        profile: String = "sRGB IEC61966-2.1",
        title: String = "Untitled Artwork",
    ) {
        val id = UUID.randomUUID().toString()
        val meta = ArtworkRecord(
            id = id,
            title = title,
            width = width.coerceIn(64, 4096),
            height = height.coerceIn(64, 4096),
            dpi = dpi,
            colorProfile = profile,
            backgroundRgb = bgRgb,
            backgroundVisible = true,
            stackId = openStackId,
            stackName = openStackId?.let { sid -> artworks.firstOrNull { it.stackId == sid }?.stackName },
            order = (artworks.minOfOrNull { it.order } ?: 0) - 1,
        )
        val firstLayer = LayerState(
            id = UUID.randomUUID().toString(),
            name = "Layer 1",
            bitmap = Bitmap.createBitmap(meta.width, meta.height, Bitmap.Config.ARGB_8888),
        )
        openLoadedDocument(meta, listOf(firstLayer))
        persistCurrentArtwork(captureTimeLapse = true)
    }

    fun openArtwork(art: ArtworkRecord) {
        if (loadingArtwork) return
        loadingArtwork = true
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = repo.loadArtworkLayers(art.id, art.width, art.height)
            val loadedTimeline = repo.loadTimeline(art.id, art.width, art.height)
            withContext(Dispatchers.Main) {
                loadingArtwork = false
                openLoadedDocument(art, loaded, loadedTimeline)
            }
        }
    }

    fun importPictureAsArtwork(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = repo.decodeImageFromUri(uri, maxDim = 2048) ?: return@launch
            val id = UUID.randomUUID().toString()
            val meta = ArtworkRecord(
                id = id,
                title = "Imported Photo",
                width = bmp.width,
                height = bmp.height,
                order = (artworks.minOfOrNull { it.order } ?: 0) - 1,
                stackId = openStackId,
            )
            val layer = LayerState(UUID.randomUUID().toString(), "Imported Image", bitmap = bmp)
            withContext(Dispatchers.Main) {
                openLoadedDocument(meta, listOf(layer))
                persistCurrentArtwork(captureTimeLapse = true)
            }
        }
    }

    fun insertPhotoAsLayer(uri: Uri) {
        val art = currentArtwork ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val decoded = repo.decodeImageFromUri(uri, maxDim = max(art.width, art.height)) ?: return@launch
            val fitted = Bitmap.createBitmap(art.width, art.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(fitted)
            val scale = min(art.width.toFloat() / decoded.width, art.height.toFloat() / decoded.height)
            val dw = decoded.width * scale
            val dh = decoded.height * scale
            val left = (art.width - dw) * 0.5f
            val top = (art.height - dh) * 0.5f
            canvas.drawBitmap(decoded, null, android.graphics.RectF(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG))
            decoded.recycle()
            withContext(Dispatchers.Main) {
                pushUndoSnapshot()
                val newLayer = LayerState(UUID.randomUUID().toString(), "Inserted Image", bitmap = fitted)
                val insertAt = (activeLayerIndex + 1).coerceIn(0, layers.size)
                layers = layers.toMutableList().apply { add(insertAt, newLayer) }
                activeLayerIndex = insertAt
                if (timelineTracks.isNotEmpty()) syncPaintTracksWithLayers()
                rebuildTimelineRenderState()
                recomposite()
                refreshLayerThumbnail(insertAt)
                persistCurrentArtwork()
                showNotice("Inserted image on Layer ${insertAt + 1}")
            }
        }
    }

    private fun openLoadedDocument(
        meta: ArtworkRecord,
        loadedLayers: List<LayerState>,
        loadedTimeline: LoadedTimelineData? = null,
    ) {
        clearTransients()
        clearHistory()
        currentArtwork = meta
        layers = loadedLayers
        activeLayerIndex = loadedLayers.lastIndex.coerceAtLeast(0)
        backgroundRgb = meta.backgroundRgb
        backgroundVisible = meta.backgroundVisible
        selectionMask = SelectionMask(meta.width, meta.height)
        tileCache?.recycle()
        tileCache = CanvasTileCache(meta.width, meta.height)
        compositeBitmap?.recycle()
        compositeBitmap = null
        compositeDirty = true
        sessionStartMs = System.currentTimeMillis()
        if (loadedTimeline != null && loadedTimeline.tracks.isNotEmpty()) {
            timelineTracks = loadedTimeline.tracks
            animationState = AnimationState(
                fps = loadedTimeline.fps,
                totalFrames = loadedTimeline.totalFrames,
                audioScrubEnabled = loadedTimeline.audioScrubEnabled,
                timelineSnapping = loadedTimeline.timelineSnapping,
            )
            refreshTimelineClipThumbnails()
        } else {
            timelineTracks = emptyList()
        }
        rebuildTimelineRenderState()
        recomposite()
        refreshLayerThumbnails()
        fitCanvasToScreen()
        screen = Screen.CANVAS
    }

    fun closeArtwork() {
        persistDebounceJob?.cancel()
        thumbJob?.cancel()
        audioEngine.stopPlayback()
        commitAnyPendingTool()
        persistCurrentArtwork(sync = true)
        clearTransients()
        clearHistory()
        tileCache?.recycle()
        tileCache = null
        recycleAllTimelineAndLayerBitmaps()
        timelineTracks = emptyList()
        renderLayersCache = emptyList()
        onionSkinCache = emptyList()
        layers = emptyList()
        compositeBitmap?.recycle()
        compositeBitmap = null
        currentArtwork = null
        screen = Screen.GALLERY
        refreshGallery()
    }

    fun refreshGallery() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = repo.listArtworks()
            withContext(Dispatchers.Main) { artworks = list }
        }
    }

    fun renameArtwork(art: ArtworkRecord, newTitle: String) {
        val trimmed = newTitle.trim().ifBlank { return }
        val updated = art.copy(title = trimmed)
        artworks = artworks.map { if (it.id == art.id) updated else it }
        viewModelScope.launch(Dispatchers.IO) { repo.saveMetaOnly(updated) }
    }

    fun renameStack(stackId: String, newName: String) {
        val trimmed = newName.trim().ifBlank { return }
        val updated = artworks.map { if (it.stackId == stackId) it.copy(stackName = trimmed) else it }
        artworks = updated
        viewModelScope.launch(Dispatchers.IO) {
            updated.filter { it.stackId == stackId }.forEach { repo.saveMetaOnly(it) }
        }
    }

    fun duplicateArtworks(ids: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val minOrder = (artworks.minOfOrNull { it.order } ?: 0) - 1
            ids.forEachIndexed { idx, id ->
                artworks.firstOrNull { it.id == id }?.let { repo.duplicateArtwork(it, minOrder - idx) }
            }
            val list = repo.listArtworks()
            withContext(Dispatchers.Main) { artworks = list }
        }
    }

    fun deleteArtworks(ids: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { repo.deleteArtwork(it) }
            val list = repo.listArtworks()
            withContext(Dispatchers.Main) { artworks = list }
        }
    }

    fun stackArtworks(ids: List<String>) {
        if (ids.size < 2) return
        val newStackId = UUID.randomUUID().toString()
        val updated = artworks.map { art ->
            if (art.id in ids) art.copy(stackId = newStackId, stackName = "Stack") else art
        }
        artworks = updated
        viewModelScope.launch(Dispatchers.IO) {
            updated.filter { it.id in ids }.forEach { repo.saveMetaOnly(it) }
        }
    }

    fun removeArtworkFromStack(art: ArtworkRecord) {
        val updated = art.copy(stackId = null, stackName = null)
        artworks = artworks.map { if (it.id == art.id) updated else it }
        viewModelScope.launch(Dispatchers.IO) { repo.saveMetaOnly(updated) }
        if (openStackId != null && artworks.none { it.stackId == openStackId }) {
            openStackId = null
        }
    }

    fun shareArtworkFromGallery(art: ArtworkRecord, format: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ls = repo.loadArtworkLayers(art.id, art.width, art.height)
            val comp = LayerCompositor.composite(art.width, art.height, ls, art.backgroundRgb, art.backgroundVisible, AnimationState())
            repo.exportAndShare(art.title, format, listOf(comp))
            comp.recycle()
            ls.forEach { it.bitmap.recycle() }
        }
    }

    fun shareCurrentArtwork(format: String) {
        val art = currentArtwork ?: return
        val comp = ensureCompositeBitmap()?.copy(Bitmap.Config.ARGB_8888, false) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repo.exportAndShare(art.title, format, listOf(comp))
            comp.recycle()
        }
    }

    fun saveCustomPreset(preset: CanvasPreset, editIndex: Int? = null) {
        val updated = presets.toMutableList()
        if (editIndex != null && editIndex in updated.indices) {
            updated[editIndex] = preset
        } else {
            updated.add(0, preset)
        }
        presets = updated
        viewModelScope.launch(Dispatchers.IO) { repo.savePresets(updated) }
    }

    fun deletePreset(index: Int) {
        if (index !in presets.indices) return
        val updated = presets.toMutableList().apply { removeAt(index) }
        presets = updated
        viewModelScope.launch(Dispatchers.IO) { repo.savePresets(updated) }
    }

    // --- Modes & Panels ---

    fun togglePanel(target: Panel) {
        if (panel == target) {
            panel = Panel.NONE
            return
        }
        if (adjustingKind != null) cancelAdjustment()
        if (transforming) commitTransform()
        selecting = false
        panel = target
    }

    fun toolPressed(kind: ToolKind) {
        if (adjustingKind != null && kind == ToolKind.PAINT) {
            // Paint remains active for Liquify/Clone or mask brush
            panel = Panel.NONE
            return
        }
        if (transforming) commitTransform()
        selecting = false
        if (tool == kind && panel == Panel.NONE && !fullscreen) {
            panel = Panel.BRUSHES
        } else {
            tool = kind
            panel = Panel.NONE
        }
    }

    fun toggleSelecting() {
        if (transforming) commitTransform()
        if (adjustingKind != null) cancelAdjustment()
        panel = Panel.NONE
        val next = !selecting
        selecting = next
        if (next) {
            if (selectionAnchor == null) {
                recomputeDefaultSelectionAnchor()
            }
        } else {
            anchorMoveMode = false
        }
    }

    fun toggleTransform() {
        if (adjustingKind != null) cancelAdjustment()
        selecting = false
        anchorMoveMode = false
        panel = Panel.NONE
        if (transforming) {
            commitTransform()
        } else {
            startSelectionOrLayerTransformSession()
        }
    }

    fun fitCanvasToScreen() {
        val art = currentArtwork ?: return
        val aw = canvasAreaSize.width.coerceAtLeast(300f)
        val ah = canvasAreaSize.height.coerceAtLeast(300f)
        val marginFactor = 0.88f
        val z = min((aw * marginFactor) / art.width, (ah * marginFactor) / art.height).coerceIn(0.08f, 8f)
        viewZoom = z
        viewPan = Offset.Zero
        viewRotationDeg = 0f
    }

    // --- Color ---

    fun setColor(rgb: Int, recordHistory: Boolean = false) {
        val opaque = rgb or 0xFF000000.toInt()
        if (opaque != colorRgb && recordHistory) {
            prevColorRgb = colorRgb
            colorHistory = (listOf(opaque) + colorHistory.filter { it != opaque }).take(14)
        }
        colorRgb = opaque
        val hsv = CanvasSpace.hsvFromArgb(opaque)
        hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
    }

    fun setHsv(h: Float, s: Float, v: Float, recordHistory: Boolean = false) {
        hue = ((h % 360f) + 360f) % 360f
        saturation = s.coerceIn(0f, 1f)
        brightness = v.coerceIn(0f, 1f)
        val rgb = CanvasSpace.argbFromHsv(hue, saturation, brightness)
        if (rgb != colorRgb && recordHistory) {
            prevColorRgb = colorRgb
            colorHistory = (listOf(rgb) + colorHistory.filter { it != rgb }).take(14)
        }
        colorRgb = rgb
    }

    fun swapPreviousColor() {
        val old = colorRgb
        setColor(prevColorRgb, recordHistory = false)
        prevColorRgb = old
        showNotice("Swapped previous color")
    }

    fun addColorToPalette(paletteId: String, rgb: Int) {
        val updated = palettes.map { p ->
            if (p.id != paletteId) p
            else p.copy(swatches = (p.swatches.filterNotNull() + rgb).take(30))
        }
        palettes = updated
        viewModelScope.launch(Dispatchers.IO) { repo.savePalettes(updated) }
    }

    fun createPalette(name: String) {
        val pal = ColorPalette(UUID.randomUUID().toString(), name.ifBlank { "Untitled Palette" }, listOf(colorRgb))
        val updated = listOf(pal) + palettes
        palettes = updated
        viewModelScope.launch(Dispatchers.IO) { repo.savePalettes(updated) }
    }

    fun setDefaultPalette(id: String) {
        val updated = palettes.map { it.copy(isDefault = it.id == id) }
        palettes = updated
        viewModelScope.launch(Dispatchers.IO) { repo.savePalettes(updated) }
    }

    fun deletePalette(id: String) {
        if (palettes.size <= 1) return
        val updated = palettes.filter { it.id != id }
        palettes = updated
        viewModelScope.launch(Dispatchers.IO) { repo.savePalettes(updated) }
    }

    // --- Brushes ---

    fun selectBrush(brush: BrushPreset) {
        when (tool) {
            ToolKind.PAINT -> paintBrush = brush
            ToolKind.SMUDGE -> smudgeBrush = brush
            ToolKind.ERASE -> eraseBrush = brush
        }
    }

    fun updateActiveBrushSettings(transform: (BrushSettings) -> BrushSettings) {
        val cur = activeBrush
        val next = cur.copy(settings = transform(cur.settings))
        selectBrush(next)
        brushSets = brushSets.map { set ->
            set.copy(brushes = set.brushes.map { if (it.id == next.id) next else it })
        }
        if (brushStudioTarget?.id == next.id) brushStudioTarget = next
        saveBrushSetsAsync()
    }

    fun updateBrushPreset(preset: BrushPreset) {
        brushSets = brushSets.map { set ->
            set.copy(brushes = set.brushes.map { if (it.id == preset.id) preset else it })
        }
        if (activeBrush.id == preset.id) selectBrush(preset)
        brushStudioTarget = preset
        saveBrushSetsAsync()
    }

    fun createBrushSet(name: String) {
        val clean = name.trim().ifBlank { return }
        val newSetId = UUID.randomUUID().toString()
        val seedBrush = activeBrush.copy(
            id = UUID.randomUUID().toString(),
            setId = newSetId,
            name = "Custom Brush",
            isCustom = true,
        )
        brushSets = listOf(BrushSet(newSetId, clean, listOf(seedBrush), isCustom = true)) + brushSets
        selectBrush(seedBrush)
        saveBrushSetsAsync()
    }

    fun renameBrushSet(setId: String, name: String) {
        val clean = name.trim().ifBlank { return }
        brushSets = brushSets.map { if (it.id == setId) it.copy(name = clean) else it }
        saveBrushSetsAsync()
    }

    fun deleteBrushSet(setId: String) {
        if (brushSets.size <= 1) return
        val target = brushSets.firstOrNull { it.id == setId } ?: return
        if (!target.isCustom) return
        brushSets = brushSets.filter { it.id != setId }
        saveBrushSetsAsync()
    }

    fun createCustomBrushInSet(setId: String): BrushPreset {
        val custom = activeBrush.copy(
            id = UUID.randomUUID().toString(),
            setId = setId,
            name = "${activeBrush.name} Custom",
            isCustom = true,
        )
        brushSets = brushSets.map { s ->
            if (s.id == setId) s.copy(brushes = listOf(custom) + s.brushes) else s
        }
        selectBrush(custom)
        brushStudioTarget = custom
        saveBrushSetsAsync()
        return custom
    }

    fun deleteCustomBrush(brushId: String) {
        brushSets = brushSets.map { s ->
            val target = s.brushes.firstOrNull { it.id == brushId }
            if (target != null && target.isCustom && s.brushes.size > 1) {
                s.copy(brushes = s.brushes.filter { it.id != brushId })
            } else s
        }
        saveBrushSetsAsync()
    }

    fun importAbrBrushes(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val cr = app.contentResolver
            var displayName = "Imported ABR"
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) displayName = c.getString(0) ?: displayName
            }
            val baseName = displayName.substringBeforeLast('.').ifBlank { "Imported ABR" }
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                withContext(Dispatchers.Main) { showNotice("Failed to read .abr file") }
                return@launch
            }
            val setId = UUID.randomUUID().toString()
            val parsed = AbrBrushParser.parseAbr(
                bytes = bytes,
                setId = setId,
                tipsOutputDir = repo.brushTipsDir(),
                fallbackBaseName = baseName,
            )
            withContext(Dispatchers.Main) {
                if (parsed.isEmpty()) {
                    showNotice("No compatible brushes found in .abr")
                } else {
                    val newSet = BrushSet(id = setId, name = baseName.take(28), brushes = parsed, isCustom = true)
                    brushSets = listOf(newSet) + brushSets
                    selectBrush(parsed.first())
                    if (brushStudioTarget != null) {
                        brushStudioTarget = parsed.first()
                    }
                    saveBrushSetsAsync()
                    showNotice("Imported ${parsed.size} brush(es) from $baseName")
                }
            }
        }
    }

    private fun saveBrushSetsAsync() {
        val snapshot = brushSets
        viewModelScope.launch(Dispatchers.IO) { repo.saveBrushSets(snapshot) }
    }

    // --- Drawing / Stroke Pipeline ---

    fun activeLayer(): LayerState? = layers.getOrNull(activeLayerIndex)

    fun startStroke(canvasPos: Offset, pressure: Float, tilt: Float, fakePressureActive: Boolean = false) {
        val art = currentArtwork ?: return
        if (animationState.isAnimation) {
            ensurePaintKeyframeAtCurrentFrame()
        }
        val layer = activeLayer() ?: return
        if (layer.locked) {
            showNotice("Active layer is locked")
            return
        }
        persistDebounceJob?.cancel()
        activeStrokeClipId = if (animationState.isAnimation) {
            timelineTracks.firstOrNull { it.kind == TrackKind.PAINT && it.layerId == layer.id }
                ?.clipAt(animationState.currentFrameIndex)?.id
        } else null
        if (adjustingKind == AdjustmentKind.LIQUIFY) {
            if (adjustBackupBitmap == null) adjustBackupBitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            return
        }
        if (adjustingKind == AdjustmentKind.CLONE) {
            if (cloneSource == null) {
                cloneSource = canvasPos
                showNotice("Clone source set")
            }
            return
        }
        if (adjustingKind != null && adjustmentPaintMode) {
            paintAdjustmentDab(canvasPos, pressure)
            return
        }
        strokeTileRecorder?.recycleAll()
        val recorder = TileUndoRecorder(layer.bitmap, art.width, art.height)
        strokeTileRecorder = recorder
        val mask = selectionMask?.let { m -> if (m.isEmpty()) null else m.alpha }
        val session = BrushStrokeSession(
            tool = tool,
            brush = activeBrush.settings,
            colorRgb = colorRgb,
            canvasWidth = art.width,
            canvasHeight = art.height,
            alphaLock = layer.alphaLock,
            guide = guideConfig,
            selectionMask = mask,
            targetBitmap = layer.bitmap,
            baseLayerSnapshot = null,
            fakePressureActive = fakePressureActive,
            onBeforeModifyRect = { l, t, r, b -> recorder.captureRect(l, t, r, b) },
        )
        session.start(StrokePoint(canvasPos.x, canvasPos.y, pressure, tilt))
        activeStroke = session
        if (session.consumeDirtyRect(dirtyRectBuf)) {
            if (mask != null) enforceSelectionOnRect(layer.bitmap, dirtyRectBuf, recorder, mask, art.width, art.height)
            recompositeRect(dirtyRectBuf.left, dirtyRectBuf.top, dirtyRectBuf.right, dirtyRectBuf.bottom)
        }
    }

    fun moveStroke(canvasPos: Offset, pressure: Float, tilt: Float, prevCanvasPos: Offset) {
        moveStrokeBatch(listOf(StrokePoint(canvasPos.x, canvasPos.y, pressure, tilt)), prevCanvasPos)
    }

    fun moveStrokeBatch(points: List<StrokePoint>, prevCanvasPos: Offset) {
        if (points.isEmpty()) return
        val art = currentArtwork ?: return
        val layer = activeLayer() ?: return
        val last = points.last()
        if (adjustingKind == AdjustmentKind.LIQUIFY) {
            val radius = 24f + brushSize * 120f
            ImageAdjustments.applyLiquifyDab(
                layer.bitmap,
                liquifyMode,
                last.x,
                last.y,
                last.x - prevCanvasPos.x,
                last.y - prevCanvasPos.y,
                radius,
                last.pressure,
            )
            recompositeRect(last.x - radius - 4f, last.y - radius - 4f, last.x + radius + 4f, last.y + radius + 4f)
            return
        }
        if (adjustingKind == AdjustmentKind.CLONE) {
            val src = cloneSource ?: return
            val srcX = (src.x + (last.x - prevCanvasPos.x)).toInt().coerceIn(0, layer.bitmap.width - 1)
            val srcY = (src.y + (last.y - prevCanvasPos.y)).toInt().coerceIn(0, layer.bitmap.height - 1)
            val sampled = layer.bitmap.getPixel(srcX, srcY)
            val r = 12f + brushSize * 48f
            val c = Canvas(layer.bitmap)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sampled; alpha = (brushOpacity * 200f).toInt() }
            c.drawCircle(last.x, last.y, r, p)
            recompositeRect(last.x - r - 4f, last.y - r - 4f, last.x + r + 4f, last.y + r + 4f)
            return
        }
        if (adjustingKind != null && adjustmentPaintMode) {
            for (pt in points) {
                paintAdjustmentDab(Offset(pt.x, pt.y), pt.pressure)
            }
            return
        }
        val session = activeStroke ?: return
        val speed = hypot(last.x - prevCanvasPos.x, last.y - prevCanvasPos.y)
        strokeSound.onStrokeMove(speed, last.pressure)
        for (i in points.indices) {
            session.move(points[i])
        }
        if (session.consumeDirtyRect(dirtyRectBuf)) {
            val mask = session.selectionMask
            val recorder = strokeTileRecorder
            if (mask != null && recorder != null) {
                enforceSelectionOnRect(layer.bitmap, dirtyRectBuf, recorder, mask, art.width, art.height)
            }
            recompositeRect(dirtyRectBuf.left, dirtyRectBuf.top, dirtyRectBuf.right, dirtyRectBuf.bottom)
        }
    }

    private fun enforceSelectionOnRect(
        bmp: Bitmap,
        rect: RectF,
        recorder: TileUndoRecorder,
        mask: ByteArray,
        width: Int,
        height: Int,
    ) {
        val x0 = rect.left.toInt().coerceIn(0, width - 1)
        val y0 = rect.top.toInt().coerceIn(0, height - 1)
        val x1 = rect.right.toInt().coerceIn(0, width - 1)
        val y1 = rect.bottom.toInt().coerceIn(0, height - 1)
        val rw = x1 - x0 + 1
        val rh = y1 - y0 + 1
        if (rw <= 0 || rh <= 0) return

        val drawn = IntArray(rw * rh)
        bmp.getPixels(drawn, 0, rw, x0, y0, rw, rh)
        val tileSize = CanvasTileCache.TILE_SIZE
        for (ry in 0 until rh) {
            val cy = y0 + ry
            val ty = cy / tileSize
            val rowOff = cy * width
            val localRow = ry * rw
            for (rx in 0 until rw) {
                val cx = x0 + rx
                val sel = mask[rowOff + cx].toInt() and 0xFF
                if (sel >= 250) continue
                val tx = cx / tileSize
                val patch = recorder.getCapturedPatch(tx, ty) ?: continue
                val orig = patch.bitmap.getPixel(cx - patch.left, cy - patch.top)
                if (sel <= 0) {
                    drawn[localRow + rx] = orig
                } else {
                    val drawnPx = drawn[localRow + rx]
                    val k = sel / 255f
                    val inv = 1f - k
                    val a = (Color.alpha(orig) * inv + Color.alpha(drawnPx) * k).roundToInt()
                    val r = (Color.red(orig) * inv + Color.red(drawnPx) * k).roundToInt()
                    val g = (Color.green(orig) * inv + Color.green(drawnPx) * k).roundToInt()
                    val b = (Color.blue(orig) * inv + Color.blue(drawnPx) * k).roundToInt()
                    drawn[localRow + rx] = Color.argb(a, r, g, b)
                }
            }
        }
        bmp.setPixels(drawn, 0, rw, x0, y0, rw, rh)
    }

    fun triggerQuickShapeIfMatched() {
        val session = activeStroke ?: return
        val layer = activeLayer() ?: return
        val recorder = strokeTileRecorder
        val recognized = session.tryQuickShape() ?: return
        var restoredIndices: List<Int> = emptyList()
        session.redrawQuickShape(recognized) {
            if (recorder != null) {
                restoredIndices = recorder.restoreToLayer(layer.bitmap)
            }
        }
        if (restoredIndices.isNotEmpty()) {
            tileCache?.recompositeTiles(restoredIndices, layers, backgroundRgb, backgroundVisible, animationState)
        }
        if (session.consumeDirtyRect(dirtyRectBuf)) {
            recompositeRect(dirtyRectBuf.left, dirtyRectBuf.top, dirtyRectBuf.right, dirtyRectBuf.bottom)
        }
        showNotice("${recognized.kind} Created")
    }

    fun endStroke() {
        strokeSound.onStrokeEnd()
        if (adjustingKind == AdjustmentKind.LIQUIFY || adjustingKind == AdjustmentKind.CLONE || (adjustingKind != null && adjustmentPaintMode)) {
            recomposite()
            return
        }
        val session = activeStroke ?: return
        val art = currentArtwork
        val layer = activeLayer()
        if (session.consumeDirtyRect(dirtyRectBuf) && layer != null) {
            val mask = session.selectionMask
            val recorder = strokeTileRecorder
            if (mask != null && recorder != null && art != null) {
                enforceSelectionOnRect(layer.bitmap, dirtyRectBuf, recorder, mask, art.width, art.height)
            }
            recompositeRect(dirtyRectBuf.left, dirtyRectBuf.top, dirtyRectBuf.right, dirtyRectBuf.bottom)
        }
        session.end()
        activeStroke = null
        val recorder = strokeTileRecorder
        strokeTileRecorder = null
        val clipIdForUndo = activeStrokeClipId
        activeStrokeClipId = null
        if (recorder != null && layer != null && art != null) {
            val patches = recorder.snapshotPatches()
            if (patches.isNotEmpty()) {
                pushTilePatchUndo(
                    TilePatchSnapshot(
                        layerId = layer.id,
                        layerIndex = activeLayerIndex,
                        clipId = clipIdForUndo,
                        width = art.width,
                        height = art.height,
                        patches = patches,
                    ),
                )
            }
        }
        currentArtwork = currentArtwork?.let {
            it.copy(totalStrokes = it.totalStrokes + 1, updatedAtMs = System.currentTimeMillis())
        }
        setColor(colorRgb, recordHistory = true)
        refreshLayerThumbnailAsync(activeLayerIndex)
        if (animationState.isAnimation && clipIdForUndo != null) {
            refreshSingleClipThumbnailAsync(clipIdForUndo)
        }
        schedulePersistAfterStroke()
    }

    fun colorDropAt(canvasPos: Offset) {
        val art = currentArtwork ?: return
        val layer = activeLayer() ?: return
        if (layer.locked) {
            showNotice("Layer is locked")
            return
        }
        val x = canvasPos.x.roundToInt()
        val y = canvasPos.y.roundToInt()
        if (x !in 0 until art.width || y !in 0 until art.height) {
            showNotice("Dropped outside canvas")
            return
        }
        pushUndoSnapshot()
        val refLayer = layers.firstOrNull { it.isReference } ?: layer
        LayerCompositor.colorDrop(
            target = layer.bitmap,
            reference = refLayer.bitmap,
            startX = x,
            startY = y,
            fillRgb = colorRgb,
            threshold = gestureSettings.colorDropThreshold,
            alphaLock = layer.alphaLock,
        )
        recomposite()
        refreshLayerThumbnail(activeLayerIndex)
        setColor(colorRgb, recordHistory = true)
        persistCurrentArtwork(captureTimeLapse = true)
        showNotice("ColorDrop filled")
    }

    fun sampleColorAt(canvasPos: Offset): Int? {
        val art = currentArtwork ?: return null
        val x = canvasPos.x.roundToInt().coerceIn(0, art.width - 1)
        val y = canvasPos.y.roundToInt().coerceIn(0, art.height - 1)
        val sampled = tileCache?.samplePixel(x, y)
            ?: ensureCompositeBitmap()?.getPixel(x, y)
            ?: return null
        return sampled or 0xFF000000.toInt()
    }

    // --- Undo / Redo ---

    private fun clearHistory() {
        undoStack.forEach { it.recycle() }
        undoStack.clear()
        redoStack.forEach { it.recycle() }
        redoStack.clear()
    }

    private fun pushHistoryEntry(snap: HistorySnapshot) {
        undoStack.addLast(snap)
        while (undoStack.size > 18) {
            undoStack.removeFirst().recycle()
        }
        redoStack.forEach { it.recycle() }
        redoStack.clear()
    }

    private fun pushTilePatchUndo(snap: TilePatchSnapshot) {
        pushHistoryEntry(snap)
    }

    fun pushUndoSnapshot() {
        val art = currentArtwork
        pushHistoryEntry(
            FullSnapshot(
                layers = layers.map { l ->
                    val copy = if (!l.bitmap.isRecycled) {
                        l.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    } else {
                        Bitmap.createBitmap((art?.width ?: 512).coerceAtLeast(1), (art?.height ?: 512).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    }
                    LayerSnapshot(l, copy)
                },
                activeLayerIndex = activeLayerIndex,
                backgroundRgb = backgroundRgb,
                backgroundVisible = backgroundVisible,
            ),
        )
    }

    private fun pushBitmapBackupUndo(layerIdx: Int, beforeBmp: Bitmap) {
        val art = currentArtwork
        pushHistoryEntry(
            FullSnapshot(
                layers = layers.mapIndexed { idx, l ->
                    val bmp = when {
                        idx == layerIdx && !beforeBmp.isRecycled -> beforeBmp
                        !l.bitmap.isRecycled -> l.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        else -> Bitmap.createBitmap((art?.width ?: 512).coerceAtLeast(1), (art?.height ?: 512).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    }
                    LayerSnapshot(l, bmp)
                },
                activeLayerIndex = activeLayerIndex,
                backgroundRgb = backgroundRgb,
                backgroundVisible = backgroundVisible,
            ),
        )
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        val art = currentArtwork
        when (prev) {
            is FullSnapshot -> {
                val curr = FullSnapshot(
                    layers = layers.map { l ->
                        val copy = if (!l.bitmap.isRecycled) {
                            l.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        } else {
                            Bitmap.createBitmap((art?.width ?: 512).coerceAtLeast(1), (art?.height ?: 512).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        }
                        LayerSnapshot(l, copy)
                    },
                    activeLayerIndex = activeLayerIndex,
                    backgroundRgb = backgroundRgb,
                    backgroundVisible = backgroundVisible,
                )
                redoStack.addLast(curr)
                restoreFullSnapshot(prev)
            }
            is TilePatchSnapshot -> {
                val curr = applyTilePatchesAndCaptureInverse(prev)
                if (curr != null) redoStack.addLast(curr)
            }
        }
        showNotice("Undo")
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        val art = currentArtwork
        when (next) {
            is FullSnapshot -> {
                val curr = FullSnapshot(
                    layers = layers.map { l ->
                        val copy = if (!l.bitmap.isRecycled) {
                            l.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        } else {
                            Bitmap.createBitmap((art?.width ?: 512).coerceAtLeast(1), (art?.height ?: 512).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        }
                        LayerSnapshot(l, copy)
                    },
                    activeLayerIndex = activeLayerIndex,
                    backgroundRgb = backgroundRgb,
                    backgroundVisible = backgroundVisible,
                )
                undoStack.addLast(curr)
                restoreFullSnapshot(next)
            }
            is TilePatchSnapshot -> {
                val curr = applyTilePatchesAndCaptureInverse(next)
                if (curr != null) undoStack.addLast(curr)
            }
        }
        showNotice("Redo")
    }

    private fun applyTilePatchesAndCaptureInverse(snap: TilePatchSnapshot): TilePatchSnapshot? {
        val clipTarget = snap.clipId?.let { cid -> findClipBitmapById(cid) }
        val idx = layers.indexOfFirst { it.id == snap.layerId }.takeIf { it >= 0 }
            ?: snap.layerIndex.takeIf { it in layers.indices }
        val targetBitmap = clipTarget ?: idx?.let { layers[it].bitmap } ?: run {
            snap.recycle()
            return null
        }
        val inversePatches = ArrayList<TilePatch>(snap.patches.size)
        val c = Canvas(targetBitmap)
        val srcPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        val cols = tileCache?.cols ?: ((snap.width + CanvasTileCache.TILE_SIZE - 1) / CanvasTileCache.TILE_SIZE)
        val dirtyTileIndices = ArrayList<Int>(snap.patches.size)

        for (patch in snap.patches) {
            val curBmp = Bitmap.createBitmap(targetBitmap, patch.left, patch.top, patch.w, patch.h)
            inversePatches.add(TilePatch(patch.tx, patch.ty, patch.left, patch.top, patch.w, patch.h, curBmp))
            c.drawBitmap(patch.bitmap, patch.left.toFloat(), patch.top.toFloat(), srcPaint)
            dirtyTileIndices.add(patch.ty * cols + patch.tx)
        }
        snap.recycle()
        val effective = if (animationState.isAnimation) renderLayersCache else layers
        tileCache?.recompositeTiles(dirtyTileIndices, effective, backgroundRgb, backgroundVisible, animationState, onionSkinCache)
        compositeDirty = true
        compositeTick++
        if (idx != null) refreshLayerThumbnailAsync(idx)
        snap.clipId?.let { refreshSingleClipThumbnailAsync(it) }
        schedulePersistAfterStroke()
        return TilePatchSnapshot(snap.layerId, idx ?: snap.layerIndex, snap.clipId, snap.width, snap.height, inversePatches)
    }

    private fun restoreFullSnapshot(snap: FullSnapshot) {
        layers.forEach { if (!isBitmapOwnedByTimeline(it.bitmap) && !it.bitmap.isRecycled) it.bitmap.recycle() }
        layers = snap.layers.map { ls ->
            ls.meta.copy(bitmap = ls.pixelsCopy.copy(Bitmap.Config.ARGB_8888, true))
        }
        snap.recycle()
        activeLayerIndex = snap.activeLayerIndex.coerceIn(0, layers.lastIndex.coerceAtLeast(0))
        backgroundRgb = snap.backgroundRgb
        backgroundVisible = snap.backgroundVisible
        syncPaintTracksWithLayers()
        rebuildTimelineRenderState()
        recomposite()
        refreshLayerThumbnails()
        persistCurrentArtwork()
    }

    // --- Layer Operations ---

    fun selectLayer(index: Int) {
        if (index in layers.indices) {
            activeLayerIndex = index
            if (animationState.isPageAssist) {
                animationState = animationState.copy(currentFrameIndex = index)
                recomposite()
            } else if (animationState.isAnimation) {
                val trackId = timelineTracks.firstOrNull { it.kind == TrackKind.PAINT && it.layerId == layers[index].id }?.id
                if (trackId != null) {
                    animationState = animationState.copy(selectedTrackId = trackId)
                    rebuildTimelineRenderState()
                    recomposite()
                }
            }
        }
    }

    fun addLayer() {
        val art = currentArtwork ?: return
        pushUndoSnapshot()
        val newLayer = LayerState(
            id = UUID.randomUUID().toString(),
            name = "Layer ${layers.size + 1}",
            bitmap = Bitmap.createBitmap(art.width, art.height, Bitmap.Config.ARGB_8888),
        )
        val insertAt = (activeLayerIndex + 1).coerceIn(0, layers.size)
        layers = layers.toMutableList().apply { add(insertAt, newLayer) }
        activeLayerIndex = insertAt
        if (animationState.isPageAssist) animationState = animationState.copy(currentFrameIndex = insertAt)
        if (timelineTracks.isNotEmpty()) syncPaintTracksWithLayers()
        rebuildTimelineRenderState()
        recomposite()
        refreshLayerThumbnail(insertAt)
        persistCurrentArtwork()
    }

    fun duplicateLayer(index: Int) {
        val target = layers.getOrNull(index) ?: return
        pushUndoSnapshot()
        val copy = target.copy(
            id = UUID.randomUUID().toString(),
            name = "${target.name} Copy",
            bitmap = target.bitmap.copy(Bitmap.Config.ARGB_8888, true),
        )
        layers = layers.toMutableList().apply { add(index + 1, copy) }
        activeLayerIndex = index + 1
        if (timelineTracks.isNotEmpty()) syncPaintTracksWithLayers()
        rebuildTimelineRenderState()
        recomposite()
        refreshLayerThumbnail(activeLayerIndex)
        persistCurrentArtwork()
    }

    fun deleteLayer(index: Int) {
        if (layers.size <= 1) {
            clearLayer(index)
            return
        }
        val target = layers.getOrNull(index) ?: return
        pushUndoSnapshot()
        if (!isBitmapOwnedByTimeline(target.bitmap) && !target.bitmap.isRecycled) target.bitmap.recycle()
        layers = layers.toMutableList().apply { removeAt(index) }
        activeLayerIndex = activeLayerIndex.coerceIn(0, layers.lastIndex)
        if (timelineTracks.isNotEmpty()) syncPaintTracksWithLayers()
        rebuildTimelineRenderState()
        recomposite()
        persistCurrentArtwork()
    }

    fun moveLayer(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in layers.indices || toIndex !in layers.indices || fromIndex == toIndex) return
        pushUndoSnapshot()
        val mutable = layers.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        layers = mutable
        activeLayerIndex = toIndex
        rebuildTimelineRenderState()
        recomposite()
        persistCurrentArtwork()
    }

    fun toggleLayerVisibility(index: Int) {
        val target = layers.getOrNull(index) ?: return
        val nextVis = !target.visible
        layers = layers.toMutableList().apply { set(index, target.copy(visible = nextVis)) }
        if (timelineTracks.isNotEmpty()) {
            timelineTracks = timelineTracks.map { tr ->
                if (tr.kind == TrackKind.PAINT && tr.layerId == target.id) tr.copy(visible = nextVis) else tr
            }
        }
        rebuildTimelineRenderState()
        recomposite()
        persistCurrentArtwork()
    }

    fun setLayerOpacity(index: Int, opacity: Float) {
        val target = layers.getOrNull(index) ?: return
        val clamped = opacity.coerceIn(0f, 1f)
        layers = layers.toMutableList().apply { set(index, target.copy(opacity = clamped)) }
        if (timelineTracks.isNotEmpty()) {
            timelineTracks = timelineTracks.map { tr ->
                if (tr.kind == TrackKind.PAINT && tr.layerId == target.id) tr.copy(opacity = clamped) else tr
            }
        }
        rebuildTimelineRenderState()
        recomposite()
    }

    fun setLayerBlendMode(index: Int, mode: LayerBlendMode) {
        val target = layers.getOrNull(index) ?: return
        pushUndoSnapshot()
        layers = layers.toMutableList().apply { set(index, target.copy(blendMode = mode)) }
        recomposite()
        persistCurrentArtwork()
    }

    fun renameLayer(index: Int, name: String) {
        val target = layers.getOrNull(index) ?: return
        val clean = name.trim().ifBlank { return }
        layers = layers.toMutableList().apply { set(index, target.copy(name = clean)) }
        if (timelineTracks.isNotEmpty()) {
            timelineTracks = timelineTracks.map { tr ->
                if (tr.kind == TrackKind.PAINT && tr.layerId == target.id) tr.copy(name = clean) else tr
            }
        }
        rebuildTimelineRenderState()
        persistCurrentArtwork()
    }

    fun toggleAlphaLock(index: Int) {
        val target = layers.getOrNull(index) ?: return
        layers = layers.toMutableList().apply { set(index, target.copy(alphaLock = !target.alphaLock)) }
        persistCurrentArtwork()
    }

    fun toggleClippingMask(index: Int) {
        val target = layers.getOrNull(index) ?: return
        if (index == 0) return
        layers = layers.toMutableList().apply { set(index, target.copy(clippingMask = !target.clippingMask, isMask = false)) }
        recomposite()
        persistCurrentArtwork()
    }

    fun toggleMaskLayer(index: Int) {
        val target = layers.getOrNull(index) ?: return
        if (index == 0) {
            showNotice("Mask must sit above a base layer")
            return
        }
        layers = layers.toMutableList().apply { set(index, target.copy(isMask = !target.isMask, clippingMask = false)) }
        recomposite()
        persistCurrentArtwork()
    }

    fun togglePrivateLayer(index: Int) {
        val target = layers.getOrNull(index) ?: return
        val next = !target.isPrivate
        layers = layers.toMutableList().apply { set(index, target.copy(isPrivate = next)) }
        persistCurrentArtwork()
        showNotice(if (next) "Private Layer: hidden in Time-lapse" else "Private Layer off")
    }

    fun toggleLayerLock(index: Int) {
        val target = layers.getOrNull(index) ?: return
        val nextLocked = !target.locked
        layers = layers.toMutableList().apply { set(index, target.copy(locked = nextLocked)) }
        if (timelineTracks.isNotEmpty()) {
            timelineTracks = timelineTracks.map { tr ->
                if (tr.kind == TrackKind.PAINT && tr.layerId == target.id) tr.copy(locked = nextLocked) else tr
            }
        }
        persistCurrentArtwork()
    }

    fun toggleReferenceLayer(index: Int) {
        val target = layers.getOrNull(index) ?: return
        val nextRef = !target.isReference
        layers = layers.mapIndexed { i, l -> l.copy(isReference = if (i == index) nextRef else false) }
        persistCurrentArtwork()
    }

    fun selectLayerPixels(index: Int) {
        val target = layers.getOrNull(index) ?: return
        val mask = selectionMask ?: return
        mask.selectFromBitmapAlpha(target.bitmap)
        updateSelectionOverlay()
        panel = Panel.NONE
        selecting = true
        showNotice("Selected layer contents")
    }

    fun toggleHoldLayer(layerId: String) {
        heldLayerIds = if (layerId in heldLayerIds) heldLayerIds - layerId else heldLayerIds + layerId
    }

    private fun recycleUnreferencedBitmaps(candidates: List<Bitmap>) {
        candidates.forEach { bmp ->
            if (!bmp.isRecycled &&
                layers.none { it.bitmap === bmp } &&
                !isBitmapOwnedByTimeline(bmp) &&
                renderLayersCache.none { it.bitmap === bmp }
            ) {
                bmp.recycle()
            }
        }
    }

    fun mergeVisibleLayers() {
        val art = currentArtwork ?: return
        val visibleLayers = layers.filter { it.visible }
        if (visibleLayers.size <= 1) {
            showNotice("Need 2+ visible layers to merge")
            return
        }
        pushUndoSnapshot()
        val oldBitmaps = visibleLayers.map { it.bitmap }
        val safeSource = layers.filter { !it.bitmap.isRecycled }
        val mergedBmp = LayerCompositor.composite(art.width, art.height, safeSource, 0, false, AnimationState())
        val hidden = layers.filter { !it.visible }.toMutableList()
        val mergedLayer = LayerState(UUID.randomUUID().toString(), "Merged Visible", bitmap = mergedBmp)
        hidden.add(mergedLayer)
        layers = hidden
        activeLayerIndex = layers.lastIndex.coerceAtLeast(0)
        heldLayerIds = emptySet()
        if (timelineTracks.isNotEmpty()) syncPaintTracksWithLayers()
        rebuildTimelineRenderState()
        recomposite()
        refreshLayerThumbnails()
        recycleUnreferencedBitmaps(oldBitmaps)
        persistCurrentArtwork()
        showNotice("Merged visible layers")
    }

    fun mergeHeldLayers() {
        if (heldLayerIds.size < 2) return
        val art = currentArtwork ?: return
        val toMerge = layers.filter { it.id in heldLayerIds }
        if (toMerge.size < 2) return
        pushUndoSnapshot()
        val oldBitmaps = toMerge.map { it.bitmap }
        val safeToMerge = toMerge.filter { !it.bitmap.isRecycled }
        val mergedBmp = LayerCompositor.composite(art.width, art.height, safeToMerge, 0, false, AnimationState())
        val remaining = mutableListOf<LayerState>()
        var inserted = false
        for (l in layers) {
            if (l.id in heldLayerIds) {
                if (!inserted) {
                    remaining.add(LayerState(UUID.randomUUID().toString(), "${toMerge.first().name} Merged", bitmap = mergedBmp))
                    inserted = true
                }
            } else {
                remaining.add(l)
            }
        }
        layers = remaining
        activeLayerIndex = activeLayerIndex.coerceIn(0, layers.lastIndex.coerceAtLeast(0))
        heldLayerIds = emptySet()
        if (timelineTracks.isNotEmpty()) syncPaintTracksWithLayers()
        rebuildTimelineRenderState()
        recomposite()
        refreshLayerThumbnails()
        recycleUnreferencedBitmaps(oldBitmaps)
        persistCurrentArtwork()
        showNotice("Merged selected layers")
    }

    fun clearLayer(index: Int) {
        val target = layers.getOrNull(index) ?: return
        if (target.locked) { showNotice("Layer is locked"); return }
        pushUndoSnapshot()
        target.bitmap.eraseColor(Color.TRANSPARENT)
        recomposite()
        refreshLayerThumbnail(index)
        persistCurrentArtwork()
        showNotice("Layer cleared")
    }

    fun fillLayer(index: Int) {
        val target = layers.getOrNull(index) ?: return
        if (target.locked) { showNotice("Layer is locked"); return }
        pushUndoSnapshot()
        if (target.alphaLock) {
            val c = Canvas(target.bitmap)
            c.drawColor(colorRgb, PorterDuff.Mode.SRC_ATOP)
        } else {
            target.bitmap.eraseColor(colorRgb)
        }
        recomposite()
        refreshLayerThumbnail(index)
        persistCurrentArtwork()
    }

    fun invertLayerColors(index: Int) {
        val target = layers.getOrNull(index) ?: return
        if (target.locked) { showNotice("Layer is locked"); return }
        pushUndoSnapshot()
        val w = target.bitmap.width
        val h = target.bitmap.height
        val px = IntArray(w * h)
        target.bitmap.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val a = Color.alpha(c)
            if (a == 0) continue
            px[i] = Color.argb(a, 255 - Color.red(c), 255 - Color.green(c), 255 - Color.blue(c))
        }
        target.bitmap.setPixels(px, 0, w, 0, 0, w, h)
        recomposite()
        refreshLayerThumbnail(index)
        persistCurrentArtwork()
    }

    fun mergeLayerDown(index: Int) {
        if (index <= 0 || index !in layers.indices) return
        val top = layers[index]
        val bottom = layers[index - 1]
        if (top.locked || bottom.locked) { showNotice("Locked layer cannot merge"); return }
        if (top.bitmap.isRecycled || bottom.bitmap.isRecycled) return
        pushUndoSnapshot()
        val merged = LayerCompositor.composite(bottom.bitmap.width, bottom.bitmap.height, listOf(bottom, top), 0, false, AnimationState())
        bottom.bitmap.eraseColor(Color.TRANSPARENT)
        Canvas(bottom.bitmap).drawBitmap(merged, 0f, 0f, null)
        merged.recycle()
        layers = layers.toMutableList().apply { removeAt(index) }
        activeLayerIndex = index - 1
        if (timelineTracks.isNotEmpty()) syncPaintTracksWithLayers()
        rebuildTimelineRenderState()
        recomposite()
        refreshLayerThumbnail(activeLayerIndex)
        recycleUnreferencedBitmaps(listOf(top.bitmap))
        persistCurrentArtwork()
    }

    fun toggleBackgroundVisibility() {
        pushUndoSnapshot()
        backgroundVisible = !backgroundVisible
        currentArtwork = currentArtwork?.copy(backgroundVisible = backgroundVisible)
        recomposite()
        persistCurrentArtwork()
    }

    fun setBackgroundColor(rgb: Int) {
        backgroundRgb = rgb or 0xFF000000.toInt()
        currentArtwork = currentArtwork?.copy(backgroundRgb = backgroundRgb)
        recomposite()
        persistCurrentArtwork()
    }

    // --- Copy / Cut / Paste ---

    fun copyActiveOrSelection() {
        val layer = activeLayer() ?: return
        val mask = selectionMask
        clipboardBitmap?.recycle()
        clipboardBitmap = if (mask != null && !mask.isEmpty()) {
            mask.extractCopy(layer.bitmap)
        } else {
            layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        showNotice("Copied to ProCraft clipboard")
    }

    fun copyCanvasAll() {
        val comp = ensureCompositeBitmap() ?: return
        clipboardBitmap?.recycle()
        clipboardBitmap = comp.copy(Bitmap.Config.ARGB_8888, false)
        showNotice("Copied canvas")
    }

    fun cutActiveOrSelection() {
        val layer = activeLayer() ?: return
        if (layer.locked) { showNotice("Layer is locked"); return }
        pushUndoSnapshot()
        copyActiveOrSelection()
        val mask = selectionMask
        if (mask != null && !mask.isEmpty()) {
            mask.clearBitmap(layer.bitmap)
        } else {
            layer.bitmap.eraseColor(Color.TRANSPARENT)
        }
        recomposite()
        refreshLayerThumbnail(activeLayerIndex)
        persistCurrentArtwork()
    }

    fun cutAndPaste() {
        val layer = activeLayer() ?: return
        if (layer.locked) { showNotice("Layer is locked"); return }
        cutActiveOrSelection()
        pasteFromClipboard()
    }

    fun pasteFromClipboard() {
        val art = currentArtwork ?: return
        val clip = clipboardBitmap ?: run {
            showNotice("Clipboard is empty")
            return
        }
        pushUndoSnapshot()
        val newBmp = Bitmap.createBitmap(art.width, art.height, Bitmap.Config.ARGB_8888)
        Canvas(newBmp).drawBitmap(clip, 0f, 0f, null)
        val newLayer = LayerState(UUID.randomUUID().toString(), "Inserted Image", bitmap = newBmp)
        val insertAt = (activeLayerIndex + 1).coerceIn(0, layers.size)
        layers = layers.toMutableList().apply { add(insertAt, newLayer) }
        activeLayerIndex = insertAt
        recomposite()
        refreshLayerThumbnail(insertAt)
        persistCurrentArtwork()
        showNotice("Pasted onto new layer")
    }

    // --- Adjustments ---

    fun startAdjustment(kind: AdjustmentKind) {
        val layer = activeLayer() ?: return
        if (layer.locked) { showNotice("Layer is locked"); return }
        if (adjustingKind != null) cancelAdjustment()
        panel = Panel.NONE
        selecting = false
        if (transforming) commitTransform()
        adjustBackupBitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        adjustingKind = kind
        adjustmentPaintMode = false
        adjustFilteredBitmap?.recycle()
        adjustFilteredBitmap = null
        adjustBrushMask = null
        adjustAmount = when (kind) {
            AdjustmentKind.GAUSSIAN_BLUR, AdjustmentKind.MOTION_BLUR, AdjustmentKind.PERSPECTIVE_BLUR,
            AdjustmentKind.NOISE, AdjustmentKind.BLOOM, AdjustmentKind.GLITCH, AdjustmentKind.HALFTONE,
            AdjustmentKind.CHROMATIC_ABERRATION, AdjustmentKind.SHARPEN -> 0.32f
            AdjustmentKind.GRADIENT_MAP -> 1f
            else -> 0.5f
        }
        adjustHsb = HsbParams()
        adjustBalance = BalanceParams()
        adjustCurves = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        adjustCurveChannel = CurveChannel.ALL
        cloneSource = null
        previewAdjustment()
    }

    fun toggleAdjustmentPaintMode(paintOn: Boolean) {
        val kind = adjustingKind ?: return
        if (kind == AdjustmentKind.LIQUIFY || kind == AdjustmentKind.CLONE) return
        val backup = adjustBackupBitmap ?: return
        val layer = activeLayer() ?: return
        adjustmentPaintMode = paintOn
        if (!paintOn) {
            adjustBrushMask = null
            adjustFilteredBitmap?.recycle()
            adjustFilteredBitmap = null
            previewAdjustment()
        } else {
            // Prepare filtered buffer & blank mask so painting selectively reveals the adjustment
            adjustBrushMask = ByteArray(backup.width * backup.height)
            rebuildAdjustmentFilteredBitmap()
            val c = Canvas(layer.bitmap)
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            c.drawBitmap(backup, 0f, 0f, null)
            recomposite()
            showNotice("Paint over layer to apply adjustment")
        }
    }

    private fun rebuildAdjustmentFilteredBitmap() {
        val kind = adjustingKind ?: return
        val backup = adjustBackupBitmap ?: return
        adjustFilteredBitmap?.recycle()
        adjustFilteredBitmap = ImageAdjustments.applyFilter(
            source = backup,
            kind = kind,
            amount = adjustAmount,
            hsb = adjustHsb,
            balance = adjustBalance,
            curvesPoints = adjustCurves,
            curveChannel = adjustCurveChannel,
            gradient = adjustGradient,
        )
    }

    private fun paintAdjustmentDab(pos: Offset, pressure: Float) {
        val backup = adjustBackupBitmap ?: return
        val filtered = adjustFilteredBitmap ?: return
        val mask = adjustBrushMask ?: return
        val layer = activeLayer() ?: return
        val w = backup.width
        val h = backup.height
        val radius = (18f + brushSize * 110f) * pressure.coerceIn(0.35f, 1f)
        val x0 = (pos.x - radius).toInt().coerceIn(0, w - 1)
        val x1 = (pos.x + radius).toInt().coerceIn(0, w - 1)
        val y0 = (pos.y - radius).toInt().coerceIn(0, h - 1)
        val y1 = (pos.y + radius).toInt().coerceIn(0, h - 1)
        val rSq = radius * radius
        val strength = (brushOpacity * 255f).toInt().coerceIn(32, 255)
        for (y in y0..y1) {
            val dy = y - pos.y
            val row = y * w
            for (x in x0..x1) {
                val dx = x - pos.x
                val dSq = dx * dx + dy * dy
                if (dSq <= rSq) {
                    val falloff = 1f - (sqrt(dSq) / radius).coerceIn(0f, 1f)
                    val cur = mask[row + x].toInt() and 0xFF
                    val add = (falloff * strength).toInt()
                    val next = max(cur, add).coerceIn(0, 255)
                    mask[row + x] = next.toByte()
                }
            }
        }
        ImageAdjustments.blendOriginalAndFilteredByMask(backup, filtered, mask, layer.bitmap)
        recompositeRect(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat())
    }

    fun previewAdjustment() {
        val kind = adjustingKind ?: return
        if (kind == AdjustmentKind.LIQUIFY || kind == AdjustmentKind.CLONE) return
        val backup = adjustBackupBitmap ?: return
        val layer = activeLayer() ?: return
        if (adjustmentPaintMode) {
            rebuildAdjustmentFilteredBitmap()
            val mask = adjustBrushMask
            val filtered = adjustFilteredBitmap
            if (mask != null && filtered != null) {
                ImageAdjustments.blendOriginalAndFilteredByMask(backup, filtered, mask, layer.bitmap)
                recomposite()
            }
            return
        }
        val filtered = ImageAdjustments.applyFilter(
            source = backup,
            kind = kind,
            amount = adjustAmount,
            hsb = adjustHsb,
            balance = adjustBalance,
            curvesPoints = adjustCurves,
            curveChannel = adjustCurveChannel,
            gradient = adjustGradient,
        )
        val c = Canvas(layer.bitmap)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        c.drawBitmap(filtered, 0f, 0f, null)
        filtered.recycle()
        recomposite()
    }

    fun applyAdjustment() {
        val backup = adjustBackupBitmap
        adjustBackupBitmap = null
        adjustFilteredBitmap?.recycle()
        adjustFilteredBitmap = null
        adjustBrushMask = null
        adjustmentPaintMode = false
        adjustingKind = null
        cloneSource = null
        if (backup != null) {
            pushBitmapBackupUndo(activeLayerIndex, backup)
        }
        recomposite()
        refreshLayerThumbnail(activeLayerIndex)
        persistCurrentArtwork()
    }

    fun cancelAdjustment() {
        val backup = adjustBackupBitmap
        val layer = activeLayer()
        if (backup != null && layer != null) {
            val c = Canvas(layer.bitmap)
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            c.drawBitmap(backup, 0f, 0f, null)
            backup.recycle()
        }
        adjustBackupBitmap = null
        adjustFilteredBitmap?.recycle()
        adjustFilteredBitmap = null
        adjustBrushMask = null
        adjustmentPaintMode = false
        adjustingKind = null
        cloneSource = null
        recomposite()
    }

    // --- Selection Operations ---

    fun updateSelectionOverlay() {
        val mask = selectionMask
        selectionOverlay?.recycle()
        selectionOverlay = if (mask != null && !mask.isEmpty()) mask.toOverlayBitmap() else null
        selectionVersion++
    }

    fun invertSelection() {
        selectionMask?.invert()
        updateSelectionOverlay()
    }

    fun clearSelectionMask() {
        selectionMask?.clear()
        updateSelectionOverlay()
    }

    fun featherSelection(radius: Int) {
        selectionMask?.feather(radius)
        updateSelectionOverlay()
    }

    fun fillSelectionWithColor() {
        val mask = selectionMask ?: return
        val layer = activeLayer() ?: return
        if (layer.locked) { showNotice("Layer is locked"); return }
        pushUndoSnapshot()
        mask.fillBitmap(layer.bitmap, colorRgb)
        recomposite()
        refreshLayerThumbnail(activeLayerIndex)
        persistCurrentArtwork()
    }

    fun clearSelectedPixels() {
        val mask = selectionMask ?: return
        val layer = activeLayer() ?: return
        if (layer.locked) { showNotice("Layer is locked"); return }
        pushUndoSnapshot()
        mask.clearBitmap(layer.bitmap)
        recomposite()
        refreshLayerThumbnail(activeLayerIndex)
        persistCurrentArtwork()
    }

    fun saveCurrentSelection() {
        val mask = selectionMask ?: return
        savedSelection = mask.alpha.copyOf()
        showNotice("Selection saved")
    }

    fun loadSavedSelection() {
        val saved = savedSelection ?: run {
            showNotice("No saved selection")
            return
        }
        val mask = selectionMask ?: return
        if (saved.size == mask.alpha.size) {
            System.arraycopy(saved, 0, mask.alpha, 0, saved.size)
            updateSelectionOverlay()
            showNotice("Selection loaded")
        }
    }

    // --- Transform Operations ---

    internal fun initTransformBackups(
        fullBackup: Bitmap,
        staticBase: Bitmap?,
        selectionScoped: Bitmap?,
        initialState: LayerTransformState,
    ) {
        transformBackupBitmap?.recycle()
        transformStaticBaseBitmap?.recycle()
        transformSelectionScopedBitmap?.recycle()
        transformBackupBitmap = fullBackup
        transformStaticBaseBitmap = staticBase
        transformSelectionScopedBitmap = selectionScoped
        transformState = initialState
        transforming = true
    }

    fun previewTransform(next: LayerTransformState) {
        transformState = next
        val source = transformSelectionScopedBitmap ?: transformBackupBitmap ?: return
        val layer = activeLayer() ?: return
        val transformed = TransformEngine.applyTransform(source, next)
        val c = Canvas(layer.bitmap)
        val staticBase = transformStaticBaseBitmap
        if (staticBase != null && !staticBase.isRecycled) {
            c.drawBitmap(staticBase, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
        } else {
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }
        c.drawBitmap(transformed, 0f, 0f, null)
        transformed.recycle()
        recomposite()
    }

    fun commitTransform() {
        val backup = transformBackupBitmap
        transformBackupBitmap = null
        transformStaticBaseBitmap?.recycle()
        transformStaticBaseBitmap = null
        transformSelectionScopedBitmap?.recycle()
        transformSelectionScopedBitmap = null
        transforming = false
        anchorMoveMode = false
        if (backup != null) {
            pushBitmapBackupUndo(activeLayerIndex, backup)
        }
        recomposite()
        refreshLayerThumbnail(activeLayerIndex)
        if (animationState.isAnimation) {
            val tr = activeLayer()?.let { al -> timelineTracks.firstOrNull { it.kind == TrackKind.PAINT && it.layerId == al.id } }
            val clip = tr?.clipAt(animationState.currentFrameIndex)
            if (clip != null) refreshSingleClipThumbnailAsync(clip.id)
        }
        persistCurrentArtwork()
    }

    internal fun clearTransformBackupsWithoutUndo() {
        transformBackupBitmap?.takeIf { !it.isRecycled }?.recycle()
        transformBackupBitmap = null
        transformStaticBaseBitmap?.takeIf { !it.isRecycled }?.recycle()
        transformStaticBaseBitmap = null
        transformSelectionScopedBitmap?.takeIf { !it.isRecycled }?.recycle()
        transformSelectionScopedBitmap = null
        transforming = false
        anchorMoveMode = false
    }

    fun resetTransform() {
        val bounds = transformState.boundsLeft?.let { l ->
            RectF(l, transformState.boundsTop ?: 0f, transformState.boundsRight ?: (currentArtwork?.width?.toFloat() ?: 1024f), transformState.boundsBottom ?: (currentArtwork?.height?.toFloat() ?: 1024f))
        }
        val ax = bounds?.centerX()
        val ay = bounds?.centerY()
        previewTransform(
            LayerTransformState(
                kind = transformState.kind,
                snapping = transformState.snapping,
                interpolation = transformState.interpolation,
                anchorX = ax,
                anchorY = ay,
                boundsLeft = bounds?.left,
                boundsTop = bounds?.top,
                boundsRight = bounds?.right,
                boundsBottom = bounds?.bottom,
            ),
        )
    }

    // --- Crop & Resize ---

    fun startCrop() {
        val art = currentArtwork ?: return
        panel = Panel.NONE
        cropState = CropState(0, 0, art.width, art.height)
        cropping = true
    }

    fun applyCrop() {
        val art = currentArtwork ?: return
        val crop = cropState ?: return
        val nw = (crop.right - crop.left).coerceIn(64, 4096)
        val nh = (crop.bottom - crop.top).coerceIn(64, 4096)
        clearHistory()
        val oldBitmaps = layers.map { it.bitmap }
        layers = layers.map { l ->
            val out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
            if (!l.bitmap.isRecycled) {
                Canvas(out).drawBitmap(l.bitmap, -crop.left.toFloat(), -crop.top.toFloat(), null)
            }
            l.copy(bitmap = out)
        }
        if (timelineTracks.isNotEmpty()) syncPaintTracksWithLayers()
        rebuildTimelineRenderState()
        recycleUnreferencedBitmaps(oldBitmaps)
        currentArtwork = art.copy(width = nw, height = nh, updatedAtMs = System.currentTimeMillis())
        selectionMask = SelectionMask(nw, nh)
        tileCache?.recycle()
        tileCache = CanvasTileCache(nw, nh)
        compositeBitmap?.recycle()
        compositeBitmap = null
        compositeDirty = true
        cropping = false
        cropState = null
        recomposite()
        refreshLayerThumbnails()
        fitCanvasToScreen()
        persistCurrentArtwork()
    }

    // --- Text Layer ---

    fun startAddText() {
        panel = Panel.NONE
        textConfig = TextLayerConfig(colorRgb = colorRgb)
        textEditing = true
    }

    fun commitTextLayer() {
        val art = currentArtwork ?: return
        if (textConfig.text.isBlank()) {
            textEditing = false
            return
        }
        pushUndoSnapshot()
        val bmp = Bitmap.createBitmap(art.width, art.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val style = when {
            textConfig.bold && textConfig.italic -> Typeface.BOLD_ITALIC
            textConfig.bold -> Typeface.BOLD
            textConfig.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textConfig.colorRgb
            textSize = textConfig.fontSizePx
            typeface = Typeface.create(Typeface.SANS_SERIF, style)
            isUnderlineText = textConfig.underline
        }
        val align = when (textConfig.alignment) {
            0 -> Layout.Alignment.ALIGN_NORMAL
            2 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        val maxW = (art.width * 0.85f).toInt().coerceAtLeast(100)
        val layout = StaticLayout.Builder.obtain(textConfig.text, 0, textConfig.text.length, paint, maxW)
            .setAlignment(align)
            .build()
        val tx = (art.width * textConfig.xFraction - layout.width * 0.5f).coerceIn(0f, (art.width - layout.width).toFloat().coerceAtLeast(0f))
        val ty = (art.height * textConfig.yFraction - layout.height * 0.5f).coerceIn(0f, (art.height - layout.height).toFloat().coerceAtLeast(0f))
        c.save()
        c.translate(tx, ty)
        layout.draw(c)
        c.restore()

        val layer = LayerState(UUID.randomUUID().toString(), textConfig.text.take(20), bitmap = bmp)
        val insertAt = (activeLayerIndex + 1).coerceIn(0, layers.size)
        layers = layers.toMutableList().apply { add(insertAt, layer) }
        activeLayerIndex = insertAt
        textEditing = false
        if (timelineTracks.isNotEmpty()) syncPaintTracksWithLayers()
        rebuildTimelineRenderState()
        recomposite()
        refreshLayerThumbnail(insertAt)
        persistCurrentArtwork()
    }

    // --- Animation Assist & Page Assist ---

    fun toggleAnimationAssist() {
        val next = !animationState.isAnimation
        animationPlayJob?.cancel()
        audioEngine.stopPlayback()
        if (next) {
            ensureTimelineInitialized()
        }
        val activeTrackId = activeLayer()?.let { al ->
            timelineTracks.firstOrNull { it.kind == TrackKind.PAINT && it.layerId == al.id }?.id
        } ?: animationState.selectedTrackId
        animationState = animationState.copy(
            enabled = next,
            assistMode = if (next) CanvasAssistMode.ANIMATION else CanvasAssistMode.NONE,
            playing = false,
            currentFrameIndex = if (next) animationState.currentFrameIndex.coerceIn(0, (animationState.totalFrames - 1).coerceAtLeast(0)) else 0,
            selectedTrackId = activeTrackId,
        )
        rebuildTimelineRenderState()
        recomposite()
    }

    fun togglePageAssist() {
        val next = !animationState.isPageAssist
        animationPlayJob?.cancel()
        audioEngine.stopPlayback()
        animationState = animationState.copy(
            enabled = next,
            assistMode = if (next) CanvasAssistMode.PAGE_ASSIST else CanvasAssistMode.NONE,
            playing = false,
            currentFrameIndex = activeLayerIndex.coerceIn(0, layers.lastIndex.coerceAtLeast(0)),
        )
        rebuildTimelineRenderState()
        recomposite()
    }

    fun nextAssistPage() {
        if (layers.isEmpty()) return
        val next = (activeLayerIndex + 1).coerceAtMost(layers.lastIndex)
        selectLayer(next)
    }

    fun prevAssistPage() {
        if (layers.isEmpty()) return
        val prev = (activeLayerIndex - 1).coerceAtLeast(0)
        selectLayer(prev)
    }

    fun toggleAnimationPlayback() {
        val play = !animationState.playing
        animationState = animationState.copy(playing = play)
        animationPlayJob?.cancel()
        if (!play) {
            audioEngine.stopPlayback()
            return
        }
        if (animationState.isPageAssist) {
            if (layers.isNotEmpty()) {
                animationPlayJob = viewModelScope.launch {
                    while (animationState.playing && layers.isNotEmpty()) {
                        val delayMs = (1000L / animationState.fps.coerceIn(1, 60))
                        delay(delayMs)
                        val nextIdx = (animationState.currentFrameIndex + 1) % layers.size
                        animationState = animationState.copy(currentFrameIndex = nextIdx)
                        activeLayerIndex = nextIdx
                        recomposite()
                    }
                }
            }
            return
        }
        val frameTotal = animationState.totalFrames.coerceAtLeast(1)
        animationPlayJob = viewModelScope.launch {
            while (animationState.playing) {
                val curFrame = animationState.currentFrameIndex.coerceIn(0, frameTotal - 1)
                val curFps = animationState.fps.coerceIn(1, 60)
                audioEngine.playFrameSlice(curFrame, curFps, timelineTracks)
                delay(1000L / curFps)
                val nextFrame = (curFrame + 1) % frameTotal
                scrubToFrame(nextFrame, fromUserDrag = false)
            }
        }
    }

    fun exportAnimatedSequence(format: String, fps: Int, transparentBg: Boolean, pingPong: Boolean) {
        val art = currentArtwork ?: return
        val bgRgb = backgroundRgb
        val isMp4 = format.equals("MP4", ignoreCase = true)
        val showBg = backgroundVisible && (!transparentBg || isMp4)
        if (timelineTracks.isNotEmpty() && animationState.totalFrames > 0) {
            val total = animationState.totalFrames.coerceIn(1, 300)
            val snapshots = ArrayList<Bitmap>(total)
            for (f in 0 until total) {
                val frameLayers = buildRenderLayersForFrame(f)
                val bmp = LayerCompositor.composite(
                    width = art.width,
                    height = art.height,
                    layers = frameLayers,
                    backgroundRgb = bgRgb,
                    backgroundVisible = showBg,
                    animation = AnimationState(),
                )
                snapshots.add(bmp)
            }
            viewModelScope.launch(Dispatchers.IO) {
                val ordered = if (pingPong && snapshots.size > 2) {
                    snapshots + snapshots.subList(1, snapshots.size - 1).reversed()
                } else snapshots
                when (format.uppercase()) {
                    "MP4" -> {
                        val ok = repo.exportMp4AndShare(art.title, ordered, fps = fps)
                        if (!ok) {
                            withContext(Dispatchers.Main) { showNotice("MP4 encoding failed") }
                        }
                    }
                    "GIF" -> repo.exportAnimatedGifAndShare(art.title, ordered, fps = fps, transparentBg = transparentBg)
                    "SHEET" -> repo.exportAndShare(art.title, "SHEET", snapshots)
                    else -> repo.exportAndShare(art.title, "PDF", snapshots)
                }
                snapshots.forEach { it.recycle() }
            }
            return
        }

        if (layers.isEmpty()) return
        val snapshots = layers.map { l ->
            LayerState(l.id, l.name, visible = true, opacity = l.opacity, blendMode = l.blendMode, bitmap = l.bitmap.copy(Bitmap.Config.ARGB_8888, false))
        }
        viewModelScope.launch(Dispatchers.IO) {
            val frames = snapshots.map { frameLayer ->
                LayerCompositor.composite(art.width, art.height, listOf(frameLayer), bgRgb, showBg, AnimationState())
            }
            val orderedFrames = if (pingPong && frames.size > 2) {
                frames + frames.subList(1, frames.size - 1).reversed()
            } else frames
            when (format.uppercase()) {
                "MP4" -> {
                    val ok = repo.exportMp4AndShare(art.title, orderedFrames, fps = fps)
                    if (!ok) {
                        withContext(Dispatchers.Main) { showNotice("MP4 encoding failed") }
                    }
                }
                "GIF" -> repo.exportAnimatedGifAndShare(art.title, orderedFrames, fps = fps, transparentBg = transparentBg)
                "SHEET" -> repo.exportAndShare(art.title, "SHEET", frames)
                else -> repo.exportAndShare(art.title, "PDF", frames)
            }
            frames.forEach { it.recycle() }
            snapshots.forEach { it.bitmap.recycle() }
        }
    }

    // --- Reference Image ---

    fun pickReferenceImage(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = repo.decodeImageFromUri(uri, maxDim = 960) ?: return@launch
            withContext(Dispatchers.Main) {
                referenceBitmap?.recycle()
                referenceBitmap = bmp
                referenceVisible = true
            }
        }
    }

    // --- QuickMenu & Gesture Settings ---

    fun runQuickAction(action: QuickAction) {
        quickMenuCenter = null
        when (action) {
            QuickAction.NEW_LAYER -> addLayer()
            QuickAction.COPY -> copyActiveOrSelection()
            QuickAction.PASTE -> pasteFromClipboard()
            QuickAction.CUT -> cutActiveOrSelection()
            QuickAction.DUPLICATE_LAYER -> duplicateLayer(activeLayerIndex)
            QuickAction.MERGE_DOWN -> mergeLayerDown(activeLayerIndex)
            QuickAction.CLEAR_LAYER -> clearLayer(activeLayerIndex)
            QuickAction.FLIP_HORIZONTAL -> {
                toggleTransform()
                if (transforming) {
                    previewTransform(transformState.copy(flipH = true))
                    commitTransform()
                }
            }
            QuickAction.FLIP_VERTICAL -> {
                toggleTransform()
                if (transforming) {
                    previewTransform(transformState.copy(flipV = true))
                    commitTransform()
                }
            }
            QuickAction.ALPHA_LOCK -> toggleAlphaLock(activeLayerIndex)
            QuickAction.EYEDROPPER -> { pickNext = true; showNotice("Tap canvas to pick color") }
            QuickAction.FULL_SCREEN -> fullscreen = !fullscreen
            QuickAction.UNDO -> undo()
            QuickAction.REDO -> redo()
            QuickAction.COPY_PASTE_MENU -> copyPasteMenuOpen = true
            QuickAction.SELECT_PIXELS -> selectLayerPixels(activeLayerIndex)
        }
    }

    fun updateGestureSettings(transform: (GestureSettings) -> GestureSettings) {
        val next = transform(gestureSettings)
        gestureSettings = next
        Pc.light = next.lightInterface
        strokeSound.setEnabled(next.strokeSoundEnabled)
        viewModelScope.launch(Dispatchers.IO) { repo.saveGestureSettings(next) }
    }

    // --- Time-lapse ---

    fun toggleTimelapseEnabled() {
        val art = currentArtwork ?: return
        val next = !art.timelapseEnabled
        val updated = art.copy(timelapseEnabled = next)
        currentArtwork = updated
        viewModelScope.launch(Dispatchers.IO) { repo.saveMetaOnly(updated) }
        showNotice(if (next) "Time-lapse Recording On" else "Time-lapse Recording Paused")
    }

    fun clearTimeLapse() {
        val art = currentArtwork ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repo.clearTimeLapseFrames(art.id)
            withContext(Dispatchers.Main) {
                timelapseFrames = emptyList()
                showNotice("Time-lapse cleared")
            }
        }
    }

    fun exportTimeLapseGif(fps: Int = 8) {
        val art = currentArtwork ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val files = repo.listTimeLapseFrames(art.id)
            if (files.isEmpty()) {
                withContext(Dispatchers.Main) { showNotice("No time-lapse frames yet") }
                return@launch
            }
            val bmps = files.mapNotNull { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }
            if (bmps.isNotEmpty()) {
                repo.exportAnimatedGifAndShare("${art.title}_timelapse", bmps, fps = fps, transparentBg = false, maxDim = 512)
                bmps.forEach { it.recycle() }
            }
        }
    }

    fun exportTimeLapseMp4(fps: Int = 12) {
        val art = currentArtwork ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val files = repo.listTimeLapseFrames(art.id)
            if (files.isEmpty()) {
                withContext(Dispatchers.Main) { showNotice("No time-lapse frames yet") }
                return@launch
            }
            val bmps = files.mapNotNull { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }
            if (bmps.isNotEmpty()) {
                val ok = repo.exportMp4AndShare("${art.title}_timelapse", bmps, fps = fps, maxDim = 1080)
                bmps.forEach { it.recycle() }
                if (!ok) {
                    withContext(Dispatchers.Main) { showNotice("MP4 encoding failed") }
                }
            }
        }
    }

    fun openTimeLapseReplay() {
        val art = currentArtwork ?: return
        panel = Panel.NONE
        viewModelScope.launch(Dispatchers.IO) {
            val frames = repo.listTimeLapseFrames(art.id)
            withContext(Dispatchers.Main) {
                timelapseFrames = frames
                timelapseReplayOpen = true
            }
        }
    }

    // --- Compositing & Saving ---

    fun recomposite() {
        val art = currentArtwork ?: return
        val cache = tileCache ?: CanvasTileCache(art.width, art.height).also { tileCache = it }
        val effective = if (animationState.isAnimation) renderLayersCache else layers
        cache.recompositeAll(
            layers = effective,
            backgroundRgb = backgroundRgb,
            backgroundVisible = backgroundVisible,
            animation = animationState,
            onionSkins = onionSkinCache,
        )
        compositeDirty = true
        compositeTick++
    }

    private fun recompositeRect(left: Float, top: Float, right: Float, bottom: Float) {
        val cache = tileCache ?: run {
            recomposite()
            return
        }
        val effective = if (animationState.isAnimation) renderLayersCache else layers
        cache.recompositeRect(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            layers = effective,
            backgroundRgb = backgroundRgb,
            backgroundVisible = backgroundVisible,
            animation = animationState,
            onionSkins = onionSkinCache,
        )
        compositeDirty = true
        compositeTick++
    }

    private fun ensureCompositeBitmap(): Bitmap? {
        val art = currentArtwork ?: return null
        if (!compositeDirty && compositeBitmap != null && compositeBitmap!!.width == art.width && compositeBitmap!!.height == art.height) {
            return compositeBitmap
        }
        val effective = if (animationState.isAnimation) renderLayersCache else layers
        val assembled = tileCache?.assembleBitmap(compositeBitmap)
            ?: LayerCompositor.composite(art.width, art.height, effective, backgroundRgb, backgroundVisible, animationState, compositeBitmap, onionSkinCache)
        compositeBitmap = assembled
        compositeDirty = false
        return assembled
    }

    internal fun refreshLayerThumbnails() {
        layers = layers.map { l ->
            if (l.bitmap.isRecycled) l else {
                val t = LayerCompositor.makeThumbnail(l.bitmap, 96)
                l.copy(thumbnail = t.asImageBitmap())
            }
        }
    }

    internal fun refreshLayerThumbnail(index: Int) {
        val l = layers.getOrNull(index) ?: return
        if (l.bitmap.isRecycled) return
        val t = LayerCompositor.makeThumbnail(l.bitmap, 96).asImageBitmap()
        layers = layers.toMutableList().apply { set(index, l.copy(thumbnail = t)) }
    }

    private fun refreshLayerThumbnailAsync(index: Int) {
        val target = layers.getOrNull(index) ?: return
        val layerId = target.id
        thumbJob?.cancel()
        thumbJob = viewModelScope.launch {
            delay(250)
            val curLayer = layers.firstOrNull { it.id == layerId } ?: return@launch
            if (curLayer.bitmap.isRecycled) return@launch
            val thumbBmp = withContext(Dispatchers.Default) {
                LayerCompositor.makeThumbnail(curLayer.bitmap, 96)
            }
            val img = thumbBmp.asImageBitmap()
            val curIdx = layers.indexOfFirst { it.id == layerId }
            if (curIdx >= 0) {
                layers = layers.toMutableList().apply { set(curIdx, layers[curIdx].copy(thumbnail = img)) }
            }
        }
    }

    private fun schedulePersistAfterStroke() {
        persistDebounceJob?.cancel()
        persistDebounceJob = viewModelScope.launch {
            delay(1500)
            persistCurrentArtwork(captureTimeLapse = true)
        }
    }

    fun persistCurrentArtwork(sync: Boolean = false, captureTimeLapse: Boolean = false) {
        val art = currentArtwork ?: return
        val comp = ensureCompositeBitmap() ?: return
        val elapsedSec = ((System.currentTimeMillis() - sessionStartMs) / 1000L).coerceAtLeast(0L)
        sessionStartMs = System.currentTimeMillis()
        val updatedMeta = art.copy(
            updatedAtMs = System.currentTimeMillis(),
            trackedSeconds = art.trackedSeconds + elapsedSec,
            backgroundRgb = backgroundRgb,
            backgroundVisible = backgroundVisible,
        )
        currentArtwork = updatedMeta
        val hasPrivate = layers.any { it.isPrivate }
        val effective = if (animationState.isAnimation) renderLayersCache else layers
        val tlBmp = if (captureTimeLapse && updatedMeta.timelapseEnabled && hasPrivate) {
            LayerCompositor.compositeForTimelapse(art.width, art.height, effective, backgroundRgb, backgroundVisible, animationState)
        } else null
        val layerSnapshots = layers.mapNotNull { l ->
            if (l.bitmap.isRecycled) null else l.copy(bitmap = l.bitmap.copy(Bitmap.Config.ARGB_8888, false))
        }
        val timelineSnapshot = timelineTracks.map { tr ->
            tr.copy(
                clips = tr.clips.map { cl ->
                    cl.copy(
                        bitmap = cl.bitmap?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false),
                    )
                },
            )
        }
        val animSnapshot = animationState
        val compCopy = comp.copy(Bitmap.Config.ARGB_8888, false)
        val saveBlock = {
            repo.saveArtwork(updatedMeta, layerSnapshots, compCopy)
            if (timelineSnapshot.isNotEmpty()) {
                repo.saveTimeline(updatedMeta.id, timelineSnapshot, animSnapshot)
            }
            if (captureTimeLapse && updatedMeta.timelapseEnabled) {
                repo.appendTimeLapseFrame(updatedMeta.id, tlBmp ?: compCopy)
            }
            tlBmp?.recycle()
            layerSnapshots.forEach { it.bitmap.recycle() }
            timelineSnapshot.forEach { tr -> tr.clips.forEach { it.bitmap?.recycle() } }
            compCopy.recycle()
        }
        if (sync) {
            saveBlock()
        } else {
            viewModelScope.launch(Dispatchers.IO) { saveBlock() }
        }
    }

    private fun commitAnyPendingTool() {
        if (adjustingKind != null) applyAdjustment()
        if (transforming) commitTransform()
        if (textEditing) commitTextLayer()
    }

    private fun clearTransients() {
        animationPlayJob?.cancel()
        audioEngine.stopPlayback()
        animationState = AnimationState()
        timelineClipHandlesId = null
        layerOrderPickerGroupId = null
        sideBarMoveMode = false
        panel = Panel.NONE
        selecting = false
        transforming = false
        adjustingKind = null
        adjustmentPaintMode = false
        cropping = false
        textEditing = false
        editingGuide = false
        fullscreen = false
        quickMenuCenter = null
        pickLoupe = null
        colorDroppingPos = null
        referenceVisible = false
        companionVisible = false
        timelapseReplayOpen = false
        gestureControlsOpen = false
        canvasInfoOpen = false
        copyPasteMenuOpen = false
        animatedExportOpen = false
        transformNumericOpen = false
        shortcutsHelpOpen = false
        heldLayerIds = emptySet()
        selectionOverlay?.recycle()
        selectionOverlay = null
        adjustBackupBitmap?.recycle()
        adjustBackupBitmap = null
        adjustFilteredBitmap?.recycle()
        adjustFilteredBitmap = null
        adjustBrushMask = null
        transformBackupBitmap?.recycle()
        transformBackupBitmap = null
        transformStaticBaseBitmap?.recycle()
        transformStaticBaseBitmap = null
        transformSelectionScopedBitmap?.recycle()
        transformSelectionScopedBitmap = null
        selectionAnchor = null
        anchorMoveMode = false
        inBetweenDialogOpen = false
        motionKeyframes.forEach { mk ->
            mk.sourceBitmap?.takeIf { !it.isRecycled }?.recycle()
            mk.baseBitmap?.takeIf { !it.isRecycled }?.recycle()
        }
        motionKeyframes = emptyList()
    }
}
