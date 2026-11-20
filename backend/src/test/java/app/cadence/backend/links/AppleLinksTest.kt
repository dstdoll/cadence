package app.cadence.backend.links

import app.cadence.backend.links.AppleLink.Album
import app.cadence.backend.links.AppleLink.Artist
import app.cadence.backend.links.AppleLink.Playlist
import app.cadence.backend.links.AppleLink.Song
import app.cadence.backend.links.AppleLink.Unsupported
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AppleLinksTest {
    private fun p(s: String?) = AppleLinks.parse(s)

    // ---- shapes of real links ---------------------------------------------------------------------------------------

    @Test fun albumLinksWithAndWithoutASlugOrStorefront() {
        assertEquals(Album("1665303755", null, "us"), p("https://music.apple.com/us/album/the-dark-side-of-the-moon/1665303755"))
        assertEquals(Album("1665303755", null, "gb"), p("https://music.apple.com/gb/album/1665303755"))
        assertEquals(Album("1665303755"), p("https://music.apple.com/album/x/1665303755"))
        assertEquals(Album("1665303755", null, "us"), p("https://music.apple.com/us/album/the-dark-side-of-the-moon/1665303755/"))
        assertEquals(Album("1665303755", null, "us"), p("https://music.apple.com/us/album/x/1665303755#frag"))
        assertEquals("tracking parameters are ignored", Album("1665303755", null, "us"), p("https://music.apple.com/us/album/x/1665303755?uo=4&at=1000l&ls=1&app=music"))
    }

    @Test fun aSongInAnAlbumKeepsItsSongId() {
        assertEquals(Album("1665303755", "1665303759", "us"), p("https://music.apple.com/us/album/breathe-in-the-air/1665303755?i=1665303759"))
        assertEquals(Album("1665303755", "1665303759", "us"), p("https://music.apple.com/us/album/x/1665303755?uo=4&i=1665303759&app=music"))
        assertEquals("a bad song id is dropped, the album still opens", Album("1665303755", null, "us"), p("https://music.apple.com/us/album/x/1665303755?i=abc"))
    }

    @Test fun songArtistAndPlaylistLinks() {
        assertEquals(Song("542212273", "us"), p("https://music.apple.com/us/song/%E5%88%86%E8%BA%AB%E8%A1%93/542212273"))
        assertEquals(Song("542212273", "us"), p("https://music.apple.com/us/song/542212273"))
        assertEquals(Artist("14836623", "us"), p("https://music.apple.com/us/artist/the-microphones/14836623"))
        assertEquals(Playlist("pl.f4d106fed2bd41149aaacabb233eb5eb", "us"), p("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb"))
        assertEquals(Playlist("pl.u-abc123_X-9", null), p("https://music.apple.com/playlist/mine/pl.u-abc123_X-9"))
    }

    @Test fun legacyHostsAndSchemes() {
        assertEquals(Album("1665303755", null, "us"), p("https://itunes.apple.com/us/album/x/id1665303755"))
        assertEquals(Album("1665303755", "1665303759", "us"), p("https://itunes.apple.com/us/album/x/id1665303755?i=1665303759"))
        assertEquals(Album("1665303755", null, "us"), p("music://music.apple.com/us/album/x/1665303755"))
        assertEquals(Album("1665303755", null, "us"), p("https://geo.music.apple.com/us/album/x/1665303755"))
        assertEquals(Album("1665303755", null, "us"), p("https://embed.music.apple.com/us/album/x/1665303755"))
        assertEquals("host and scheme are case-insensitive", Album("1665303755", null, "us"), p("HTTPS://Music.Apple.Com/us/album/X/1665303755"))
    }

    // ---- share texts -----------------------------------------------------------------------------------------------

    @Test fun theFirstSupportedLinkWinsWhateverElseIsInTheText() {
        assertEquals(Song("542212273", "us"), p("Listen to this! https://music.apple.com/us/song/542212273 so good"))
        assertEquals(Album("111", null, "us"), p("“Title” by Artist\nhttps://music.apple.com/us/album/x/111\nand https://music.apple.com/us/album/y/222"))
        assertEquals("an unsupported link before a supported one does not hide it", Album("333", null, "us"), p("https://music.apple.com/us/music-video/x/999 https://music.apple.com/us/album/z/333"))
        assertEquals(Song("542212273", "us"), p("(https://music.apple.com/us/song/542212273)"))
    }

    @Test fun unsupportedKindsAreNamedNotOpened() {
        assertEquals(Unsupported("music-video"), p("https://music.apple.com/us/music-video/x/123456"))
        assertEquals(Unsupported("station"), p("https://music.apple.com/us/station/x/ra.123456"))
        assertEquals(Unsupported("curator"), p("https://music.apple.com/us/curator/apple-music-pop/976439514"))
        assertEquals(Unsupported("library"), p("https://music.apple.com/library/playlists/p.abcdef"))
        assertEquals(Unsupported("classical"), p("https://classical.music.apple.com/us/album/x/123456"))
        assertEquals("a private library playlist id is unsupported", Unsupported("playlist"), p("https://music.apple.com/us/playlist/x/p.abc123"))
    }

    @Test fun notAnAppleLinkIsNull() {
        for (s in listOf(null, "", "   ", "hello world", "https://example.com/us/album/x/123456", "https://music.apple.com.evil.com/us/album/x/123456",
            "https://evil.com/?u=https://music.apple.com/us/album/x/123456x", "music.apple.com/us/album/x/123456", "ftp://music.apple.com/us/album/x/123456")) {
            assertNull("'$s'", p(s))
        }
    }

    // ---- hard rules ------------------------------------------------------------------------------------------------

    @Test fun idsThatCouldInjectIntoAnApiPathAreRejected() {
        for (bad in listOf("../../me/library", "12", "abc123", "1234567890123456", "123/../456", "%2e%2e", "123%2f456", "12 3", "123;rm", "١٢٣٤٥٦")) {
            val r = p("https://music.apple.com/us/album/x/$bad")
            assertTrue("'$bad' -> $r", r == null || r is Unsupported)
        }
        assertNull("a walking path is not a link at all", p("https://music.apple.com/us/playlist/x/pl.../.."))
        assertNull(p("https://music.apple.com/us/album/x/123/../456"))
        assertNull(p("https://music.apple.com/us/album/%2e%2e/456"))
        assertTrue(p("https://music.apple.com/us/playlist/x/pl.a/b") is Unsupported || p("https://music.apple.com/us/playlist/x/pl.a/b") is Playlist)
    }

    @Test fun aHostThatMerelyContainsAppleIsNotApple() {
        assertNull(p("https://notmusic.apple.com.example/us/album/x/123456"))
        assertNull(p("https://music.apple.com@evil.com/us/album/x/123456"))
    }

    @Test fun hugeAndRandomTextNeverCrashesAndNeverInvents() {
        val big = "a".repeat(1_000_000) + " https://music.apple.com/us/album/x/123456"
        assertNull("beyond the cap the link is not read", p(big))
        val rnd = Random(42)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789:/.?&=#%-_ \n中é"
        repeat(20_000) { p((1..rnd.nextInt(0, 80)).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")) }
        repeat(2_000) { p("https://music.apple.com/" + (1..rnd.nextInt(0, 60)).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")) }
    }

    // ---- building and the round trip -------------------------------------------------------------------------------

    @Test fun builtLinksParseBackToTheSameThing() {
        assertEquals("https://music.apple.com/gb/song/542212273", AppleLinks.song("gb", "542212273"))
        assertEquals(Song("542212273", "gb"), p(AppleLinks.song("gb", "542212273")))
        assertEquals(Album("1665303755", null, "us"), p(AppleLinks.album("us", "1665303755")))
        assertEquals(Artist("14836623", "mx"), p(AppleLinks.artist("MX", "14836623")))
        assertEquals(Playlist("pl.abc123", "us"), p(AppleLinks.playlist("us", "pl.abc123")))
    }

    @Test fun badIdsAndStorefrontsNeverProduceALink() {
        assertNull(AppleLinks.song("us", "abc")); assertNull(AppleLinks.album("us", "12")); assertNull(AppleLinks.playlist("us", "p.123"))
        assertEquals("an unusable storefront falls back to us", "https://music.apple.com/us/song/542212273", AppleLinks.song("", "542212273"))
        assertEquals("https://music.apple.com/us/song/542212273", AppleLinks.song("usa", "542212273"))
        assertFalse(AppleLinks.song("us", "542212273")!!.contains(" "))
    }

    @Test fun aPastedLineIsRecognisedOnlyWhenItIsOneLink() {
        assertTrue(AppleLinks.isLinkText("  https://music.apple.com/us/song/542212273 \n"))
        assertFalse(AppleLinks.isLinkText("listen https://music.apple.com/us/song/542212273"))
        assertFalse(AppleLinks.isLinkText("Pink Floyd"))
        assertFalse(AppleLinks.isLinkText(null))
        assertTrue("an unsupported Apple link is still a link, not a search", AppleLinks.isLinkText("https://music.apple.com/us/music-video/x/123456"))
    }
}
