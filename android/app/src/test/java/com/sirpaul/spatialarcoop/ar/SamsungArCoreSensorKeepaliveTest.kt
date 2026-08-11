package com.sirpaul.spatialarcoop.ar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungArCoreSensorKeepaliveTest {
    @Test fun appliesOnlyToSamsungAndroid16AndNewer() {
        assertTrue(SamsungArCoreSensorKeepalive.isApplicable("samsung", 36))
        assertTrue(SamsungArCoreSensorKeepalive.isApplicable("Samsung", 37))
        assertFalse(SamsungArCoreSensorKeepalive.isApplicable("samsung", 35))
        assertFalse(SamsungArCoreSensorKeepalive.isApplicable("google", 36))
    }
}
