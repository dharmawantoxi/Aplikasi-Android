package com.dharmawantoxi.docscan.edit

/** Titik harus tetap berurutan dan membentuk segi empat cembung yang tidak terlipat. */
object CropGeometry {
    fun valid(points: FloatArray): Boolean {
        if (points.size != 8 || points.any { !it.isFinite() || it < 0f || it > 1f }) return false
        for (i in 0..3) {
            val a = i * 2
            val b = ((i + 1) % 4) * 2
            val c = ((i + 2) % 4) * 2
            val cross = (points[b] - points[a]) * (points[c + 1] - points[b + 1]) -
                (points[b + 1] - points[a + 1]) * (points[c] - points[b])
            if (cross < 0.002f) return false
        }
        return true
    }
}
