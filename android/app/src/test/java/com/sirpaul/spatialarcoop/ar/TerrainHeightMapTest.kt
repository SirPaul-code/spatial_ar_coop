package com.sirpaul.spatialarcoop.ar

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TerrainHeightMapTest {
    @Test
    fun followsARealSlopeInsteadOfFlatteningEverythingToGroundY() {
        val points = buildList {
            for (zStep in 0..20) {
                val z = zStep * 0.4f
                val ground = z * 0.08f
                for (xStep in -3..3) {
                    val x = xStep * 0.25f
                    add(floatArrayOf(x, ground, z, 0.9f))
                    add(floatArrayOf(x, ground + 0.04f, z, 0.7f))
                    add(floatArrayOf(x, ground + 1.6f, z, 0.8f))
                }
            }
        }
        val terrain = assertNotNull(TerrainHeightMap.fromPoints(points, fallbackGroundY = 0f))
        val low = assertNotNull(terrain.heightAt(0f, 0.8f))
        val high = assertNotNull(terrain.heightAt(0f, 7.2f))
        assertTrue(high.y - low.y > 0.40f)
    }

    @Test
    fun rayIntersectionFindsSlopedSurface() {
        val points = buildList {
            for (zStep in 0..24) {
                val z = zStep * 0.35f
                val y = z * 0.05f
                for (x in -2..2) add(floatArrayOf(x * 0.25f, y, z, 0.9f))
            }
        }
        val terrain = assertNotNull(TerrainHeightMap.fromPoints(points, null))
        val hit = assertNotNull(terrain.intersectRay(floatArrayOf(0f, 1.6f, 0f), floatArrayOf(0f, -0.15f, 1f), 12f))
        assertTrue(hit.position[1] > 0.15f)
        assertTrue(hit.distanceMeters in 1f..12f)
    }
}
