package com.paka.app

import android.graphics.Bitmap
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.oned.Code128Writer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import zxingcpp.BarcodeReader
import java.util.Random
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The device-free half of the scanner checklist in docs/DEVICE_TESTING.md.
 * Paka's own renders are degraded in memory and decoded through the camera
 * path's zxing-cpp readers, format mapping, payload rules and aim selection.
 * It proves decoder robustness and payload fidelity, not optics, autofocus,
 * torch control or real glare; those still need a camera.
 */
class ScannerDecodeCorpusTest {
    private val readers = scanReaderOptions.map(::BarcodeReader)

    @Test
    fun corpusDecodesToTheExactSourcePayload() {
        val checks = SAMPLES.flatMap { sample ->
            val symbol = render(sample)
            val module = symbol.moduleWidth()
            Log.i(TAG, "${sample.format.label()}: ${symbol.width / module} modules wide at $module px/module")
            listOf(Check(sample, "clean", "", true, listOf(outcome(sample, "clean", symbol.onPaper())))) +
                DEGRADATIONS.flatMap { damage ->
                    damage.levels.mapIndexed { index, level ->
                        val name = "${damage.name} ${level.label()}"
                        val frames = (0 until damage.frames).map { frame ->
                            outcome(sample, name, damage.apply(symbol, module, level, frame))
                        }
                        Check(sample, damage.name, level.label(), index < damage.asserted, frames)
                    }
                }
        }
        val report = SAMPLES.joinToString("\n") { sample -> reportLine(sample, checks.filter { it.sample == sample }) }
        report.lines().forEach { Log.i(TAG, it) }

        // A wrong payload is worse than no read, so it fails at any severity.
        val wrong = checks.filter { it.outcome == Outcome.WRONG && it.name !in KNOWN_LIMITS }.map(Check::name)
        assertTrue("Wrong payloads: $wrong\n$report", wrong.isEmpty())
        val missed = checks.filter { it.asserted && it.outcome != Outcome.EXACT && it.name !in KNOWN_LIMITS }
            .map { "${it.name}: ${it.outcome}" }
        assertTrue("Clean or moderate frames not decoded exactly: $missed\n$report", missed.isEmpty())
    }

    @Test
    fun tiltedItfNeverImportsAShorterPayload() {
        // Without Paka's guard, zxing-cpp read only a shorter, valid ITF at these tilts.
        listOf("123456" to 40f, "0123456789" to 26f, "00012345678905" to 24f).forEach { (payload, degrees) ->
            val sample = Sample(PakaFormat.ITF, payload)
            val frame = render(sample).onPaper().onPaper().rotated(degrees)
            val read = outcome(sample, "tilt° ${degrees.label()}", frame)
            assertTrue("ITF $payload tilted ${degrees.label()}° imported a wrong payload", read != Outcome.WRONG)
        }
    }

    @Test
    fun payloadsPakaCannotReRenderAreRefused() {
        // GS1-128: FNC1 in the first position is not part of a stored payload.
        // ZXing writes \u00f1 as FNC1: (01)09501101530003 (17)261231 (10)PAKA42.
        val gs1 = Code128Writer().encode("\u00f101095011015300031726123110PAKA42", BarcodeFormat.CODE_128, 0, 0)
        val gs1Frame = Gray(gs1.width * BASE_MODULE_PX, LINEAR_HEIGHT_PX) { x, _ ->
            if (gs1.get(x / BASE_MODULE_PX, 0)) BLACK else WHITE
        }.onPaper()
        assertRefused(BarcodeReader.Format.CODE_128, gs1Frame)

        // A group separator comes back as "<GS>" HRI text, which is not the payload.
        val qr = checkNotNull(Barcodes.generate(PakaFormat.QR, "ID\u001D12345")) { "QR with 0x1D did not render" }
        assertRefused(BarcodeReader.Format.QR_CODE, qr.toGray().onPaper())
    }

