package app.cadence.playback

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands

/**
 * Who may do what with the playback session.
 *
 * The service has to be exported (system media controls and Bluetooth bind to it), which lets any
 * installed app connect to it by name. The app itself and controllers Android trusts (system UI,
 * Bluetooth, apps holding MEDIA_CONTENT_CONTROL or notification-listener access) keep full access.
 * Any other app gets transport controls only: it cannot queue its own items, read the queue or see
 * what is playing.
 */
@OptIn(UnstableApi::class)
internal class ControllerPolicy(private val ownPackage: String) : MediaSession.Callback {
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
        val limited = commandsFor(own = controller.packageName == ownPackage, trusted = controller.isTrusted)
        // Full commands stated outright: the builder's own defaults depend on whether Media3 trusts the
        // controller, and the app's own controller must keep everything either way.
            ?: return result
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
                .build()
        return result
            .setAvailablePlayerCommands(Player.Commands.Builder().apply { limited.forEach(::add) }.build())
            .setAvailableSessionCommands(SessionCommands.EMPTY)
            .build()
    }

    companion object {
        /** Play, pause, seek and skip; nothing that reads or changes the queue or its metadata. */
        val TRANSPORT_ONLY: Set<Int> = setOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
        )

        /** Null means the session's full default command set. */
        fun commandsFor(own: Boolean, trusted: Boolean): Set<Int>? = if (own || trusted) null else TRANSPORT_ONLY
    }
}
