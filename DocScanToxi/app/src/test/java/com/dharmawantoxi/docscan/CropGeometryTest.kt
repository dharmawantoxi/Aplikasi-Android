package com.dharmawantoxi.docscan

import com.dharmawantoxi.docscan.edit.CropGeometry
import org.junit.Assert.*
import org.junit.Test

class CropGeometryTest {
    @Test fun acceptsFullPageAndPerspective() {
        assertTrue(CropGeometry.valid(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)))
        assertTrue(CropGeometry.valid(floatArrayOf(.1f, .1f, .9f, .05f, .95f, .9f, .05f, .95f)))
    }
    @Test fun rejectsFoldedDegenerateAndOutOfRange() {
        assertFalse(CropGeometry.valid(floatArrayOf(0f, 0f, 1f, 1f, 1f, 0f, 0f, 1f)))
        assertFalse(CropGeometry.valid(floatArrayOf(0f, 0f, 0f, 0f, 1f, 1f, 0f, 1f)))
        assertFalse(CropGeometry.valid(floatArrayOf(-1f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)))
        assertFalse(CropGeometry.valid(floatArrayOf(Float.NaN, 0f, 1f, 0f, 1f, 1f, 0f, 1f)))
    }
}