    private fun assertRefused(format: BarcodeReader.Format, frame: Gray) {
        val decoded = decode(frame)
        // Without a read the refusal would be unproven.
        assertEquals(listOf(format), decoded.formats)
        assertNull("$format payload must be refused", decoded.scan)
        assertTrue(decoded.refused)
    }

    /** Paka's own clean render at about [BASE_MODULE_PX] pixels per module. */
    private fun render(sample: Sample): Gray {
        val probe = checkNotNull(Barcodes.generate(sample.format, sample.payload)) {
            "${sample.format.label()} did not render"
        }.toGray()
        val modules = probe.width / probe.moduleWidth()
        return checkNotNull(Barcodes.generate(sample.format, sample.payload, modules * BASE_MODULE_PX)) {
            "${sample.format.label()} did not render at $BASE_MODULE_PX px/module"
        }.toGray()
    }

    private fun outcome(sample: Sample, name: String, frame: Gray): Outcome {
        val decoded = decode(frame)
        val scan = decoded.scan
        return when {
            scan == null -> if (decoded.refused) Outcome.REFUSED else Outcome.MISSED
            // Latin-1 maps bytes 1:1, so this is byte equality for Aztec and PDF417.
            scan.format == sample.scannedFormat && scan.data == sample.scanned -> Outcome.EXACT
            else -> Outcome.WRONG.also {
                Log.w(TAG, "${sample.format.label()} $name read as ${scan.format.label()} ${scan.data.escaped()}")
            }
        }
    }

    /** The camera analyzer's path, with the frame filling the preview. */
    private fun decode(frame: Gray): Decoded {
        val bitmap = frame.toBitmap()
        try {
            val symbols = readScanSymbols(readers) { read(bitmap) }
            val accepted = acceptedScans(symbols)
            val geometry = ScanGeometry(frame.width, frame.height, frame.width, frame.height)
            val scan = selectAimedSymbol(accepted.map { it.second }, geometry)?.let { accepted[it].first }
            return Decoded(symbols.map { it.format }, scan, refused = symbols.isNotEmpty() && accepted.isEmpty())
        } finally {
            bitmap.recycle()
        }
    }

    /** [refused] mirrors the camera's onlyRefused: symbols were read, none importable. */
    private class Decoded(val formats: List<BarcodeReader.Format>, val scan: ScanResult?, val refused: Boolean)

    /** One line per format; bracketed levels are asserted, starred ones are known limits. */
    private fun reportLine(sample: Sample, checks: List<Check>): String =
        sample.format.label().padEnd(LABEL_WIDTH) +
            checks.groupBy(Check::group).entries.joinToString(" | ") { (group, levels) ->
                val (asserted, logged) = levels.partition(Check::asserted)
                "$group [${asserted.joinToString(" ") { it.mark() }}] ${logged.joinToString(" ") { it.mark() }}".trimEnd()
            }

    /** [scanned] is what an exact scan imports, which is [payload] unless noted. */
    private data class Sample(
        val format: PakaFormat,
        val payload: String,
        val scannedFormat: PakaFormat = format,
        val scanned: String = payload,
    )

    /** [frames] holds one outcome per analysed frame of the same damage. */
    private data class Check(
        val sample: Sample,
        val group: String,
        val level: String,
        val asserted: Boolean,
        val frames: List<Outcome>,
    ) {
        val name get() = "${sample.format.label()} $group $level".trimEnd()

        // The analyzer keeps reading frames, so one exact frame scans and one wrong frame imports.
        val outcome get() = frames.min()

        fun mark(): String {
            val count = if (frames.size > 1) " ${frames.count { it == Outcome.EXACT }}/${frames.size}" else ""
            val known = if (name in KNOWN_LIMITS) "*" else ""
            return "$level ${outcome.mark}$count$known".trimStart()
        }
    }

