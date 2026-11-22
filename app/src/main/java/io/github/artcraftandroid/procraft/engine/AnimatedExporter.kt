package io.github.artcraftandroid.procraft.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure Kotlin GIF89a animated encoder + filmstrip sheet exporter.
 * Encodes multi-frame artwork animations and Time-lapse sequences cleanly without external binaries.
 */
object AnimatedExporter {

    fun encodeGif(
        frames: List<Bitmap>,
        output: OutputStream,
        fps: Int = 8,
        transparentBg: Boolean = false,
        maxDim: Int = 512,
    ) {
        if (frames.isEmpty()) return
        val first = frames.first()
        val scale = min(maxDim.toFloat() / max(first.width, 1), maxDim.toFloat() / max(first.height, 1)).coerceAtMost(1f)
        val w = max(16, (first.width * scale).roundToInt())
        val h = max(16, (first.height * scale).roundToInt())
        val delayCs = (100f / fps.coerceIn(1, 30)).roundToInt().coerceIn(3, 200)

        // Header: GIF89a
        output.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // Logical Screen Descriptor (no global color table — each frame writes a 256-color quantized table)
        writeShort(output, w)
        writeShort(output, h)
        output.write(0x70) // 8-bit color resolution, no global table
        output.write(0x00) // bg color index
        output.write(0x00) // pixel aspect ratio

        // Netscape 2.0 Application Extension (infinite loop)
        output.write(0x21)
        output.write(0xFF)
        output.write(11)
        output.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        output.write(3)
        output.write(1)
        writeShort(output, 0) // loop forever
        output.write(0)

        val scaledBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaledBmp)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val pixels = IntArray(w * h)

        // Build shared uniform 6x7x6 palette (252 colors) + reserved transparent/white entries
        val paletteBytes = buildWebCubePalette()

        for (src in frames) {
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(src, android.graphics.Rect(0, 0, src.width, src.height), android.graphics.Rect(0, 0, w, h), paint)
            scaledBmp.getPixels(pixels, 0, w, 0, 0, w, h)

            // Graphic Control Extension
            output.write(0x21)
            output.write(0xF9)
            output.write(4)
            val packed = if (transparentBg) 0x09 else 0x08 // disposal=2 (restore background) + transparent flag
            output.write(packed)
            writeShort(output, delayCs)
            output.write(if (transparentBg) 255 else 0) // transparent index = 255
            output.write(0)

            // Image Descriptor
            output.write(0x2C)
            writeShort(output, 0)
            writeShort(output, 0)
            writeShort(output, w)
            writeShort(output, h)
            output.write(0x87) // Local color table present, size = 2^(7+1) = 256
            output.write(paletteBytes)

            val indices = ByteArray(pixels.size) { i ->
                val c = pixels[i]
                val a = (c ushr 24) and 0xFF
                if (transparentBg && a < 64) {
                    255.toByte()
                } else {
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    quantizeToCube(r, g, b).toByte()
                }
            }
            LzwEncoder(8, indices).encode(output)
        }

        scaledBmp.recycle()
        output.write(0x3B) // GIF Trailer
        output.flush()
    }

    fun buildFilmstripBitmap(frames: List<Bitmap>, maxCellDim: Int = 360): Bitmap? {
        if (frames.isEmpty()) return null
        val sample = frames.first()
        val scale = min(maxCellDim.toFloat() / sample.width, maxCellDim.toFloat() / sample.height).coerceAtMost(1f)
        val cw = max(32, (sample.width * scale).roundToInt())
        val ch = max(32, (sample.height * scale).roundToInt())
        val cols = min(frames.size, ceil(sqrt(frames.size.toDouble())).toInt().coerceAtLeast(1))
        val rows = ((frames.size + cols - 1) / cols).coerceAtLeast(1)
        val out = Bitmap.createBitmap(cols * cw, rows * ch, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        frames.forEachIndexed { idx, bmp ->
            val col = idx % cols
            val row = idx / cols
            val dst = android.graphics.Rect(col * cw, row * ch, (col + 1) * cw, (row + 1) * ch)
            c.drawBitmap(bmp, android.graphics.Rect(0, 0, bmp.width, bmp.height), dst, p)
        }
        return out
    }

    private fun buildWebCubePalette(): ByteArray {
        val out = ByteArray(256 * 3)
        var idx = 0
        // 6 x 7 x 6 = 252 colors
        for (r in 0 until 6) {
            for (g in 0 until 7) {
                for (b in 0 until 6) {
                    out[idx++] = ((r * 255) / 5).toByte()
                    out[idx++] = ((g * 255) / 6).toByte()
                    out[idx++] = ((b * 255) / 5).toByte()
                }
            }
        }
        // 252..255 grayscale ramp (255 = pure white / transparent slot)
        val grays = intArrayOf(64, 128, 192, 255)
        for (v in grays) {
            out[idx++] = v.toByte()
            out[idx++] = v.toByte()
            out[idx++] = v.toByte()
        }
        return out
    }

    private fun quantizeToCube(r: Int, g: Int, b: Int): Int {
        val ri = (r * 5 + 127) / 255
        val gi = (g * 6 + 127) / 255
        val bi = (b * 5 + 127) / 255
        return (ri * 42 + gi * 6 + bi).coerceIn(0, 251)
    }

    private fun writeShort(out: OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private class LzwEncoder(
        private val minCodeSize: Int,
        private val pixels: ByteArray,
    ) {
        private val clearCode = 1 shl minCodeSize
        private val eofCode = clearCode + 1
        private var codeSize = minCodeSize + 1
        private var nextCode = eofCode + 1
        private val table = HashMap<Int, Int>(4096)

        private var curAccum = 0
        private var curBits = 0
        private val blockBuf = ByteArrayOutputStream(256)

        fun encode(out: OutputStream) {
            out.write(minCodeSize)
            resetTable()
            emitCode(clearCode, out)

            if (pixels.isEmpty()) {
                emitCode(eofCode, out)
                flushBits(out)
                out.write(0)
                return
            }

            var prefix = pixels[0].toInt() and 0xFF
            for (i in 1 until pixels.size) {
                val c = pixels[i].toInt() and 0xFF
                val key = (prefix shl 12) or c
                val existing = table[key]
                if (existing != null) {
                    prefix = existing
                } else {
                    emitCode(prefix, out)
                    if (nextCode < 4096) {
                        table[key] = nextCode++
                        if (nextCode > (1 shl codeSize) && codeSize < 12) {
                            codeSize++
                        }
                    } else {
                        emitCode(clearCode, out)
                        resetTable()
                    }
                    prefix = c
                }
            }
            emitCode(prefix, out)
            emitCode(eofCode, out)
            flushBits(out)
            out.write(0) // zero-length block terminator
        }

        private fun resetTable() {
            table.clear()
            codeSize = minCodeSize + 1
            nextCode = eofCode + 1
        }

        private fun emitCode(code: Int, out: OutputStream) {
            curAccum = curAccum or (code shl curBits)
            curBits += codeSize
            while (curBits >= 8) {
                blockBuf.write(curAccum and 0xFF)
                if (blockBuf.size() >= 254) {
                    flushBlock(out)
                }
                curAccum = curAccum ushr 8
                curBits -= 8
            }
        }

        private fun flushBits(out: OutputStream) {
            if (curBits > 0) {
                blockBuf.write(curAccum and 0xFF)
            }
            flushBlock(out)
        }

        private fun flushBlock(out: OutputStream) {
            val sz = blockBuf.size()
            if (sz > 0) {
                out.write(sz)
                out.write(blockBuf.toByteArray())
                blockBuf.reset()
            }
        }
    }
}
