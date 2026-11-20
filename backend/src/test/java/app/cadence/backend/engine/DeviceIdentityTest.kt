package app.cadence.backend.engine

import app.cadence.backend.engine.media.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom

class DeviceIdentityTest {
    private fun dir(): File = Files.createTempDirectory("identity").toFile().apply { deleteOnExit() }

    @Test
    fun anIdIsOneToSixteenHexDigits() {
        assertEquals("0123456789abcdef", DeviceIdentity.usable("0123456789ABCDEF\n"))
        assertEquals("a", DeviceIdentity.usable(" a "))
        assertNull(DeviceIdentity.usable(""))
        assertNull(DeviceIdentity.usable("unknown"))
        assertNull(DeviceIdentity.usable("0123456789abcdef0"))
        assertNull(DeviceIdentity.usable("0123-4567"))
    }

    @Test
    fun withoutAReplacementThereIsNoOverride() {
        assertNull(DeviceIdentity.override(dir()))
    }

    @Test
    fun rotatingWritesAUsableSixteenDigitIdAndASecondOneDiffers() {
        val d = dir()
        assertTrue(DeviceIdentity.rotate(d, SecureRandom()))
        val first = DeviceIdentity.override(d)
        assertEquals(16, first?.length)
        assertEquals(first, DeviceIdentity.usable(first!!))
        assertTrue(DeviceIdentity.rotate(d, SecureRandom()))
        assertNotEquals(first, DeviceIdentity.override(d))
    }

    /** A damaged file must not become the device's identity: the platform's id is used instead. */
    @Test
    fun aMalformedReplacementIsIgnored() {
        val d = dir()
        File(d, DeviceIdentity.OVERRIDE_FILE).writeText("not an id")
        assertNull(DeviceIdentity.override(d))
    }

    @Test
    fun theIdIsNeverInItsDescription() {
        assertEquals("len=16", DeviceIdentity.describe("0123456789abcdef"))
        assertEquals("none", DeviceIdentity.describe("unknown"))
    }
}
