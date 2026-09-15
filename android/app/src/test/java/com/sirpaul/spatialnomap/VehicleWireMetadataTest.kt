package com.sirpaul.spatialnomap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleWireMetadataTest {
    @Test fun roundTripFitsExistingOwnerBudget() {
        val encoded = VehicleWireMetadata.encode("Galaxy S942B", "CAR", floatArrayOf(0f, 0f, -1f))
        assertTrue(encoded.length <= 48)
        val parsed = VehicleWireMetadata.decode(encoded)
        assertEquals("Galaxy S942B", parsed.owner)
        assertEquals("CAR", parsed.label)
        assertNotNull(parsed.axis)
    }

    @Test fun legacyDynamicOwnerStillParses() {
        val parsed = VehicleWireMetadata.decode("AUTO:CAR:Peer")
        assertEquals("Peer", parsed.owner)
        assertEquals("CAR", parsed.label)
    }
}
