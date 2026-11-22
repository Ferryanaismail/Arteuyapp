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
import kotlin.math.max
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
                            val diameter = r.readU16().coerceIn(4, 2000)
                            val roundness = (r.readU16() / 100f).coerceIn(0.15f, 1f)
                            val angle = r.readI16().toFloat().coerceIn(-90f, 90f)
                            val hardness = (r.readU16() / 100f).coerceIn(0.05f, 1f)
                            val presetName = name.ifBlank { "$baseName ${out.size + 1}" }
                            out.add(
                                BrushPreset(
                                    id = UUID.randomUUID().toString(),
                                    name = presetName,
                                    setId = setId,
                                    isCustom = true,
                                    settings = BrushSettings(
                                        size = mapDiameterToSize(diameter),
                                        opacity = 1f,
                                        spacing = (spacing / 100f).coerceIn(0.03f, 0.7f),
                                        hardness = hardness,
                                        roundness = roundness,
                                        angleDeg = angle,
                                        shapeKind = if (roundness < 0.75f) BrushShapeKind.CALLIGRAPHY_FLAT else BrushShapeKind.ROUND,
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
                        val antialias = if (r.pos < blockEnd) r.readU8() else 1
                        val _unused = antialias
                        if (r.pos + 16 <= blockEnd) {
                            // short bounds (top, left, bottom, right) or 4x int32 bounds
                            val savedPos = r.pos
                            var tipFile: File? = null
                            if (r.pos + 19 <= blockEnd) {
                                // Try 4x int16 bounds + 4x int32 bounds layout first
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
                                    name = presetName,
                                    setId = setId,
                                    isCustom = true,
                                    settings = BrushSettings(
                                        size = 0.34f,
                                        opacity = 1f,
                                        spacing = (spacing / 100f).coerceIn(0.04f, 0.7f),
                                        hardness = 0.88f,
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

    private fun parseAbrV6Plus(
        bytes: ByteArray,
        subversion: Int,
        setId: String,
        tipsDir: File,
        baseName: String,
    ): List<BrushPreset> {
        val blocks = find8BimBlocks(bytes, startOffset = 4)
        val descNames = mutableListOf<String>()
        val sampledTips = mutableListOf<File?>()

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

                        val tip = tryDecodeV6SampledBrush(r, brushStart, brushEnd, subversion, tipsDir)
                        sampledTips.add(tip)
                        r.pos = paddedEnd
                    }
                }
                "desc" -> {
                    extractDescriptorNames(bytes, dataStart, dataEnd, descNames)
                }
            }
        }

        val out = mutableListOf<BrushPreset>()
        val total = max(sampledTips.size, if (sampledTips.isEmpty()) descNames.size else 0)
        if (sampledTips.isNotEmpty()) {
            sampledTips.forEachIndexed { idx, tipFile ->
                val rawName = descNames.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: "$baseName ${idx + 1}"
                out.add(
                    BrushPreset(
                        id = UUID.randomUUID().toString(),
                        name = rawName.take(40),
                        setId = setId,
                        isCustom = true,
                        settings = BrushSettings(
                            size = 0.36f,
                            opacity = 1f,
                            spacing = 0.12f,
                            hardness = 0.9f,
                            shapeKind = BrushShapeKind.ROUND,
                            tipPath = tipFile?.absolutePath,
                        ),
                    ),
                )
            }
        } else if (descNames.isNotEmpty()) {
            descNames.distinct().take(64).forEachIndexed { idx, nm ->
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

    private fun tryDecodeV6SampledBrush(
        r: BigEndianReader,
        brushStart: Int,
        brushEnd: Int,
        subversion: Int,
        tipsDir: File,
    ): File? {
        // Known header offsets before [top, left, bottom, right, depth(u16), compression(u8)] in ABR v6+
        val candidateHeaderOffsets = when (subversion) {
            1 -> listOf(47, 46, 48, 37)
            2 -> listOf(301, 300, 302, 264, 47)
            else -> listOf(301, 47, 300, 46, 264)
        }
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
                if (decoded != null) return decoded
            }
        }
        return null
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
            // PackBits RLE: Photoshop stores uint16 line byte counts for height * channels first
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
                    // hdr == -128 is PackBits no-op
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

        // Convert 8-bit mask to ARGB bitmap (RGB=white, A=mask). Check mask isn't completely empty.
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
                    // Advance past any optional 2/4-byte pad to next '8BIM' if not immediately aligned
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
        // Scan for "Nm  TEXT" Unicode strings in Photoshop ActionDescriptors
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

    private fun mapDiameterToSize(diameterPx: Int): Float =
        ((diameterPx.toFloat() / 600f).coerceIn(0.04f, 0.9f))

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