    /**
     * Damage at increasing [levels]; the first [asserted] must decode exactly.
     * Noise differs per camera frame, so noisy damage is drawn for [frames] frames.
     */
    private class Degradation(
        val name: String,
        val levels: List<Float>,
        val asserted: Int,
        val frames: Int = 1,
        val apply: (symbol: Gray, module: Int, level: Float, frame: Int) -> Gray,
    )

    /** In precedence order across frames. */
    private enum class Outcome(val mark: String) { WRONG("WRONG"), EXACT("ok"), REFUSED("refused"), MISSED("--") }

    private companion object {
        const val TAG = "PakaScanCorpus"
        const val BASE_MODULE_PX = 4
        const val LINEAR_HEIGHT_PX = 160
        const val LABEL_WIDTH = 13

        // Synthetic payloads only; never real passes.
        val SAMPLES = listOf(
            Sample(PakaFormat.QR, "PAKA-QR-2026 \u00b7 R\u012bga \u2192 Tallinn \u00b7 seat 42"),
            // Every byte value 0x00-0xFF, more than twice: a dense binary ticket.
            Sample(PakaFormat.AZTEC, ByteArray(640) { (it * 37 + 11).toByte() }.toString(Charsets.ISO_8859_1)),
            Sample(PakaFormat.PDF417, ByteArray(256) { (it * 53 + 7).toByte() }.toString(Charsets.ISO_8859_1)),
            Sample(PakaFormat.DATA_MATRIX, "M1PAKA/DEMO EABC123 RIXTLLBT 0042 292Y012A0001 100"),
            Sample(PakaFormat.CODE128, "PAKA-128-demo/2026"),
            Sample(PakaFormat.CODE39, "PAKA-39 DEMO"),
            Sample(PakaFormat.EAN13, "4006381333931"),
            // zxing-cpp 3.1 reports UPC-A as EAN-13 with the implied leading 0: the
            // same bars, so the imported pass rescans identically.
            Sample(PakaFormat.UPCA, "036000291452", PakaFormat.EAN13, "0036000291452"),
            Sample(PakaFormat.ITF, "00012345678905"),
            Sample(PakaFormat.DATABAR_EXPANDED, "(01)09501101530003(3103)000123(15)261231(10)PAKA42"),
        )

        val DEGRADATIONS = listOf(
            Degradation("px/module", listOf(3f, 2f, 1.5f, 1.2f), 2) { s, m, px, _ -> s.scaled(px / m).onPaper() },
            Degradation("tilt\u00b0", listOf(2f, 4f, 8f, 12f, 20f), 2) { s, _, deg, _ -> s.onPaper().rotated(deg) },
            Degradation("blur \u03c3/module", listOf(0.2f, 0.3f, 0.4f, 0.6f, 0.8f), 2) { s, m, sigma, _ ->
                s.onPaper().blurred(sigma * m)
            },
            Degradation("glare", listOf(0.4f, 0.6f, 0.8f, 0.95f), 2) { s, _, strength, _ ->
                s.onPaper().glare(strength)
            },
            // Paper luma. 110 and 85 keep the frame mean above CameraScan's LOW_LIGHT_LUMA
            // (48); from about 60 down, auto-light would be turning the torch on.
            Degradation("low light", listOf(110f, 85f, 60f, 40f, 28f), 2, NOISY_FRAMES) { s, _, paper, frame ->
                val white = paper * WHITE / PAPER
                s.onPaper().exposed(white * DARK_SHARE, white).noisy(SENSOR_NOISE, SEED + frame)
            },
            Degradation("faded contrast", listOf(105f, 70f, 45f, 25f, 12f), 3) { s, _, contrast, _ ->
                s.onPaper().exposed(FADED_WHITE - contrast, FADED_WHITE)
            },
            Degradation("specks %", listOf(0.25f, 0.5f, 1f, 2f, 6f), 2, NOISY_FRAMES) { s, _, percent, frame ->
                s.onPaper().saltAndPepper(percent / 100, SEED + frame)
            },
            Degradation("quiet zone clear", listOf(0.75f, 0.5f, 0.25f), 1) { s, m, clear, _ ->
                s.withBorder(clear, m).onPaper()
            },
        )

        /** Limits of zxing-cpp 3.1.1 found by this corpus, exempt from the assertions above. */
        val KNOWN_LIMITS = setOf(
            // This wide, 13-column PDF417 decodes at 2° of tilt but no further.
            "PDF417 tilt° 4",
            // ITF needs its whole 10-module quiet zone.
            "ITF quiet zone clear 0.75",
        )
    }
}

