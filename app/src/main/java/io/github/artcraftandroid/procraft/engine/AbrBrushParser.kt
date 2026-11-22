package io.github.artcraftandroid.procraft.engine

import android.graphics.Bitmap
import android.graphics.Color
import io.github.artcraftandroid.procraft.model.BrushPreset
import io.github.artcraftandroid.procraft.model.BrushSettings
import io.github.artcraftandroid.procraft.model.BrushShapeKind
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

object AbrBrushParser {

    fun parseAbr(
        bytes: ByteArray,
        setId: String,
        tipsOutputDir: File,
        fallbackBaseName: String = "ABR Brush",
    ): List<BrushPreset> {
        if (bytes.size < 4) return emptyList()
        tipsOutputDir.mkdirs()

        val reader = BigEndianReader(bytes)
        val version = reader.readU16()
        val subOrCount = reader.readU16()

        return when (version) {
            1, 2 -> parseAbrV12(reader, version, subOrCount, setId, tipsOutputDir, fallbackBaseName)
            6, 7, 8, 9, 10 -> parseAbrV6Plus(bytes, subOrCount, setId, tipsOutputDir, fallbackBaseName)
            else -> emptyList()
        }
    }

    private fun parseAbrV12(
        r: BigEndianReader,
        version: Int,
        count: Int,
        setId: String,
        tipsDir: File,
        baseName: String,
    ): List<BrushPreset> {
        val out = mutableListOf<BrushPreset>()
        val safeCount = count.coerceIn(0, 512)
        for (idx in 0 until safeCount) {
            if (r.remaining() < 6) break
            val type = r.readU16()
            val blockSize = r.readI32()
            if (blockSize <= 0 || blockSize > r.remaining()) break
            val blockEnd = r.pos + blockSize

            when (type) {
                1 -> {
                    // Computed brush
                    if (r.remaining() >= 6) {
                        r.skip(4) // misc
                        val spacing = r.readU16()
                        val name = if (version == 2) r.readPhotoshopUnicodeString() else ""
                        if (r.pos + 8 <= blockEnd) {
                            val diameter = r.readU16().coerceIn(4, 2500)
                            val roundness = (r.readU16() / 100f).coerceIn(0.15f, 1f)
                            val angle = normalizeAngle90(r.readI16().toFloat())
                            val hardness = (r.readU16() / 100f).coerceIn(0.05f, 1f)
                            val presetName = name.ifBlank { "$baseName ${out.size + 1}" }
                            out.add(
                                BrushPreset(
                                    id = UUID.randomUUID().toString(),
                                    name = presetName.take(40),
                                    setId = setId,
                                    isCustom = true,
                                    settings = BrushSettings(
                                        size = mapDiameterToSize(diameter),
                                        opacity = 1f,
                                        spacing = (spacing / 100f).coerceIn(0.02f, 0.85f),
                                        hardness = hardness,
                                        roundness = roundness,
                                        angleDeg = angle,
                                        shapeKind = inferShapeKind(roundness, hardness, 0f),
                                    ),
                                ),
                            )
                        }
                    }
                }
                2 -> {
                    // Sampled brush
                    if (r.remaining() >= 6) {
                        r.skip(4) // misc
                        val spacing = r.readU16()
                        val name = if (version == 2) r.readPhotoshopUnicodeString() else ""
                        if (r.pos < blockEnd) r.readU8() // antialias flag
                        if (r.pos + 16 <= blockEnd) {
                            val savedPos = r.pos
                            var tipFile: File? = null
                            var sampledWidth = 180
                            if (r.pos + 19 <= blockEnd) {
                                r.skip(8) // short bounds + misc
                                val top = r.readI32()
                                val left = r.readI32()
                                val bottom = r.readI32()
                                val right = r.readI32()
                                val w = right - left
                                val h = bottom - top
                                val depth = if (r.pos + 2 <= blockEnd) r.readU16() else 8
                                val compression = if (r.pos < blockEnd) r.readU8() else 0
                                if (w in 2..4096 && h in 2..4096 && (depth == 8 || depth == 16) && compression in 0..1) {
                                    sampledWidth = w
                                    tipFile = decodeAndSaveSampledTip(
                                        r = r,
                                        limit = blockEnd,
                                        width = w,
                                        height = h,
                                        depth = depth,
                                        compression = compression,
                                        tipsDir = tipsDir,
                                    )
                                }
                            }
                            if (tipFile == null) {
                                r.pos = savedPos
                            }
                            val presetName = name.ifBlank { "$baseName ${out.size + 1}" }
                            out.add(
                                BrushPreset(
                                    id = UUID.randomUUID().toString(),
                                    name = presetName.take(40),
                                    setId = setId,
                                    isCustom = true,
                                    settings = BrushSettings(
                                        size = mapDiameterToSize(sampledWidth),
                                        opacity = 1f,
                                        spacing = (spacing / 100f).coerceIn(0.02f, 0.85f),
                                        hardness = 0.90f,
                                        shapeKind = BrushShapeKind.ROUND,
                                        tipPath = tipFile?.absolutePath,
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
            r.pos = blockEnd
        }
        return out
    }

    private data class DecodedSampleEntry(
        val sampleId: String?,
        val tipFile: File?,
        val widthPx: Int = 0,
    )

    private data class AbrParsedPreset(
        val name: String,
        val sampledDataId: String?,
        val isComputed: Boolean,
        val settings: BrushSettings,
    )

    private fun parseAbrV6Plus(
        bytes: ByteArray,
        subversion: Int,
        setId: String,
        tipsDir: File,
        baseName: String,
    ): List<BrushPreset> {
        val blocks = find8BimBlocks(bytes, startOffset = 4)
        val sampledEntries = mutableListOf<DecodedSampleEntry>()
        val parsedPresets = mutableListOf<AbrParsedPreset>()
        val fallbackNames = mutableListOf<String>()

        for ((tag, dataStart, dataEnd) in blocks) {
            when (tag) {
                "samp" -> {
                    val r = BigEndianReader(bytes, dataStart, dataEnd)
                    while (r.remaining() >= 4) {
                        val brushLen = r.readI32()
                        if (brushLen <= 0 || brushLen > r.remaining()) break
                        val brushStart = r.pos
                        val brushEnd = brushStart + brushLen
                        val paddedEnd = min(dataEnd, brushStart + ((brushLen + 3) and 3.inv()))

                        val sampleId = extractSampledBrushId(bytes, brushStart, brushEnd)
                        val decoded = tryDecodeV6SampledBrush(r, bytes, brushStart, brushEnd, subversion, tipsDir)
                        sampledEntries.add(
                            DecodedSampleEntry(
                                sampleId = sampleId,
                                tipFile = decoded?.first,
                                widthPx = decoded?.second ?: 0,
                            ),
                        )
                        r.pos = paddedEnd
                    }
                }
                "desc" -> {
                    val structured = parseDescriptorPresets(bytes, dataStart, dataEnd)
                    if (structured.isNotEmpty()) {
                        parsedPresets.addAll(structured)
                    } else {
                        extractDescriptorNames(bytes, dataStart, dataEnd, fallbackNames)
                    }
                }
            }
        }

        val out = mutableListOf<BrushPreset>()
        if (parsedPresets.isNotEmpty()) {
            val sampleById = mutableMapOf<String, DecodedSampleEntry>()
            sampledEntries.forEach { entry ->
                val sid = entry.sampleId?.trim()?.lowercase()
                if (!sid.isNullOrBlank() && entry.tipFile != null && !sampleById.containsKey(sid)) {
                    sampleById[sid] = entry
                }
            }
            var fallbackSampleCursor = 0
            val usedSamples = HashSet<DecodedSampleEntry>()

            parsedPresets.forEachIndexed { idx, preset ->
                var matchedTip: DecodedSampleEntry? = null
                val wantedId = preset.sampledDataId?.trim()?.lowercase()
                if (!wantedId.isNullOrBlank()) {
                    matchedTip = sampleById[wantedId]
                }
                if (matchedTip == null && !preset.isComputed) {
                    while (fallbackSampleCursor < sampledEntries.size) {
                        val candidate = sampledEntries[fallbackSampleCursor++]
                        if (candidate !in usedSamples && candidate.tipFile != null) {
                            matchedTip = candidate
                            break
                        }
                    }
                }
                if (matchedTip != null) {
                    usedSamples.add(matchedTip)
                }

                val tipPath = matchedTip?.tipFile?.absolutePath
                val finalSize = if (preset.settings.size <= 0f && matchedTip != null && matchedTip.widthPx > 0) {
                    mapDiameterToSize(matchedTip.widthPx)
                } else {
                    preset.settings.size.coerceIn(0.04f, 0.9f)
                }

                out.add(
                    BrushPreset(
                        id = UUID.randomUUID().toString(),
                        name = preset.name.ifBlank { "$baseName ${idx + 1}" }.take(40),
                        setId = setId,
                        isCustom = true,
                        settings = preset.settings.copy(
                            size = finalSize,
                            tipPath = tipPath,
                        ),
                    ),
                )
            }
            return out
        }

        // Fallback if 'desc' ActionDescriptor tree could not be parsed cleanly
        if (sampledEntries.isNotEmpty()) {
            sampledEntries.forEachIndexed { idx, entry ->
                val rawName = fallbackNames.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: "$baseName ${idx + 1}"
                out.add(
                    BrushPreset(
                        id = UUID.randomUUID().toString(),
                        name = rawName.take(40),
                        setId = setId,
                        isCustom = true,
                        settings = BrushSettings(
                            size = if (entry.widthPx > 0) mapDiameterToSize(entry.widthPx) else 0.34f,
                            opacity = 1f,
                            spacing = 0.12f,
                            hardness = 0.9f,
                            shapeKind = BrushShapeKind.ROUND,
                            tipPath = entry.tipFile?.absolutePath,
                        ),
                    ),
                )
            }
        } else if (fallbackNames.isNotEmpty()) {
            fallbackNames.distinct().take(96).forEachIndexed { idx, nm ->
                out.add(
                    BrushPreset(
                        id = UUID.randomUUID().toString(),
                        name = nm.ifBlank { "$baseName ${idx + 1}" }.take(40),
                        setId = setId,
                        isCustom = true,
                        settings = BrushSettings(
                            size = 0.30f,
                            opacity = 1f,
                            spacing = 0.10f,
                            hardness = 0.82f,
                            shapeKind = BrushShapeKind.ROUND,
                        ),
                    ),
                )
            }
        }
        return out
    }

    private fun extractSampledBrushId(bytes: ByteArray, brushStart: Int, brushEnd: Int): String? {
        if (brushStart >= brushEnd) return null
        val len = bytes[brushStart].toInt() and 0xFF
        if (len in 4..96 && brushStart + 1 + len <= brushEnd) {
            val raw = String(bytes, brushStart + 1, len, Charsets.US_ASCII).trim('\u0000', ' ')
            if (raw.isNotEmpty() && raw.all { it.code in 32..126 }) {
                return raw
            }
        }
        // Fallback: check UTF-16 or raw GUID ASCII at brushStart
        val maxScan = min(brushEnd, brushStart + 64)
        val sb = StringBuilder()
        for (i in brushStart until maxScan) {
            val ch = bytes[i].toInt() and 0xFF
            if (ch in 45..122 && (ch in 48..57 || ch in 65..90 || ch in 97..122 || ch == 45 || ch == 95)) {
                sb.append(ch.toChar())
            } else if (sb.length >= 16) {
                break
            } else {
                sb.clear()
            }
        }
        return sb.toString().takeIf { it.length >= 16 }
    }

    private fun tryDecodeV6SampledBrush(
        r: BigEndianReader,
        bytes: ByteArray,
        brushStart: Int,
        brushEnd: Int,
        subversion: Int,
        tipsDir: File,
    ): Pair<File, Int>? {
        val pascalLen = if (brushStart < brushEnd) (bytes[brushStart].toInt() and 0xFF) else 36
        val dynamicBase = if (pascalLen in 4..96) 1 + pascalLen else 37

        val candidateHeaderOffsets = buildList {
            if (subversion == 1) {
                add(dynamicBase + 10)
                add(dynamicBase)
                add(dynamicBase + 9)
                add(dynamicBase + 11)
            } else {
                add(dynamicBase + 264)
                add(dynamicBase + 263)
                add(dynamicBase + 265)
                add(dynamicBase + 10)
                add(dynamicBase + 227)
                add(dynamicBase)
            }
            addAll(listOf(301, 47, 300, 46, 264, 37, 302, 48))
        }.distinct()

        for (off in candidateHeaderOffsets) {
            val headerPos = brushStart + off
            if (headerPos + 19 > brushEnd) continue
            r.pos = headerPos
            val top = r.readI32()
            val left = r.readI32()
            val bottom = r.readI32()
            val right = r.readI32()
            val w = right - left
            val h = bottom - top
            val depth = r.readU16()
            val compression = r.readU8()
            if (w in 2..4096 && h in 2..4096 && (depth == 8 || depth == 16) && compression in 0..1) {
                val decoded = decodeAndSaveSampledTip(r, brushEnd, w, h, depth, compression, tipsDir)
                if (decoded != null) return decoded to maxOf(w, h)
            }
        }
        return null
    }

    // --- Photoshop ActionDescriptor Binary Parser for ABR v6-v10 ---

    private sealed interface DescVal {
        data class Obj(val classId: String, val items: Map<String, DescVal>) : DescVal
        data class Arr(val items: List<DescVal>) : DescVal
        data class Num(val value: Double, val unit: String = "") : DescVal
        data class Str(val value: String) : DescVal
        data class BoolVal(val value: Boolean) : DescVal
        data class IntVal(val value: Int) : DescVal
        data class EnumVal(val typeId: String, val valueId: String) : DescVal
        data object RawSkip : DescVal
    }

    private fun DescVal.Obj.findObj(vararg keys: String): DescVal.Obj? {
        for (k in keys) {
            val v = items[k] ?: items.entries.firstOrNull { it.key.trim().equals(k.trim(), ignoreCase = true) }?.value
            if (v is DescVal.Obj) return v
        }
        return null
    }

    private fun DescVal.Obj.findArr(vararg keys: String): DescVal.Arr? {
        for (k in keys) {
            val v = items[k] ?: items.entries.firstOrNull { it.key.trim().equals(k.trim(), ignoreCase = true) }?.value
            if (v is DescVal.Arr) return v
        }
        return null
    }

    private fun DescVal.Obj.findNum(vararg keys: String): Float? {
        for (k in keys) {
            val v = items[k] ?: items.entries.firstOrNull { it.key.trim().equals(k.trim(), ignoreCase = true) }?.value
            when (v) {
                is DescVal.Num -> return v.value.toFloat()
                is DescVal.IntVal -> return v.value.toFloat()
                else -> Unit
            }
        }
        return null
    }

    private fun DescVal.Obj.findInt(vararg keys: String): Int? {
        for (k in keys) {
            val v = items[k] ?: items.entries.firstOrNull { it.key.trim().equals(k.trim(), ignoreCase = true) }?.value
            when (v) {
                is DescVal.IntVal -> return v.value
                is DescVal.Num -> return v.value.roundToInt()
                else -> Unit
            }
        }
        return null
    }

    private fun DescVal.Obj.findBool(vararg keys: String): Boolean? {
        for (k in keys) {
            val v = items[k] ?: items.entries.firstOrNull { it.key.trim().equals(k.trim(), ignoreCase = true) }?.value
            if (v is DescVal.BoolVal) return v.value
        }
        return null
    }

    private fun DescVal.Obj.findText(vararg keys: String): String? {
        for (k in keys) {
            val v = items[k] ?: items.entries.firstOrNull { it.key.trim().equals(k.trim(), ignoreCase = true) }?.value
            if (v is DescVal.Str && v.value.isNotBlank()) return v.value
        }
        return null
    }

    private fun parseDescriptorPresets(bytes: ByteArray, start: Int, end: Int): List<AbrParsedPreset> {
        val rootObj = runCatching {
            val r = BigEndianReader(bytes, start, end)
            if (r.remaining() >= 4) {
                val ver = r.readI32()
                if (ver != 16) {
                    r.pos = start
                }
            }
            readDescriptorObj(r)
        }.getOrNull() ?: return emptyList()

        val brushList = rootObj.findArr("Brsh", "brsh", "preset")
            ?: rootObj.items.values.filterIsInstance<DescVal.Arr>().firstOrNull { arr ->
                arr.items.any { it is DescVal.Obj }
            }
            ?: return emptyList()

        return brushList.items.mapIndexedNotNull { idx, item ->
            val presetObj = item as? DescVal.Obj ?: return@mapIndexedNotNull null
            convertDescriptorToPreset(presetObj, idx)
        }
    }

    private fun convertDescriptorToPreset(preset: DescVal.Obj, idx: Int): AbrParsedPreset {
        val name = preset.findText("Nm  ", "Name", "name") ?: "Brush ${idx + 1}"
        val brsh = preset.findObj("Brsh", "brsh")
        val toolOpts = preset.findObj("toolOptions", "TlOp", "ToolOptions")

        val brshClass = brsh?.classId.orEmpty()
        val isComputed = brshClass.contains("computed", ignoreCase = true)
        val sampledDataId = brsh?.findText("sampledData", "idnt", "Smpl")

        // Tip Geometry from ABR
        val diameterPx = (brsh?.findNum("Dmtr") ?: preset.findNum("Dmtr"))?.takeIf { it > 0f }
        val spacingEnabled = brsh?.findBool("Intr") ?: true
        val rawSpacingPct = brsh?.findNum("Spcn") ?: preset.findNum("Spcn")
        val spacing = if (!spacingEnabled) {
            0.03f
        } else if (rawSpacingPct != null) {
            (rawSpacingPct / 100f).coerceIn(0.02f, 0.85f)
        } else {
            0.12f
        }
        val hardness = ((brsh?.findNum("Hrdn") ?: preset.findNum("Hrdn"))?.div(100f) ?: if (isComputed) 0.82f else 0.92f)
            .coerceIn(0.05f, 1f)
        val roundness = ((brsh?.findNum("Rndn") ?: preset.findNum("Rndn"))?.div(100f) ?: 1f)
            .coerceIn(0.15f, 1f)
        val angleDeg = normalizeAngle90(brsh?.findNum("Angl") ?: preset.findNum("Angl") ?: 0f)

        // Tool Opacity & Flow
        val opctPct = toolOpts?.findNum("Opct") ?: preset.findNum("Opct")
        val flowPct = toolOpts?.findNum("flow", "Flw ") ?: preset.findNum("flow", "Flw ")
        val opacity = when {
            opctPct != null -> (opctPct / 100f).coerceIn(0.05f, 1f)
            flowPct != null -> (flowPct / 100f).coerceIn(0.15f, 1f)
            else -> 1f
        }

        // Tip Dynamics (Size Jitter & Pressure Size)
        val szVr = preset.findObj("szVr", "SzVr")
        val useTipDyn = preset.findBool("useTipDynamics", "UsTD") ?: (szVr != null)
        val sizeJitter = if (useTipDyn) ((szVr?.findNum("jtr ", "Jtr ") ?: 0f) / 100f).coerceIn(0f, 1f) else 0f
        val sizeCtrl = szVr?.findInt("bVTy", "BVTy") ?: 0
        val sizeMinPct = szVr?.findNum("MnmS", "mnmS")
        val pressureSize = if (useTipDyn && (sizeCtrl == 2 || sizeCtrl == 1 || sizeMinPct != null && sizeCtrl != 0)) {
            val minFrac = (sizeMinPct ?: 0f) / 100f
            (1f - minFrac).coerceIn(0.15f, 1f)
        } else {
            0f
        }

        // Scattering
        val useScatter = preset.findBool("useScatter", "UsSc") ?: (preset.findNum("Sctr") != null)
        val scatterPct = if (useScatter) (preset.findNum("Sctr") ?: preset.findObj("scatterDynamics")?.findNum("Sctr") ?: 0f) else 0f
        val scatter = (scatterPct / 250f).coerceIn(0f, 1f)

        // Transfer / Other Dynamics (Opacity Pressure)
        val opVr = preset.findObj("opVr", "OpVr")
        val useTransfer = preset.findBool("usePaintDynamics", "UsPD") ?: (opVr != null)
        val opCtrl = opVr?.findInt("bVTy", "BVTy") ?: 0
        val opMinPct = opVr?.findNum("MnmS", "mnmS")
        val pressureOpacity = if (useTransfer && (opCtrl == 2 || (opCtrl != 0 && opMinPct != null))) {
            (1f - (opMinPct ?: 0f) / 100f).coerceIn(0.10f, 1f)
        } else {
            0f
        }

        // Color Dynamics
        val useColorDyn = preset.findBool("useColorDynamics", "UsCD") ?: false
        val hueJitter = if (useColorDyn) {
            ((preset.findNum("hueJ", "HueJ") ?: preset.findObj("clVr")?.findNum("jtr ") ?: 0f) / 100f).coerceIn(0f, 1f)
        } else 0f
        val satJitter = if (useColorDyn) {
            ((preset.findNum("satJ", "SatJ") ?: 0f) / 100f).coerceIn(0f, 1f)
        } else 0f

        // Texture / Grain
        val useTexture = preset.findBool("useTexture", "UsTx") ?: false
        val grainAmount = if (useTexture) {
            ((preset.findNum("txtrDepth", "Dpth") ?: 40f) / 100f).coerceIn(0f, 1f)
        } else 0f
        val grainScale = if (useTexture) {
            ((preset.findNum("Scl ", "txtrScale") ?: 100f) / 100f).coerceIn(0.3f, 3f)
        } else 1f
        val grainMoving = if (useTexture) (preset.findBool("txtrEachTip") ?: true) else true

        // Smoothing -> Streamline
        val smoothBool = preset.findBool("useSmoothing", "Smth") ?: toolOpts?.findBool("Smth") ?: false
        val smoothPct = preset.findNum("Smth") ?: toolOpts?.findNum("Smth")
        val streamline = when {
            smoothPct != null -> (smoothPct / 100f).coerceIn(0f, 0.9f)
            smoothBool -> 0.42f
            else -> 0f
        }

        // Wet Edges / Dual Brush hint
        val wetEdges = preset.findBool("Wtdg", "useWetEdges") ?: false

        val sizeNorm = diameterPx?.let { mapDiameterToSize(it.roundToInt()) } ?: if (isComputed) 0.30f else 0f
        val shapeKind = inferShapeKind(roundness, hardness, scatter, grainAmount)

        return AbrParsedPreset(
            name = name,
            sampledDataId = sampledDataId,
            isComputed = isComputed,
            settings = BrushSettings(
                size = sizeNorm,
                opacity = opacity,
                spacing = spacing,
                streamline = streamline,
                hardness = hardness,
                roundness = roundness,
                angleDeg = angleDeg,
                scatter = scatter,
                jitter = sizeJitter,
                grainAmount = grainAmount,
                grainScale = grainScale,
                grainMoving = grainMoving,
                wetMix = if (wetEdges) 0.35f else 0f,
                hueJitter = hueJitter,
                saturationJitter = satJitter,
                pressureSize = pressureSize,
                pressureOpacity = pressureOpacity,
                shapeKind = shapeKind,
            ),
        )
    }

    private fun readDescriptorObj(r: BigEndianReader): DescVal.Obj? {
        if (r.remaining() < 12) return null
        r.readPhotoshopUnicodeString() // descriptor name
        val classId = readKeyOrClassId(r) ?: return null
        if (r.remaining() < 4) return null
        val itemCount = r.readI32()
        if (itemCount !in 0..4096) return null
        val map = LinkedHashMap<String, DescVal>(itemCount.coerceAtMost(64))
        for (i in 0 until itemCount) {
            val key = readKeyOrClassId(r) ?: break
            val valObj = readDescriptorValue(r) ?: break
            map[key] = valObj
        }
        return DescVal.Obj(classId, map)
    }

    private fun readDescriptorValue(r: BigEndianReader): DescVal? {
        if (r.remaining() < 4) return null
        val type = r.readAscii4()
        return when (type) {
            "Objc", "GlbO" -> readDescriptorObj(r)
            "VlLs" -> {
                if (r.remaining() < 4) return null
                val count = r.readI32()
                if (count !in 0..8192) return null
                val list = ArrayList<DescVal>(count.coerceAtMost(128))
                for (i in 0 until count) {
                    val v = readDescriptorValue(r) ?: break
                    list.add(v)
                }
                DescVal.Arr(list)
            }
            "doub" -> {
                if (r.remaining() < 8) return null
                DescVal.Num(r.readF64())
            }
            "UntF" -> {
                if (r.remaining() < 12) return null
                val unit = r.readAscii4()
                val d = r.readF64()
                DescVal.Num(d, unit)
            }
            "TEXT" -> {
                DescVal.Str(r.readPhotoshopUnicodeString())
            }
            "enum" -> {
                val typeId = readKeyOrClassId(r) ?: return null
                val enumVal = readKeyOrClassId(r) ?: return null
                DescVal.EnumVal(typeId, enumVal)
            }
            "bool" -> {
                if (r.remaining() < 1) return null
                DescVal.BoolVal(r.readU8() != 0)
            }
            "long" -> {
                if (r.remaining() < 4) return null
                DescVal.IntVal(r.readI32())
            }
            "comp", "LMNi" -> {
                if (r.remaining() < 8) return null
                val hi = r.readI32()
                val lo = r.readI32()
                DescVal.IntVal(lo.takeIf { hi == 0 } ?: hi)
            }
            "tdta", "alis", "pth ", "Pth ", "raw " -> {
                if (r.remaining() < 4) return null
                val byteLen = r.readI32()
                if (byteLen < 0 || byteLen > r.remaining()) return null
                r.skip(byteLen)
                DescVal.RawSkip
            }
            "ObAr" -> {
                if (r.remaining() < 8) return null
                r.readI32() // version
                r.readPhotoshopUnicodeString()
                readKeyOrClassId(r) ?: return null
                if (r.remaining() < 4) return null
                val count = r.readI32()
                if (count !in 0..4096) return null
                for (i in 0 until count) {
                    readKeyOrClassId(r) ?: return null
                    if (r.remaining() < 4) return null
                    val itemType = r.readAscii4()
                    if (itemType == "UnFl") {
                        if (r.remaining() < 8) return null
                        r.readAscii4() // unit
                        val fCount = r.readI32()
                        if (fCount < 0 || fCount * 8 > r.remaining()) return null
                        r.skip(fCount * 8)
                    } else {
                        readDescriptorValueOfType(r, itemType) ?: return null
                    }
                }
                DescVal.RawSkip
            }
            "obj " -> {
                // Reference list: read itemCount and skip cleanly if small
                if (r.remaining() < 4) return null
                val count = r.readI32()
                if (count !in 0..128) return null
                for (i in 0 until count) {
                    if (r.remaining() < 4) return null
                    val refType = r.readAscii4()
                    r.readPhotoshopUnicodeString()
                    readKeyOrClassId(r) ?: return null
                    when (refType) {
                        "prop" -> readKeyOrClassId(r) ?: return null
                        "Clss" -> Unit
                        "Enmr" -> {
                            readKeyOrClassId(r) ?: return null
                            readKeyOrClassId(r) ?: return null
                        }
                        "rele" -> if (r.remaining() >= 4) r.skip(4) else return null
                        "Idnt", "indx" -> if (r.remaining() >= 4) r.skip(4) else return null
                        "name" -> r.readPhotoshopUnicodeString()
                        else -> return null
                    }
                }
                DescVal.RawSkip
            }
            else -> null
        }
    }

    private fun readDescriptorValueOfType(r: BigEndianReader, type: String): DescVal? {
        return when (type) {
            "doub" -> if (r.remaining() >= 8) DescVal.Num(r.readF64()) else null
            "UntF" -> if (r.remaining() >= 12) DescVal.Num(unit = r.readAscii4(), value = r.readF64()) else null
            "long" -> if (r.remaining() >= 4) DescVal.IntVal(r.readI32()) else null
            "bool" -> if (r.remaining() >= 1) DescVal.BoolVal(r.readU8() != 0) else null
            "TEXT" -> DescVal.Str(r.readPhotoshopUnicodeString())
            else -> null
        }
    }

    private fun readKeyOrClassId(r: BigEndianReader): String? {
        if (r.remaining() < 4) return null
        val len = r.readI32()
        val actualLen = if (len == 0) 4 else len
        if (actualLen !in 1..256 || actualLen > r.remaining()) return null
        return r.readAscii(actualLen)
    }

    private fun decodeAndSaveSampledTip(
        r: BigEndianReader,
        limit: Int,
        width: Int,
        height: Int,
        depth: Int,
        compression: Int,
        tipsDir: File,
    ): File? {
        if (width <= 0 || height <= 0 || width.toLong() * height.toLong() > 16_777_216L) return null
        val bytesPerSample = if (depth == 16) 2 else 1
        val alphaBytes = ByteArray(width * height)

        if (compression == 0) {
            val rowBytes = width * bytesPerSample
            for (y in 0 until height) {
                if (r.pos + rowBytes > limit) return null
                val rowOff = y * width
                if (bytesPerSample == 1) {
                    r.readBytes(alphaBytes, rowOff, width)
                } else {
                    for (x in 0 until width) {
                        val hi = r.readU8()
                        r.skip(1)
                        alphaBytes[rowOff + x] = hi.toByte()
                    }
                }
            }
        } else {
            val rowCountsPos = r.pos
            if (rowCountsPos + height * 2 > limit) return null
            val rowCounts = IntArray(height)
            for (y in 0 until height) {
                rowCounts[y] = r.readU16()
            }
            val expectedRowPixels = width * bytesPerSample
            val rowScratch = ByteArray(expectedRowPixels)
            for (y in 0 until height) {
                val packedLen = rowCounts[y]
                if (packedLen < 0 || r.pos + packedLen > limit) return null
                val rowLimit = r.pos + packedLen
                var outIdx = 0
                while (r.pos < rowLimit && outIdx < expectedRowPixels) {
                    val hdr = r.readI8()
                    if (hdr in 0..127) {
                        val count = hdr + 1
                        val copyCount = min(count, min(rowLimit - r.pos, expectedRowPixels - outIdx))
                        if (copyCount <= 0) break
                        r.readBytes(rowScratch, outIdx, copyCount)
                        outIdx += copyCount
                    } else if (hdr in -127..-1) {
                        val count = 1 - hdr
                        if (r.pos >= rowLimit) break
                        val valByte = r.readU8().toByte()
                        val fillCount = min(count, expectedRowPixels - outIdx)
                        for (k in 0 until fillCount) {
                            rowScratch[outIdx++] = valByte
                        }
                    }
                }
                r.pos = rowLimit
                val rowOff = y * width
                if (bytesPerSample == 1) {
                    System.arraycopy(rowScratch, 0, alphaBytes, rowOff, width)
                } else {
                    for (x in 0 until width) {
                        alphaBytes[rowOff + x] = rowScratch[x * 2]
                    }
                }
            }
        }

        var maxAlpha = 0
        val argb = IntArray(width * height)
        for (i in argb.indices) {
            val a = alphaBytes[i].toInt() and 0xFF
            if (a > maxAlpha) maxAlpha = a
            argb[i] = Color.argb(a, 255, 255, 255)
        }
        if (maxAlpha < 4) return null

        val rawBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        rawBmp.setPixels(argb, 0, width, 0, 0, width, height)

        val maxSide = 256
        val finalBmp = if (width > maxSide || height > maxSide) {
            val scale = min(maxSide.toFloat() / width, maxSide.toFloat() / height)
            val nw = (width * scale).roundToInt().coerceAtLeast(16)
            val nh = (height * scale).roundToInt().coerceAtLeast(16)
            Bitmap.createScaledBitmap(rawBmp, nw, nh, true).also {
                if (it !== rawBmp) rawBmp.recycle()
            }
        } else {
            rawBmp
        }

        val outFile = File(tipsDir, "abr_tip_${UUID.randomUUID()}.png")
        FileOutputStream(outFile).use { fos ->
            finalBmp.compress(Bitmap.CompressFormat.PNG, 95, fos)
        }
        if (!finalBmp.isRecycled) finalBmp.recycle()
        return outFile
    }

    private data class EightBimBlock(val tag: String, val dataStart: Int, val dataEnd: Int)

    private fun find8BimBlocks(bytes: ByteArray, startOffset: Int): List<EightBimBlock> {
        val out = mutableListOf<EightBimBlock>()
        var pos = startOffset
        while (pos + 12 <= bytes.size) {
            if (bytes[pos] == '8'.code.toByte() &&
                bytes[pos + 1] == 'B'.code.toByte() &&
                bytes[pos + 2] == 'I'.code.toByte() &&
                bytes[pos + 3] == 'M'.code.toByte()
            ) {
                val tag = String(bytes, pos + 4, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(bytes, pos + 8, 4).order(ByteOrder.BIG_ENDIAN).int
                val dataStart = pos + 12
                if (size >= 0 && dataStart + size <= bytes.size) {
                    out.add(EightBimBlock(tag, dataStart, dataStart + size))
                    pos = dataStart + size
                    while (pos + 4 <= bytes.size &&
                        !(bytes[pos] == '8'.code.toByte() &&
                            bytes[pos + 1] == 'B'.code.toByte() &&
                            bytes[pos + 2] == 'I'.code.toByte() &&
                            bytes[pos + 3] == 'M'.code.toByte())
                    ) {
                        pos++
                    }
                    continue
                }
            }
            pos++
        }
        return out
    }

    private fun extractDescriptorNames(
        bytes: ByteArray,
        start: Int,
        end: Int,
        outNames: MutableList<String>,
    ) {
        var pos = start
        while (pos + 12 < end) {
            if (bytes[pos] == 'N'.code.toByte() &&
                bytes[pos + 1] == 'm'.code.toByte() &&
                bytes[pos + 2] == ' '.code.toByte() &&
                bytes[pos + 3] == ' '.code.toByte() &&
                bytes[pos + 4] == 'T'.code.toByte() &&
                bytes[pos + 5] == 'E'.code.toByte() &&
                bytes[pos + 6] == 'X'.code.toByte() &&
                bytes[pos + 7] == 'T'.code.toByte()
            ) {
                val charCount = ByteBuffer.wrap(bytes, pos + 8, 4).order(ByteOrder.BIG_ENDIAN).int
                val byteLen = charCount * 2
                if (charCount in 1..128 && pos + 12 + byteLen <= end) {
                    val str = String(bytes, pos + 12, byteLen, Charsets.UTF_16BE).trim('\u0000', ' ')
                    if (str.isNotBlank()) outNames.add(str)
                    pos += 12 + byteLen
                    continue
                }
            }
            pos++
        }
    }

    private fun inferShapeKind(
        roundness: Float,
        hardness: Float,
        scatter: Float,
        grainAmount: Float = 0f,
    ): BrushShapeKind = when {
        roundness < 0.72f -> BrushShapeKind.CALLIGRAPHY_FLAT
        scatter > 0.28f -> BrushShapeKind.SPECKLE_SPRAY
        hardness < 0.42f -> BrushShapeKind.SOFT_AIR
        grainAmount > 0.35f -> BrushShapeKind.GRAINY_CHALK
        else -> BrushShapeKind.ROUND
    }

    private fun normalizeAngle90(deg: Float): Float {
        var a = deg % 180f
        if (a > 90f) a -= 180f
        if (a < -90f) a += 180f
        return a.coerceIn(-90f, 90f)
    }

    private fun mapDiameterToSize(diameterPx: Int): Float =
        (diameterPx.toFloat() / 600f).coerceIn(0.04f, 0.9f)

    private class BigEndianReader(
        private val bytes: ByteArray,
        startPos: Int = 0,
        private val limit: Int = bytes.size,
    ) {
        var pos: Int = startPos

        fun remaining(): Int = (limit - pos).coerceAtLeast(0)
        fun skip(n: Int) { pos = (pos + n).coerceAtMost(limit) }

        fun readU8(): Int = bytes[pos++].toInt() and 0xFF
        fun readI8(): Int = bytes[pos++].toInt()

        fun readU16(): Int {
            val b0 = readU8()
            val b1 = readU8()
            return (b0 shl 8) or b1
        }

        fun readI16(): Int {
            val u = readU16()
            return if (u >= 0x8000) u - 0x10000 else u
        }

        fun readI32(): Int {
            val b0 = readU8()
            val b1 = readU8()
            val b2 = readU8()
            val b3 = readU8()
            return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
        }

        fun readF64(): Double {
            val v = ByteBuffer.wrap(bytes, pos, 8).order(ByteOrder.BIG_ENDIAN).double
            pos += 8
            return v
        }

        fun readAscii4(): String = readAscii(4)

        fun readAscii(len: Int): String {
            val s = String(bytes, pos, len, Charsets.US_ASCII)
            pos += len
            return s
        }

        fun readBytes(dst: ByteArray, dstOffset: Int, len: Int) {
            System.arraycopy(bytes, pos, dst, dstOffset, len)
            pos += len
        }

        fun readPhotoshopUnicodeString(): String {
            if (remaining() < 4) return ""
            val charCount = readI32()
            if (charCount <= 0 || charCount * 2 > remaining()) return ""
            val str = String(bytes, pos, charCount * 2, Charsets.UTF_16BE).trim('\u0000', ' ')
            pos += charCount * 2
            return str
        }
    }
}
