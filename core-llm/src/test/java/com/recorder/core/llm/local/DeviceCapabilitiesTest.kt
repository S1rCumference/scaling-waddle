package com.recorder.core.llm.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The values here are what real phones report, not round numbers. The kernel and firmware
 * reserve memory before Linux sees it, so every one of these is meaningfully below the size
 * on the box.
 */
class DeviceCapabilitiesTest {

    @Test
    fun `a 12GB phone is recognised as 12GB, not 8GB`() {
        // The case that matters for the Razr+ 2025: a raw threshold of 11 GB put phones
        // reporting less than that into the 8 GB tier and removed the heavy model.
        assertEquals(12, DeviceCapabilities.snapToMarketedGb(11.20))
        assertEquals(12, DeviceCapabilities.snapToMarketedGb(10.67))
        assertEquals(12, DeviceCapabilities.snapToMarketedGb(11.62))
    }

    @Test
    fun `an 8GB phone stays 8GB`() {
        assertEquals(8, DeviceCapabilities.snapToMarketedGb(7.35))
        assertEquals(8, DeviceCapabilities.snapToMarketedGb(7.68))
        assertEquals(8, DeviceCapabilities.snapToMarketedGb(7.01))
    }

    @Test
    fun `16GB and larger are recognised`() {
        assertEquals(16, DeviceCapabilities.snapToMarketedGb(15.36))
        assertEquals(16, DeviceCapabilities.snapToMarketedGb(14.90))
        assertEquals(24, DeviceCapabilities.snapToMarketedGb(23.10))
    }

    @Test
    fun `smaller phones are not rounded up into a tier they cannot serve`() {
        assertEquals(6, DeviceCapabilities.snapToMarketedGb(5.63))
        assertEquals(4, DeviceCapabilities.snapToMarketedGb(3.71))
        assertEquals(3, DeviceCapabilities.snapToMarketedGb(2.84))
    }

    /**
     * A measurement slightly above a marketed size must not jump to the next one: reporting
     * 8.05 on an 8 GB phone should not promise a 12 GB phone's model.
     */
    @Test
    fun `a slight overshoot does not promote to the next size`() {
        assertEquals(8, DeviceCapabilities.snapToMarketedGb(8.05))
        assertEquals(12, DeviceCapabilities.snapToMarketedGb(12.10))
        assertEquals(16, DeviceCapabilities.snapToMarketedGb(16.20))
    }

    @Test
    fun `nonsense measurements do not crash`() {
        assertEquals(0, DeviceCapabilities.snapToMarketedGb(0.0))
        assertEquals(0, DeviceCapabilities.snapToMarketedGb(-1.0))
        assertEquals(32, DeviceCapabilities.snapToMarketedGb(999.0))
    }

    @Test
    fun `exact marketed sizes map to themselves`() {
        listOf(2, 3, 4, 6, 8, 12, 16, 24, 32).forEach { size ->
            assertEquals(size, DeviceCapabilities.snapToMarketedGb(size.toDouble()))
        }
    }
}
