package app.cadence.core.design

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class CoverLoaderTest {

    @Test
    fun transientHttpStatusesAreRetriedAndClientErrorsAreNot() {
        assertTrue(isTransientHttpStatus(429))
        assertTrue(isTransientHttpStatus(500))
        assertTrue(isTransientHttpStatus(502))
        assertTrue(isTransientHttpStatus(503))
        assertTrue(isTransientHttpStatus(504))

        assertFalse(isTransientHttpStatus(400))
        assertFalse(isTransientHttpStatus(403))
        assertFalse(isTransientHttpStatus(404))
        assertFalse(isTransientHttpStatus(200))
    }

    @Test
    fun transientNetworkExceptionsAreDetectedIncludingWrappedCauses() {
        assertTrue(isTransientNetworkError(UnknownHostException("is1-ssl.mzstatic.com")))
        assertTrue(isTransientNetworkError(SocketTimeoutException("timeout")))
        assertTrue(isTransientNetworkError(ConnectException("refused")))
        assertTrue(isTransientNetworkError(SSLHandshakeException("reset")))
        assertTrue(isTransientNetworkError(IOException("wrapped", UnknownHostException("dns"))))

        assertFalse(isTransientNetworkError(IOException("disk full")))
        assertFalse(isTransientNetworkError(IllegalStateException("bad state")))
    }
}
