package io.github.artcraftandroid.procraft.engine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.artcraftandroid.procraft.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class LoadedTimelineData(
    val tracks: List<TimelineTrack>,
    val fps: Int,
    val totalFrames: Int,
    val audioScrubEnabled: Boolean,
    val timelineSnapping: Boolean = true,
)

class ArtworkRepository(private val context: Context) {
    private val rootDir = File(context.filesDir, "procraft").apply { mkdirs() }
    private val artworksDir = File(rootDir, "artworks").apply { mkdirs() }
    private val presetsFile = File(rootDir, "presets.json")
    private val palettesFile = File(rootDir, "palettes.json")
    private val settingsFile = File(rootDir, "settings.json")
    private val customBrushesFile = File(rootDir, "custom_brushes.json")
    private val brushTipsDir = File(rootDir, "brush_tips").apply { mkdirs() }

    fun brushTipsDir(): File = brushTipsDir

    fun artworkMediaDir(artworkId: String): File =
        File(File(artworksDir, artworkId), "media").apply { mkdirs() }

    fun listArtworks(): List<ArtworkRecord> {
        val list = mutableListOf<ArtworkRecord>()
        artworksDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val metaFile = File(dir, "meta.json")
            if (metaFile.isFile) {
                runCatching {
                    val obj = JSONObject(metaFile.readText())
                    val thumb = File(dir, "thumb.png").takeIf { it.isFile }?.absolutePath
                    list.add(
                        ArtworkRecord(
                            id = obj.getString("id"),
                            title = obj.optString("title", "Untitled Artwork"),
                            width = obj.optInt("width", 2048),
                            height = obj.optInt("height", 2048),
                            dpi = obj.optInt("dpi", 300),
                            colorProfile = obj.optString("colorProfile", "sRGB IEC61966-2.1"),
                            backgroundRgb = obj.optInt("backgroundRgb", Color.WHITE),
                            backgroundVisible = obj.optBoolean("backgroundVisible", true),
                            timelapseEnabled = obj.optBoolean("timelapseEnabled", false),
                            createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
                            updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis()),
                            totalStrokes = obj.optLong("totalStrokes", 0L),
                            trackedSeconds = obj.optLong("trackedSeconds", 0L),
                            stackId = obj.optString("stackId").takeIf { it.isNotBlank() && it != "null" },
                            stackName = obj.optString("stackName").takeIf { it.isNotBlank() && it != "null" },
                            order = obj.optInt("order", 0),
                            thumbnailPath = thumb,
                        ),
                    )
                }
            }
        }
        return list.sortedWith(compareBy<ArtworkRecord> { it.order }.thenByDescending { it.updatedAtMs })
    }

    fun seedDemoArtworkIfEmpty(): List<ArtworkRecord> {
        val current = listArtworks()
        if (current.isNotEmpty()) return current
        val id = UUID.randomUUID().toString()
        val meta = ArtworkRecord(
            id = id,
            title = "Welcome Studio",
            width = 1600,
            height = 1200,
            dpi = 300,
            backgroundRgb = 0xFFFDFBF7.toInt(),
            order = 0,
        )
        val layerBmp = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        val session = BrushStrokeSession(
            tool = ToolKind.PAINT,
            brush = BrushLibraryCatalog.defaultBrushSets()[3].brushes[1].settings.copy(size = 0.48f, opacity = 0.9f),
            colorRgb = 0xFF0A84FF.toInt(),
            canvasWidth = 1600,
            canvasHeight = 1200,
            alphaLock = false,
            guide = GuideConfig(),
            selectionMask = null,
            targetBitmap = layerBmp,
            baseLayerSnapshot = null,
        )
        session.start(StrokePoint(280f, 760f, 0.35f))
        for (i in 1..40) {
            val t = i / 40f
            val x = 280f + t * 1040f
            val y = 600f - kotlin.math.sin(t * Math.PI.toFloat() * 1.4f) * 260f
            val p = (0.3f + 0.7f * kotlin.math.sin(t * Math.PI.toFloat())).coerceIn(0.2f, 1f)
            session.move(StrokePoint(x, y, p))
        }
        session.end()

        val accentSession = BrushStrokeSession(
            tool = ToolKind.PAINT,
            brush = BrushLibraryCatalog.defaultBrushSets()[2].brushes[1].settings.copy(size = 0.34f, opacity = 0.92f),
            colorRgb = 0xFFFF6D42.toInt(),
            canvasWidth = 1600,
            canvasHeight = 1200,
            alphaLock = false,
            guide = GuideConfig(),
            selectionMask = null,
            targetBitmap = layerBmp,
            baseLayerSnapshot = null,
        )
        accentSession.start(StrokePoint(340f, 440f, 0.35f))
        for (i in 1..36) {
            val t = i / 36f
            val x = 340f + t * 920f
            val y = 520f + kotlin.math.cos(t * Math.PI.toFloat() * 1.6f) * 210f
            val p = (0.35f + 0.65f * kotlin.math.sin(t * Math.PI.toFloat())).coerceIn(0.2f, 1f)
            accentSession.move(StrokePoint(x, y, p))
        }
        accentSession.end()

        val layers = listOf(LayerState(id = UUID.randomUUID().toString(), name = "Layer 1", bitmap = layerBmp))
        val composite = LayerCompositor.composite(1600, 1200, layers, meta.backgroundRgb, true, AnimationState())
        saveArtwork(meta, layers, composite)
        composite.recycle()
        layerBmp.recycle()
        return listArtworks()
    }

    fun loadArtworkLayers(artworkId: String, width: Int, height: Int): List<LayerState> {
        val dir = File(artworksDir, artworkId)
        val layersFile = File(dir, "layers.json")
        if (!layersFile.isFile) {
            return listOf(LayerState(UUID.randomUUID().toString(), "Layer 1", bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)))
        }
        val arr = JSONArray(layersFile.readText())
        val out = mutableListOf<LayerState>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.getString("id")
            val imgFile = File(dir, "layer_$id.png")
            val bmp = if (imgFile.isFile) {
                BitmapFactory.decodeFile(imgFile.absolutePath)?.copy(Bitmap.Config.ARGB_8888, true)
                    ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } else {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            val mode = LayerBlendMode.entries.firstOrNull { it.name == obj.optString("blendMode") } ?: LayerBlendMode.NORMAL
            out.add(
                LayerState(
                    id = id,
                    name = obj.optString("name", "Layer ${i + 1}"),
                    visible = obj.optBoolean("visible", true),
                    opacity = obj.optDouble("opacity", 1.0).toFloat(),
                    blendMode = mode,
                    locked = obj.optBoolean("locked", false),
                    alphaLock = obj.optBoolean("alphaLock", false),
                    clippingMask = obj.optBoolean("clippingMask", false),
                    isMask = obj.optBoolean("isMask", false),
                    isReference = obj.optBoolean("isReference", false),
                    isPrivate = obj.optBoolean("isPrivate", false),
                    bitmap = bmp,
                ),
            )
        }
        return if (out.isEmpty()) listOf(LayerState(UUID.randomUUID().toString(), "Layer 1", bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))) else out
    }

    fun saveArtwork(meta: ArtworkRecord, layers: List<LayerState>, composite: Bitmap) {
        val dir = File(artworksDir, meta.id).apply { mkdirs() }
        writeMetaJson(dir, meta)

        val arr = JSONArray()
        layers.forEach { layer ->
            val obj = JSONObject().apply {
                put("id", layer.id)
                put("name", layer.name)
                put("visible", layer.visible)
                put("opacity", layer.opacity.toDouble())
                put("blendMode", layer.blendMode.name)
                put("locked", layer.locked)
                put("alphaLock", layer.alphaLock)
                put("clippingMask", layer.clippingMask)
                put("isMask", layer.isMask)
                put("isReference", layer.isReference)
                put("isPrivate", layer.isPrivate)
            }
            arr.put(obj)
            if (!layer.bitmap.isRecycled) {
                FileOutputStream(File(dir, "layer_${layer.id}.png")).use { fos ->
                    layer.bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
            }
        }
        File(dir, "layers.json").writeText(arr.toString())

        val thumb = LayerCompositor.makeThumbnail(composite, maxDim = 420)
        FileOutputStream(File(dir, "thumb.png")).use { fos ->
            thumb.compress(Bitmap.CompressFormat.PNG, 95, fos)
        }
        if (thumb !== composite) thumb.recycle()
    }

    fun saveTimeline(artworkId: String, tracks: List<TimelineTrack>, anim: AnimationState) {
        val dir = File(artworksDir, artworkId).apply { mkdirs() }
        val tlMediaDir = File(dir, "timeline_frames").apply { mkdirs() }
        val trArr = JSONArray()
        val liveKeys = HashSet<String>()

        tracks.forEach { tr ->
            val clipArr = JSONArray()
            tr.clips.forEach { clip ->
                val clipObj = JSONObject().apply {
                    put("id", clip.id)
                    put("startFrame", clip.startFrame)
                    put("durationFrames", clip.durationFrames)
                    put("label", clip.label)
                    put("sourceFrameOffset", clip.sourceFrameOffset)
                    put("mediaUri", clip.mediaUri)
                    put("audioFilePath", clip.audioFilePath)
                    put("audioSampleRate", clip.audioSampleRate)
                    put("videoFrameCount", clip.videoFrames.size)
                    put("orderTargetTrackId", clip.orderTargetTrackId)
                }
                clip.bitmap?.let { bmp ->
                    if (!bmp.isRecycled) {
                        val name = "kf_${clip.id}.png"
                        liveKeys.add(name)
                        FileOutputStream(File(tlMediaDir, name)).use { fos ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                        }
                    }
                }
                clip.videoFrames.forEachIndexed { idx, vb ->
                    if (!vb.isRecycled) {
                        val name = "vf_${clip.id}_$idx.png"
                        liveKeys.add(name)
                        FileOutputStream(File(tlMediaDir, name)).use { fos ->
                            vb.compress(Bitmap.CompressFormat.PNG, 92, fos)
                        }
                    }
                }
                clipArr.put(clipObj)
            }
            trArr.put(
                JSONObject().apply {
                    put("id", tr.id)
                    put("name", tr.name)
                    put("kind", tr.kind.name)
                    put("colorLabel", tr.colorLabel.name)
                    put("visible", tr.visible)
                    put("muted", tr.muted)
                    put("locked", tr.locked)
                    put("opacity", tr.opacity.toDouble())
                    put("isLayerOrderGroup", tr.isLayerOrderGroup)
                    put("layerId", tr.layerId)
                    put("groupId", tr.groupId)
                    put("expanded", tr.expanded)
                    put("clips", clipArr)
                },
            )
        }
        tlMediaDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name !in liveKeys) f.delete()
        }
        val rootObj = JSONObject().apply {
            put("fps", anim.fps)
            put("totalFrames", anim.totalFrames)
            put("audioScrubEnabled", anim.audioScrubEnabled)
            put("timelineSnapping", anim.timelineSnapping)
            put("tracks", trArr)
        }
        File(dir, "timeline.json").writeText(rootObj.toString())
    }

    fun loadTimeline(artworkId: String, width: Int, height: Int): LoadedTimelineData? {
        val dir = File(artworksDir, artworkId)
        val file = File(dir, "timeline.json")
        if (!file.isFile) return null
        val tlMediaDir = File(dir, "timeline_frames")
        return runCatching {
            val rootObj = JSONObject(file.readText())
            val fps = rootObj.optInt("fps", 12).coerceIn(1, 60)
            val totalFrames = rootObj.optInt("totalFrames", 24).coerceIn(1, 1200)
            val audioScrub = rootObj.optBoolean("audioScrubEnabled", true)
            val timelineSnapping = rootObj.optBoolean("timelineSnapping", true)
            val trArr = rootObj.optJSONArray("tracks") ?: JSONArray()
            val tracks = ArrayList<TimelineTrack>(trArr.length())

            for (i in 0 until trArr.length()) {
                val to = trArr.getJSONObject(i)
                val kind = TrackKind.entries.firstOrNull { it.name == to.optString("kind") } ?: TrackKind.PAINT
                val colorLabel = TrackColorLabel.entries.firstOrNull { it.name == to.optString("colorLabel") }
                    ?: TrackColorLabel.BLUE
                val clipsArr = to.optJSONArray("clips") ?: JSONArray()
                val clips = ArrayList<TimelineClip>(clipsArr.length())

                for (j in 0 until clipsArr.length()) {
                    val co = clipsArr.getJSONObject(j)
                    val cid = co.getString("id")
                    val bmp = if (kind == TrackKind.PAINT) {
                        val f = File(tlMediaDir, "kf_$cid.png")
                        if (f.isFile) {
                            BitmapFactory.decodeFile(f.absolutePath)?.copy(Bitmap.Config.ARGB_8888, true)
                                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        } else {
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        }
                    } else null

                    val vfCount = co.optInt("videoFrameCount", 0)
                    val videoFrames = if (kind == TrackKind.VIDEO && vfCount > 0) {
                        (0 until vfCount).mapNotNull { idx ->
                            val f = File(tlMediaDir, "vf_${cid}_$idx.png")
                            if (f.isFile) BitmapFactory.decodeFile(f.absolutePath) else null
                        }
                    } else emptyList()

                    val audioPath = co.optString("audioFilePath").takeIf { it.isNotBlank() && it != "null" }
                    val sr = co.optInt("audioSampleRate", MediaTimelineEngine.TARGET_SAMPLE_RATE)
                    val peaks = if (audioPath != null) {
                        MediaTimelineEngine.loadWaveformPeaksFromFile(File(audioPath), sr)
                    } else FloatArray(0)

                    clips.add(
                        TimelineClip(
                            id = cid,
                            startFrame = co.optInt("startFrame", 0),
                            durationFrames = co.optInt("durationFrames", 1).coerceAtLeast(1),
                            label = co.optString("label", ""),
                            bitmap = bmp,
                            videoFrames = videoFrames,
                            sourceFrameOffset = co.optInt("sourceFrameOffset", 0),
                            mediaUri = co.optString("mediaUri").takeIf { it.isNotBlank() && it != "null" },
                            audioFilePath = audioPath,
                            audioSampleRate = sr,
                            waveformPeaks = peaks,
                            orderTargetTrackId = co.optString("orderTargetTrackId").takeIf { it.isNotBlank() && it != "null" },
                        ),
                    )
                }

                tracks.add(
                    TimelineTrack(
                        id = to.getString("id"),
                        name = to.optString("name", "Track ${i + 1}"),
                        kind = kind,
                        colorLabel = colorLabel,
                        visible = to.optBoolean("visible", true),
                        muted = to.optBoolean("muted", false),
                        locked = to.optBoolean("locked", false),
                        opacity = to.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
                        isLayerOrderGroup = to.optBoolean("isLayerOrderGroup", false),
                        layerId = to.optString("layerId").takeIf { it.isNotBlank() && it != "null" },
                        groupId = to.optString("groupId").takeIf { it.isNotBlank() && it != "null" },
                        expanded = to.optBoolean("expanded", true),
                        clips = clips,
                    ),
                )
            }
            LoadedTimelineData(
                tracks = tracks,
                fps = fps,
                totalFrames = totalFrames,
                audioScrubEnabled = audioScrub,
                timelineSnapping = timelineSnapping,
            )
        }.getOrNull()
    }

    fun saveMetaOnly(meta: ArtworkRecord) {
        val dir = File(artworksDir, meta.id).apply { mkdirs() }
        writeMetaJson(dir, meta)
    }

    private fun writeMetaJson(dir: File, meta: ArtworkRecord) {
        val metaJson = JSONObject().apply {
            put("id", meta.id)
            put("title", meta.title)
            put("width", meta.width)
            put("height", meta.height)
            put("dpi", meta.dpi)
            put("colorProfile", meta.colorProfile)
            put("backgroundRgb", meta.backgroundRgb)
            put("backgroundVisible", meta.backgroundVisible)
            put("timelapseEnabled", meta.timelapseEnabled)
            put("createdAtMs", meta.createdAtMs)
            put("updatedAtMs", meta.updatedAtMs)
            put("totalStrokes", meta.totalStrokes)
            put("trackedSeconds", meta.trackedSeconds)
            put("stackId", meta.stackId)
            put("stackName", meta.stackName)
            put("order", meta.order)
        }
        File(dir, "meta.json").writeText(metaJson.toString())
    }

    fun duplicateArtwork(src: ArtworkRecord, newOrder: Int): ArtworkRecord {
        val newId = UUID.randomUUID().toString()
        val srcDir = File(artworksDir, src.id)
        val dstDir = File(artworksDir, newId).apply { mkdirs() }
        srcDir.listFiles()?.forEach { f ->
            if (f.isFile) f.copyTo(File(dstDir, f.name), overwrite = true)
        }
        val copy = src.copy(
            id = newId,
            title = "${src.title} Copy",
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis(),
            order = newOrder,
            thumbnailPath = File(dstDir, "thumb.png").takeIf { it.isFile }?.absolutePath,
        )
        saveMetaOnly(copy)
        return copy
    }

    fun deleteArtwork(id: String) {
        File(artworksDir, id).deleteRecursively()
    }

    fun appendTimeLapseFrame(artworkId: String, composite: Bitmap) {
        val tlDir = File(File(artworksDir, artworkId), "timelapse").apply { mkdirs() }
        val count = tlDir.listFiles()?.size ?: 0
        if (count >= 120) return
        val scaled = LayerCompositor.makeThumbnail(composite, maxDim = 640)
        val file = File(tlDir, "frame_${count.toString().padStart(4, '0')}.jpg")
        FileOutputStream(file).use { fos ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, fos)
        }
        if (scaled !== composite) scaled.recycle()
    }

    fun listTimeLapseFrames(artworkId: String): List<File> {
        val tlDir = File(File(artworksDir, artworkId), "timelapse")
        return tlDir.listFiles()?.filter { it.isFile && it.extension == "jpg" }?.sortedBy { it.name } ?: emptyList()
    }

    fun clearTimeLapseFrames(artworkId: String) {
        val tlDir = File(File(artworksDir, artworkId), "timelapse")
        if (tlDir.exists()) tlDir.deleteRecursively()
    }

    fun exportAndShare(title: String, format: String, bitmaps: List<Bitmap>) {
        if (bitmaps.isEmpty()) return
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = title.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "ProCraft_Artwork" }
        val (file, mime) = when (format.uppercase()) {
            "JPEG", "JPG" -> {
                val out = File(exportDir, "$safeName.jpg")
                FileOutputStream(out).use { fos -> bitmaps.first().compress(Bitmap.CompressFormat.JPEG, 94, fos) }
                out to "image/jpeg"
            }
            "PDF" -> {
                val out = File(exportDir, "$safeName.pdf")
                val pdf = PdfDocument()
                bitmaps.forEachIndexed { idx, bmp ->
                    val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, idx + 1).create()
                    val page = pdf.startPage(pageInfo)
                    page.canvas.drawBitmap(bmp, 0f, 0f, null)
                    pdf.finishPage(page)
                }
                FileOutputStream(out).use { fos -> pdf.writeTo(fos) }
                pdf.close()
                out to "application/pdf"
            }
            "SHEET" -> {
                val out = File(exportDir, "${safeName}_sheet.png")
                val sheet = AnimatedExporter.buildFilmstripBitmap(bitmaps) ?: bitmaps.first()
                FileOutputStream(out).use { fos -> sheet.compress(Bitmap.CompressFormat.PNG, 100, fos) }
                if (sheet !== bitmaps.first()) sheet.recycle()
                out to "image/png"
            }
            else -> {
                val out = File(exportDir, "$safeName.png")
                FileOutputStream(out).use { fos -> bitmaps.first().compress(Bitmap.CompressFormat.PNG, 100, fos) }
                out to "image/png"
            }
        }
        shareExportFile(file, mime)
    }

    fun exportAnimatedGifAndShare(
        title: String,
        frames: List<Bitmap>,
        fps: Int = 8,
        transparentBg: Boolean = false,
        maxDim: Int = 512,
    ) {
        if (frames.isEmpty()) return
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = title.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "ProCraft_Animation" }
        val out = File(exportDir, "$safeName.gif")
        FileOutputStream(out).use { fos ->
            AnimatedExporter.encodeGif(frames, fos, fps = fps, transparentBg = transparentBg, maxDim = maxDim)
        }
        shareExportFile(out, "image/gif")
    }

    fun exportMp4AndShare(
        title: String,
        frames: List<Bitmap>,
        fps: Int = 12,
        maxDim: Int = 1080,
    ): Boolean {
        if (frames.isEmpty()) return false
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = title.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "ProCraft_Video" }
        val out = File(exportDir, "$safeName.mp4")
        val ok = AnimatedExporter.encodeMp4(frames, out, fps = fps, maxDim = maxDim)
        if (ok) {
            shareExportFile(out, "video/mp4")
        }
        return ok
    }

    private fun shareExportFile(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share Artwork").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    fun decodeImageFromUri(uri: Uri, maxDim: Int = 2048): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)?.copy(Bitmap.Config.ARGB_8888, true)
            }
        }.getOrNull()
    }

    fun loadBrushSets(): List<BrushSet> {
        val defaults = BrushLibraryCatalog.defaultBrushSets()
        if (!customBrushesFile.isFile) return defaults
        return runCatching {
            val arr = JSONArray(customBrushesFile.readText())
            if (arr.length() == 0) return defaults
            List(arr.length()) { i ->
                val so = arr.getJSONObject(i)
                val setId = so.getString("id")
                val bArr = so.getJSONArray("brushes")
                val brushes = List(bArr.length()) { j ->
                    val bo = bArr.getJSONObject(j)
                    val st = bo.getJSONObject("settings")
                    BrushPreset(
                        id = bo.getString("id"),
                        name = bo.getString("name"),
                        setId = setId,
                        isCustom = bo.optBoolean("isCustom", false),
                        settings = BrushSettings(
                            size = st.optDouble("size", 0.28).toFloat(),
                            opacity = st.optDouble("opacity", 1.0).toFloat(),
                            spacing = st.optDouble("spacing", 0.12).toFloat(),
                            hardness = st.optDouble("hardness", 0.85).toFloat(),
                            streamline = st.optDouble("streamline", 0.35).toFloat(),
                            scatter = st.optDouble("scatter", 0.0).toFloat(),
                            jitter = st.optDouble("jitter", 0.0).toFloat(),
                            taperStart = st.optDouble("taperStart", 0.15).toFloat(),
                            taperEnd = st.optDouble("taperEnd", 0.25).toFloat(),
                            pressureSize = st.optDouble("pressureSize", 0.65).toFloat(),
                            pressureOpacity = st.optDouble("pressureOpacity", 0.2).toFloat(),
                            roundness = st.optDouble("roundness", 1.0).toFloat(),
                            angleDeg = st.optDouble("angleDeg", 0.0).toFloat(),
                            wetMix = st.optDouble("wetMix", 0.0).toFloat(),
                            grainAmount = st.optDouble("grainAmount", 0.0).toFloat(),
                            grainScale = st.optDouble("grainScale", 1.0).toFloat(),
                            grainMoving = st.optBoolean("grainMoving", false),
                            hueJitter = st.optDouble("hueJitter", 0.0).toFloat(),
                            saturationJitter = st.optDouble("saturationJitter", 0.0).toFloat(),
                            shapeKind = BrushShapeKind.entries.firstOrNull { it.name == st.optString("shapeKind") } ?: BrushShapeKind.ROUND,
                            quickShapeEnabled = st.optBoolean("quickShapeEnabled", true),
                            tipPath = st.optString("tipPath").takeIf { it.isNotBlank() && it != "null" },
                        ),
                    )
                }
                BrushSet(
                    id = setId,
                    name = so.getString("name"),
                    brushes = brushes,
                    isCustom = so.optBoolean("isCustom", false),
                )
            }
        }.getOrElse { defaults }
    }

    fun saveBrushSets(sets: List<BrushSet>) {
        val arr = JSONArray()
        sets.forEach { set ->
            val bArr = JSONArray()
            set.brushes.forEach { b ->
                val s = b.settings
                val stObj = JSONObject().apply {
                    put("size", s.size.toDouble())
                    put("opacity", s.opacity.toDouble())
                    put("spacing", s.spacing.toDouble())
                    put("hardness", s.hardness.toDouble())
                    put("streamline", s.streamline.toDouble())
                    put("scatter", s.scatter.toDouble())
                    put("jitter", s.jitter.toDouble())
                    put("taperStart", s.taperStart.toDouble())
                    put("taperEnd", s.taperEnd.toDouble())
                    put("pressureSize", s.pressureSize.toDouble())
                    put("pressureOpacity", s.pressureOpacity.toDouble())
                    put("roundness", s.roundness.toDouble())
                    put("angleDeg", s.angleDeg.toDouble())
                    put("wetMix", s.wetMix.toDouble())
                    put("grainAmount", s.grainAmount.toDouble())
                    put("grainScale", s.grainScale.toDouble())
                    put("grainMoving", s.grainMoving)
                    put("hueJitter", s.hueJitter.toDouble())
                    put("saturationJitter", s.saturationJitter.toDouble())
                    put("shapeKind", s.shapeKind.name)
                    put("quickShapeEnabled", s.quickShapeEnabled)
                    put("tipPath", s.tipPath)
                }
                bArr.put(
                    JSONObject().apply {
                        put("id", b.id)
                        put("name", b.name)
                        put("isCustom", b.isCustom)
                        put("settings", stObj)
                    },
                )
            }
            arr.put(
                JSONObject().apply {
                    put("id", set.id)
                    put("name", set.name)
                    put("isCustom", set.isCustom)
                    put("brushes", bArr)
                },
            )
        }
        customBrushesFile.writeText(arr.toString())
    }

    fun loadPresets(): List<CanvasPreset> {
        if (!presetsFile.isFile) return BrushLibraryCatalog.standardPresets()
        return runCatching {
            val arr = JSONArray(presetsFile.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                CanvasPreset(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    width = o.getDouble("width").toFloat(),
                    height = o.getDouble("height").toFloat(),
                    unit = CanvasUnit.entries.firstOrNull { it.name == o.optString("unit") } ?: CanvasUnit.PX,
                    dpi = o.optInt("dpi", 300),
                    colorProfile = o.optString("colorProfile", "sRGB IEC61966-2.1"),
                    backgroundRgb = o.optInt("backgroundRgb", Color.WHITE),
                    timelapseEnabled = o.optBoolean("timelapseEnabled", false),
                    isCustom = o.optBoolean("isCustom", false),
                )
            }
        }.getOrElse { BrushLibraryCatalog.standardPresets() }
    }

    fun savePresets(presets: List<CanvasPreset>) {
        val arr = JSONArray()
        presets.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("width", p.width.toDouble())
                    put("height", p.height.toDouble())
                    put("unit", p.unit.name)
                    put("dpi", p.dpi)
                    put("colorProfile", p.colorProfile)
                    put("backgroundRgb", p.backgroundRgb)
                    put("timelapseEnabled", p.timelapseEnabled)
                    put("isCustom", p.isCustom)
                },
            )
        }
        presetsFile.writeText(arr.toString())
    }

    fun loadPalettes(): List<ColorPalette> {
        if (!palettesFile.isFile) return BrushLibraryCatalog.defaultPalettes()
        return runCatching {
            val arr = JSONArray(palettesFile.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val swArr = o.getJSONArray("swatches")
                val swatches = List(swArr.length()) { j ->
                    if (swArr.isNull(j)) null else swArr.getInt(j)
                }
                ColorPalette(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    swatches = swatches,
                    isDefault = o.optBoolean("isDefault", false),
                )
            }
        }.getOrElse { BrushLibraryCatalog.defaultPalettes() }
    }

    fun savePalettes(palettes: List<ColorPalette>) {
        val arr = JSONArray()
        palettes.forEach { pal ->
            val swArr = JSONArray()
            pal.swatches.forEach { c -> if (c == null) swArr.put(JSONObject.NULL) else swArr.put(c) }
            arr.put(
                JSONObject().apply {
                    put("id", pal.id)
                    put("name", pal.name)
                    put("isDefault", pal.isDefault)
                    put("swatches", swArr)
                },
            )
        }
        palettesFile.writeText(arr.toString())
    }

    fun loadGestureSettings(): GestureSettings {
        if (!settingsFile.isFile) return GestureSettings()
        return runCatching {
            val o = JSONObject(settingsFile.readText())
            val slotArr = o.optJSONArray("quickMenuSlots")
            val slots = if (slotArr != null && slotArr.length() == 6) {
                List(6) { i ->
                    QuickAction.entries.firstOrNull { it.name == slotArr.optString(i) } ?: QuickAction.NEW_LAYER
                }
            } else {
                GestureSettings().quickMenuSlots
            }
            GestureSettings(
                quickMenuEnabled = o.optBoolean("quickMenuEnabled", true),
                quickMenuSlots = slots,
                eyedropperHoldMs = o.optInt("eyedropperHoldMs", 380),
                twoFingerUndo = o.optBoolean("twoFingerUndo", true),
                threeFingerRedo = o.optBoolean("threeFingerRedo", true),
                threeFingerClear = o.optBoolean("threeFingerClear", true),
                threeFingerCopyPaste = o.optBoolean("threeFingerCopyPaste", true),
                fourFingerFullscreen = o.optBoolean("fourFingerFullscreen", true),
                rightHanded = o.optBoolean("rightHanded", false),
                lightInterface = o.optBoolean("lightInterface", false),
                brushCursor = o.optBoolean("brushCursor", true),
                palmRejection = o.optBoolean("palmRejection", false),
                strokeSoundEnabled = o.optBoolean("strokeSoundEnabled", false),
                zoomHudEnabled = o.optBoolean("zoomHudEnabled", true),
                colorDropThreshold = o.optDouble("colorDropThreshold", 0.25).toFloat(),
                sideBarScale = o.optDouble("sideBarScale", 1.0).toFloat().coerceIn(0.45f, 1.0f),
                sideBarVerticalBias = o.optDouble("sideBarVerticalBias", 0.5).toFloat().coerceIn(0.05f, 0.95f),
            )
        }.getOrElse { GestureSettings() }
    }

    fun saveGestureSettings(s: GestureSettings) {
        val o = JSONObject().apply {
            put("quickMenuEnabled", s.quickMenuEnabled)
            put("quickMenuSlots", JSONArray(s.quickMenuSlots.map { it.name }))
            put("eyedropperHoldMs", s.eyedropperHoldMs)
            put("twoFingerUndo", s.twoFingerUndo)
            put("threeFingerRedo", s.threeFingerRedo)
            put("threeFingerClear", s.threeFingerClear)
            put("threeFingerCopyPaste", s.threeFingerCopyPaste)
            put("fourFingerFullscreen", s.fourFingerFullscreen)
            put("rightHanded", s.rightHanded)
            put("lightInterface", s.lightInterface)
            put("brushCursor", s.brushCursor)
            put("palmRejection", s.palmRejection)
            put("strokeSoundEnabled", s.strokeSoundEnabled)
            put("zoomHudEnabled", s.zoomHudEnabled)
            put("colorDropThreshold", s.colorDropThreshold.toDouble())
            put("sideBarScale", s.sideBarScale.toDouble())
            put("sideBarVerticalBias", s.sideBarVerticalBias.toDouble())
        }
        settingsFile.writeText(o.toString())
    }
}
