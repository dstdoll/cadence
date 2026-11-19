package app.cadence.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Another app binding to the exported session gets transport controls, never the queue. */
class ControllerPolicyTest {
    @Test
    fun theAppAndTrustedControllersKeepFullAccess() {
        assertNull(ControllerPolicy.commandsFor(own = true, trusted = false))
        assertNull(ControllerPolicy.commandsFor(own = false, trusted = true))
    }

    @Test
    fun anUntrustedAppCanOnlyUseTransportControls() {
        val c = ControllerPolicy.commandsFor(own = false, trusted = false)!!
        for (allowed in listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            assertTrue("missing $allowed", c.contains(allowed))
        }
        for (denied in listOf(
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_SET_PLAYLIST_METADATA,
        )) {
            assertFalse("untrusted app may use $denied", c.contains(denied))
        }
    }
}