private fun Float.label(): String = if (this == toInt().toFloat()) toInt().toString() else toString()

private fun String.escaped(): String = take(ESCAPED_LENGTH).map { c ->
    if (c in ' '..'~') c.toString() else "\\u%04x".format(c.code)
}.joinToString("")

private const val ESCAPED_LENGTH = 60
private const val NOISY_FRAMES = 3
private const val DARK_SHARE = 0.27f
private const val SENSOR_NOISE = 4f
private const val FADED_WHITE = 235f
private const val BLACK = 0f
private const val WHITE = 255f
private const val PAPER = 240f
private const val DARK = 128f
private const val SEED = 2026L

/** A luminance plane, 0 black to 255 white, like the camera's Y plane. */
private class Gray(val width: Int, val height: Int, init: (x: Int, y: Int) -> Float) {
    val px = FloatArray(width * height) { init(it % width, it / width) }

    operator fun get(x: Int, y: Int): Float = px[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

    fun map(f: (x: Int, y: Int, v: Float) -> Float) = Gray(width, height) { x, y -> f(x, y, this[x, y]) }

    fun toBitmap(): Bitmap {
        val argb = IntArray(px.size) {
            val v = px[it].roundToInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    }
}

private fun Bitmap.toGray(): Gray {
    val argb = IntArray(width * height)
    getPixels(argb, 0, width, 0, 0, width, height)
    return Gray(width, height) { x, y -> ((argb[y * width + x] shr 16) and 0xFF).toFloat() }
}

/** Narrowest dark run on a few rows: one module in an integer-scaled render. */
private fun Gray.moduleWidth(): Int = (1..7).minOf { band ->
    val y = height * band / 8
    var shortest = width
    var run = 0
    for (x in 0 until width) {
        if (this[x, y] < DARK) {
            run++
        } else if (run > 0) {
            shortest = min(shortest, run)
            run = 0
        }
    }
    shortest
}

private fun Gray.darkBounds(): IntArray {
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (y in 0 until height) for (x in 0 until width) {
        if (this[x, y] < DARK) {
            left = min(left, x)
            right = max(right, x)
            top = min(top, y)
            bottom = max(bottom, y)
        }
    }
    return intArrayOf(left, top, right, bottom)
}

/** Area-average resampling, as a sensor integrates light over each pixel. */
private fun Gray.scaled(factor: Float): Gray {
    val w = (width * factor).roundToInt()
    val h = (height * factor).roundToInt()
    val sx = width.toFloat() / w
    val sy = height.toFloat() / h
    return Gray(w, h) { x, y ->
        var sum = 0f
        var area = 0f
        val x0 = x * sx
        val x1 = x0 + sx
        val y0 = y * sy
        val y1 = y0 + sy
        for (yy in floor(y0).toInt() until ceil(y1).toInt()) {
            val wy = min(y1, yy + 1f) - max(y0, yy.toFloat())
            for (xx in floor(x0).toInt() until ceil(x1).toInt()) {
                val weight = wy * (min(x1, xx + 1f) - max(x0, xx.toFloat()))
                sum += this[xx, yy] * weight
                area += weight
            }
        }
        sum / area
    }
}

/** Places the symbol on paper with room to rotate it. */
private fun Gray.onPaper(): Gray {
    val margin = (max(width, height) * 0.15f).toInt() + 16
    return Gray(width + margin * 2, height + margin * 2) { x, y ->
        val sx = x - margin
        val sy = y - margin
        if (sx in 0 until width && sy in 0 until height) this[sx, sy] else PAPER
    }
}

private fun Gray.rotated(degrees: Float): Gray {
    val radians = Math.toRadians(degrees.toDouble())
    val c = cos(radians).toFloat()
    val s = sin(radians).toFloat()
    val cx = width / 2f
    val cy = height / 2f
    return Gray(width, height) { x, y ->
        val sx = c * (x - cx) + s * (y - cy) + cx
        val sy = -s * (x - cx) + c * (y - cy) + cy
        if (sx < 0 || sy < 0 || sx > width - 1 || sy > height - 1) {
            PAPER
        } else {
            val x0 = floor(sx).toInt()
            val y0 = floor(sy).toInt()
            val fx = sx - x0
            val fy = sy - y0
            val top = this[x0, y0] * (1 - fx) + this[x0 + 1, y0] * fx
            val bottom = this[x0, y0 + 1] * (1 - fx) + this[x0 + 1, y0 + 1] * fx
            top * (1 - fy) + bottom * fy
        }
    }
}

private fun Gray.blurred(sigma: Float): Gray {
    val radius = ceil(sigma * 3).toInt()
    val kernel = FloatArray(radius * 2 + 1) { exp(-((it - radius) * (it - radius)) / (2 * sigma * sigma)) }
    val total = kernel.sum()
    val horizontal = Gray(width, height) { x, y ->
        kernel.indices.sumOf { (this[x + it - radius, y] * kernel[it]).toDouble() }.toFloat() / total
    }
    return Gray(width, height) { x, y ->
        kernel.indices.sumOf { (horizontal[x, y + it - radius] * kernel[it]).toDouble() }.toFloat() / total
    }
}

/** A specular hotspot that lifts everything near it towards white. */
private fun Gray.glare(strength: Float): Gray {
    val cx = width * 0.4f
    val cy = height * 0.45f
    val radius = 0.35f * sqrt(width.toFloat() * height)
    return map { x, y, v ->
        val d2 = ((x - cx) * (x - cx) + (y - cy) * (y - cy)) / (radius * radius)
        v + (WHITE - v) * strength * exp(-d2)
    }
}

/** Maps black and white onto a narrower, possibly darker, luminance range. */
private fun Gray.exposed(black: Float, white: Float): Gray = map { _, _, v -> black + (white - black) * v / WHITE }

private fun Gray.noisy(sigma: Float, seed: Long): Gray {
    val random = Random(seed)
    return map { _, _, v -> v + random.nextGaussian().toFloat() * sigma }
}

private fun Gray.saltAndPepper(fraction: Float, seed: Long): Gray {
    val random = Random(seed)
    return map { _, _, v ->
        when {
            random.nextFloat() >= fraction -> v
            random.nextBoolean() -> BLACK
            else -> WHITE
        }
    }
}

/**
 * A printed frame line, one module thick, inside each quiet zone Paka renders,
 * leaving [clearFraction] of that zone clear next to the symbol.
 */
private fun Gray.withBorder(clearFraction: Float, module: Int): Gray {
    val (left, top, right, bottom) = darkBounds()
    fun gap(quiet: Int) = max(1, (quiet * clearFraction).roundToInt())
    val lines = buildList {
        if (left > 0) add(intArrayOf(left - gap(left) - module, 0, left - gap(left), height))
        if (right < width - 1) {
            val g = gap(width - 1 - right)
            add(intArrayOf(right + 1 + g, 0, right + 1 + g + module, height))
        }
        if (top > 0) add(intArrayOf(0, top - gap(top) - module, width, top - gap(top)))
        if (bottom < height - 1) {
            val g = gap(height - 1 - bottom)
            add(intArrayOf(0, bottom + 1 + g, width, bottom + 1 + g + module))
        }
    }
    return map { x, y, v ->
        if (lines.any { (x0, y0, x1, y1) -> x in x0 until x1 && y in y0 until y1 }) BLACK else v
    }
}
