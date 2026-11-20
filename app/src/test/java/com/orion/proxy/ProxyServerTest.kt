package com.orion.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProxyServerTest {

    @Test
    fun parseConnectRequestAndHostPort_ipv4AndIpv6() {
        val server = ProxyServer(port = 0)
        val raw = "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n"
            .toByteArray(StandardCharsets.ISO_8859_1)

        val head = server.parseHttpRequestHead(raw)
        assertNotNull(head)
        assertEquals("CONNECT", head!!.method)
        assertEquals("example.com:443", head.target)

        val hostPort = server.parseHostPort(head.target, 443)
        assertEquals(ProxyServer.HostPort("example.com", 443), hostPort)

        val ipv6HostPort = server.parseHostPort("[2001:db8::1]:8443", 443)
        assertEquals(ProxyServer.HostPort("2001:db8::1", 8443), ipv6HostPort)
    }

    @Test
    fun resolveHttpTarget_extractsOriginPathAndHost() {
        val server = ProxyServer(port = 0)
        val raw = "GET http://example.org:8080/api/ping?ok=1 HTTP/1.1\r\nHost: example.org:8080\r\nProxy-Connection: keep-alive\r\n\r\n"
            .toByteArray(StandardCharsets.ISO_8859_1)

        val head = server.parseHttpRequestHead(raw)!!
        val target = server.resolveHttpTarget(head)
        assertEquals(ProxyServer.HttpTarget("example.org", 8080, "/api/ping?ok=1"), target)
    }

    @Test
    fun endToEndHttpsConnectTunnel_pipesDataBidirectionally() {
        // Spin up a local echo TCP server simulating a remote TLS endpoint
        val echoServer = ServerSocket(0).apply { soTimeout = 3000 }
        val echoPort = echoServer.localPort
        val echoThread = Thread {
            runCatching {
                echoServer.accept().use { s ->
                    s.soTimeout = 3000
                    val input = s.getInputStream()
                    val output = s.getOutputStream()
                    val buf = ByteArray(1024)
                    val n = input.read(buf)
                    if (n > 0) {
                        output.write("ECHO:".toByteArray() + buf.copyOf(n))
                        output.flush()
                    }
                }
            }
        }.apply { start() }

        // Find a free port for the proxy server
        val freePort = ServerSocket(0).use { it.localPort }
        var lastStats = ProxyStats(0, 0, 0)
        val statsLatch = CountDownLatch(1)

        val proxy = ProxyServer(
            port = freePort,
            bindAddress = "127.0.0.1",
            onStatsChanged = { stats ->
                lastStats = stats
                if (stats.txBytes > 0 && stats.rxBytes > 0) statsLatch.countDown()
            }
        )

        try {
            proxy.start()
            Socket("127.0.0.1", freePort).use { client ->
                client.soTimeout = 3000
                val out = client.getOutputStream()
                val inp = client.getInputStream()

                val connectReq = "CONNECT 127.0.0.1:$echoPort HTTP/1.1\r\nHost: 127.0.0.1:$echoPort\r\n\r\n"
                out.write(connectReq.toByteArray(StandardCharsets.US_ASCII))
                out.flush()

                val reader = BufferedReader(InputStreamReader(inp, StandardCharsets.US_ASCII))
                val statusLine = reader.readLine()
                assertTrue("Expected 200 Connection Established, got $statusLine", statusLine.contains("200"))
                // Consume remaining response headers
                while (reader.readLine().isNotEmpty()) {
                    // skip
                }

                val payload = "HELLO_TLS_FRAME"
                out.write(payload.toByteArray(StandardCharsets.US_ASCII))
                out.flush()

                val echoed = CharArray(5 + payload.length)
                val readCount = reader.read(echoed)
                assertEquals("ECHO:$payload", String(echoed, 0, readCount))
            }

            assertTrue(statsLatch.await(2, TimeUnit.SECONDS))
            assertTrue(lastStats.txBytes > 0L)
            assertTrue(lastStats.rxBytes > 0L)
        } finally {
            proxy.stop()
            echoServer.close()
            echoThread.join(1000)
        }
    }
}
