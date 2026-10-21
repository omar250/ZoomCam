package com.example.zoomcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class SuperResolution(private val context: Context) {

    private var interpreter: Interpreter? = null
    val hasModel: Boolean get() = interpreter != null

    private val inSize = 50
    private val outSize = 200
    private val scale = 4
    private val margin = 5
    private val step = inSize - 2 * margin
    private val maxSide = 320

    init {
        try {
            val options = Interpreter.Options().setNumThreads(4)
            interpreter = Interpreter(loadModel("esrgan.tflite"), options)
        } catch (e: Exception) {
            interpreter = null
        }
    }

    private fun loadModel(name: String): MappedByteBuffer {
        val fd = context.assets.openFd(name)
        FileInputStream(fd.fileDescriptor).use {
            return it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val ratio = maxSide.toFloat() / maxOf(src.width, src.height)
        if (ratio >= 1f) return src
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    // تكبير عادي للصورة كاملة (للمقارنة)
    fun basic(src: Bitmap): Bitmap {
        val small = scaleDown(src)
        return Bitmap.createScaledBitmap(small, small.width * scale, small.height * scale, true)
    }

    fun enhance(src: Bitmap): Bitmap = enhanceFull(src) { _, _ -> }

    // تكبير بالذكاء الاصطناعي للصورة كاملة
    fun enhanceFull(src: Bitmap, onProgress: (Int, Int) -> Unit): Bitmap {
        val small = scaleDown(src)
        val w = small.width
        val h = small.height
        val itp = interpreter
            ?: return Bitmap.createScaledBitmap(small, w * scale, h * scale, true)

        val sp = IntArray(w * h)
        small.getPixels(sp, 0, w, 0, 0, w, h)

        val cols = (w + step - 1) / step
        val rows = (h + step - 1) / step
        val outW = w * scale
        val outH = h * scale
        val outPixels = IntArray(outW * outH)

        val input = Array(1) { Array(inSize) { Array(inSize) { FloatArray(3) } } }
        val output = Array(1) { Array(outSize) { Array(outSize) { FloatArray(3) } } }

        val cut = margin * scale
        val tileOut = step * scale
        val total = cols * rows
        var done = 0

        for (r in 0 until rows) for (c in 0 until cols) {
            val ox = c * step - margin
            val oy = r * step - margin
            for (y in 0 until inSize) {
                val sy = (oy + y).coerceIn(0, h - 1)
                for (x in 0 until inSize) {
                    val sx = (ox + x).coerceIn(0, w - 1)
                    val p = sp[sy * w + sx]
                    input[0][y][x][0] = Color.red(p).toFloat()
                    input[0][y][x][1] = Color.green(p).toFloat()
                    input[0][y][x][2] = Color.blue(p).toFloat()
                }
            }

            itp.run(input, output)

            for (y in 0 until tileOut) {
                val dy = r * tileOut + y
                if (dy >= outH) break
                for (x in 0 until tileOut) {
                    val dx = c * tileOut + x
                    if (dx >= outW) break
                    val o = output[0][y + cut][x + cut]
                    outPixels[dy * outW + dx] = Color.rgb(
                        o[0].toInt().coerceIn(0, 255),
                        o[1].toInt().coerceIn(0, 255),
                        o[2].toInt().coerceIn(0, 255)
                    )
                }
            }
            done++
            onProgress(done, total)
        }

        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, outW, 0, 0, outW, outH)
        return result
    }
}
