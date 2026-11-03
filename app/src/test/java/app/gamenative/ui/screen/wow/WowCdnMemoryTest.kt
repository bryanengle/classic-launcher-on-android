package app.gamenative.ui.screen.wow

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WowCdnMemoryTest {
    private fun cdn(server: MockWebServer) = WowClientDownloader.Cdn(listOf("127.0.0.1:${server.port}"), "tpr/wow")
    private val key = "a".repeat(32)

    @Test fun ignoredRangeWithGigabyteContentLengthOnlyBuffersRequestedSlice() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("0123456789abcdef").setHeader("Content-Length", "1135560696"))
            server.start()
            assertEquals("4567", String(cdn(server).fetch("data", key, 4L..7L)!!))
            assertEquals("bytes=4-7", server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun ignoredRangeWithChunkedResponseAlsoReturnsCorrectSlice() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setChunkedBody("0123456789abcdef", 3))
            server.start()
            assertEquals("89ab", String(cdn(server).fetch("data", key, 8L..11L)!!))
        }
    }

    @Test fun honouredRangeUsesResponseStartAndReportsBytes() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 8-11/16").setBody("89ab"))
            server.start()
            var bytes = 0L
            assertEquals("89ab", String(cdn(server).fetch("data", key, 8L..11L) { bytes += it }!!))
            assertEquals(4L, bytes)
        }
    }

    @Test fun oversizedFullFileIsRejectedBeforeAllocating() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Length", "1135560696"))
            server.start()
            assertThrows(IOException::class.java) { cdn(server).fetchFast("data", key, null) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun wrongContentRangeIsRejected() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-3/16").setBody("0123"))
            server.start()
            assertThrows(IOException::class.java) { cdn(server).fetch("data", key, 8L..11L) }
        }
    }

    @Test fun shortRangeIsRejected() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setBody("01"))
            server.start()
            assertThrows(IOException::class.java) { cdn(server).fetch("data", key, 8L..11L) }
        }
    }
}
