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

    private val tiles = 4
    private val inSize = 50
    private val outSize = 200

    init {
        try {
            interpreter = Interpreter(loadModel("esrgan.tflite"))
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

    // مربع من منتصف الصورة بدقتها الأصلية (بحد أقصى 200x200 بكسل)
    private fun centerCrop(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height, inSize * tiles)
        return Bitmap.createBitmap(
            src, (src.width - side) / 2, (src.height - side) / 2, side, side
        )
    }

    // تكبير عادي لنفس المنطقة (للمقارنة)
    fun basic(src: Bitmap): Bitmap {
        return Bitmap.createScaledBitmap(centerCrop(src), outSize * tiles, outSize * tiles, true)
    }

    // تكبير بالذكاء الاصطناعي
    fun enhance(src: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(centerCrop(src), inSize * tiles, inSize * tiles, true)

        val itp = interpreter ?: return basic(src)

        val result = Bitmap.createBitmap(outSize * tiles, outSize * tiles, Bitmap.Config.ARGB_8888)
        val input = Array(1) { Array(inSize) { Array(inSize) { FloatArray(3) } } }
        val output = Array(1) { Array(outSize) { Array(outSize) { FloatArray(3) } } }
        val px = IntArray(inSize * inSize)
        val outPx = IntArray(outSize * outSize)

        for (ty in 0 until tiles) for (tx in 0 until tiles) {
            small.getPixels(px, 0, inSize, tx * inSize, ty * inSize, inSize, inSize)
            for (y in 0 until inSize) for (x in 0 until inSize) {
                val c = px[y * inSize + x]
                input[0][y][x][0] = Color.red(c).toFloat()
                input[0][y][x][1] = Color.green(c).toFloat()
                input[0][y][x][2] = Color.blue(c).toFloat()
            }
            itp.run(input, output)
            for (y in 0 until outSize) for (x in 0 until outSize) {
                val o = output[0][y][x]
                outPx[y * outSize + x] = Color.rgb(
                    o[0].toInt().coerceIn(0, 255),
                    o[1].toInt().coerceIn(0, 255),
                    o[2].toInt().coerceIn(0, 255)
                )
            }
            result.setPixels(outPx, 0, outSize, tx * outSize, ty * outSize, outSize, outSize)
        }
        return result
    }
}
