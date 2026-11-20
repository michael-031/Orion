package com.orion.proxy

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

data class ProxyStats(
    val activeConnections: Int,
    val txBytes: Long,
    val rxBytes: Long,
    val throughputBytesPerSec: Long = 0L
)

/**
 * High-throughput, multithreaded HTTP & HTTPS (CONNECT tunnel) proxy server tuned
 * to match native Android mobile tethering throughput:
 * - Dedicated elastic thread pool (up to 512 concurrent I/O streams, avoiding Dispatchers.IO 64-thread cap)
 * - 64 KB copy buffers + 512 KB kernel TCP send/receive windows for high-BDP 4G/5G links
 * - Single-syscall buffered header framing with zero byte loss into TLS/body streams
 * - Halved per-tunnel thread usage (1 child coroutine + caller thread)
 * - Cached cellular DNS resolution to eliminate P2P interface DNS stalls
 */
class ProxyServer(
    private val port: Int = DEFAULT_PROXY_PORT,
    private val bindAddress: String = "0.0.0.0",
    private val upstreamSocketFactoryProvider: () -> SocketFactory = { SocketFactory.getDefault() },
    private val upstreamDnsResolver: (String) -> Array<InetAddress> = { host -> InetAddress.getAllByName(host) },
    private val onStatsChanged: (ProxyStats) -> Unit = {},
    private val onFatalError: (Throwable) -> Unit = {}
) {
    companion object {
        private const val TAG = "DirectProxyServer"
        private const val BACKLOG = 256
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val HEADER_CHUNK_SIZE = 8 * 1024
        private const val SOCKET_BUFFER_SIZE = 512 * 1024
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val HEADER_READ_TIMEOUT_MS = 15_000
        private const val UPSTREAM_CONNECT_TIMEOUT_MS = 12_000
        private const val STATS_FLUSH_INTERVAL_MS = 500L
        private const val DNS_CACHE_TTL_MS = 60_000L
        private const val MAX_DNS_CACHE_ENTRIES = 512

        private val HOP_BY_HOP_HEADERS = setOf(
            "proxy-connection",
            "proxy-authenticate",
            "proxy-authorization",
            "connection",
            "keep-alive",
            "te",
            "trailers",
            "upgrade"
        )
    }

    private data class CachedDnsEntry(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long
    )

    private data class HeaderReadResult(
        val headerBytes: ByteArray,
        val leftoverBytes: ByteArray,
        val leftoverLength: Int
    )

    private val running = AtomicBoolean(false)
    private val activeConnections = AtomicInteger(0)
    private val txBytes = AtomicLong(0L)
    private val rxBytes = AtomicLong(0L)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()
    private val threadIdCounter = AtomicInteger(1)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var ioExecutor: ThreadPoolExecutor? = null

    @Volatile
    private var ioDispatcher: CoroutineDispatcher? = null

    @Volatile
    private var serverScope: CoroutineScope? = null

    private var acceptJob: Job? = null
    private var statsJob: Job? = null

    val isRunning: Boolean
        get() = running.get()

    @Throws(IOException::class)
    fun start() {
        if (!running.compareAndSet(false, true)) return

        activeConnections.set(0)
        txBytes.set(0L)
        rxBytes.set(0L)
        dnsCache.clear()

        val socket = ServerSocket().apply {
            reuseAddress = true
            // Configure receive window BEFORE bind so accepted TCP connections advertise large windows in SYN-ACK
            runCatching { receiveBufferSize = SOCKET_BUFFER_SIZE }
            bind(InetSocketAddress(InetAddress.getByName(bindAddress), port), BACKLOG)
        }
        serverSocket = socket

        val executor = ThreadPoolExecutor(
            16,
            512,
            30L,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            ThreadFactory { runnable ->
                Thread(runnable, "DirectProxy-IO-${threadIdCounter.getAndIncrement()}").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY + 1
                }
            },
            ThreadPoolExecutor.CallerRunsPolicy()
        )
        ioExecutor = executor
        val dispatcher = executor.asCoroutineDispatcher()
        ioDispatcher = dispatcher

        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        serverScope = scope

        statsJob = scope.launch {
            var lastConnections = -1
            var lastTx = 0L
            var lastRx = 0L
            var lastSpeed = -1L
            var lastSampleTimeMs = System.nanoTime() / 1_000_000L

            while (isActive && running.get()) {
                delay(STATS_FLUSH_INTERVAL_MS)
                val nowMs = System.nanoTime() / 1_000_000L
                val elapsedMs = (nowMs - lastSampleTimeMs).coerceAtLeast(1L)
                val c = activeConnections.get()
                val tx = txBytes.get()
                val rx = rxBytes.get()
                val deltaBytes = ((tx - lastTx) + (rx - lastRx)).coerceAtLeast(0L)
                val speed = (deltaBytes * 1000L) / elapsedMs

                if (c != lastConnections || tx != lastTx || rx != lastRx || speed != lastSpeed) {
                    lastConnections = c
                    lastTx = tx
                    lastRx = rx
                    lastSpeed = speed
                    lastSampleTimeMs = nowMs
                    onStatsChanged(ProxyStats(c, tx, rx, speed))
                } else {
                    lastSampleTimeMs = nowMs
                }
            }
        }

        acceptJob = scope.launch {
            Log.i(TAG, "High-throughput proxy listening on $bindAddress:$port")
            try {
                while (isActive && running.get()) {
                    val clientSocket = try {
                        socket.accept()
                    } catch (e: IOException) {
                        if (running.get()) {
                            Log.e(TAG, "Accept error on port $port", e)
                            onFatalError(e)
                        }
                        break
                    }

                    launch(dispatcher) {
                        handleClient(clientSocket, dispatcher)
                    }
                }
            } finally {
                closeQuietly(socket)
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return

        closeQuietly(serverSocket)
        serverSocket = null

        val socketsSnapshot = activeSockets.toList()
        activeSockets.clear()
        socketsSnapshot.forEach { closeQuietly(it) }

        acceptJob?.cancel()
        statsJob?.cancel()
        serverScope?.cancel()
        acceptJob = null
        statsJob = null
        serverScope = null

        ioExecutor?.shutdownNow()
        ioExecutor = null
        ioDispatcher = null
        dnsCache.clear()

        activeConnections.set(0)
        onStatsChanged(ProxyStats(0, txBytes.get(), rxBytes.get(), 0L))
        Log.i(TAG, "Proxy server stopped")
    }

    fun clearDnsCache() {
        dnsCache.clear()
    }

    private suspend fun handleClient(
        clientSocket: Socket,
        dispatcher: CoroutineDispatcher
    ) {
        activeSockets.add(clientSocket)
        activeConnections.incrementAndGet()

        var remoteSocket: Socket? = null
        try {
            tuneSocket(clientSocket)
            clientSocket.soTimeout = HEADER_READ_TIMEOUT_MS

            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            val headerResult = readHeaderBlockBuffered(clientIn) ?: return
            txBytes.addAndGet(headerResult.headerBytes.size.toLong())

            val parsedRequest = parseHttpRequestHead(headerResult.headerBytes)
            if (parsedRequest == null) {
                writeHttpError(clientOut, 400, "Bad Request")
                return
            }

            if (parsedRequest.method.equals("CONNECT", ignoreCase = true)) {
                remoteSocket = handleHttpsConnect(
                    request = parsedRequest,
                    leftoverBytes = headerResult.leftoverBytes,
                    leftoverLength = headerResult.leftoverLength,
                    clientSocket = clientSocket,
                    clientIn = clientIn,
                    clientOut = clientOut,
                    dispatcher = dispatcher
                )
            } else {
                remoteSocket = handleHttpForward(
                    request = parsedRequest,
                    leftoverBytes = headerResult.leftoverBytes,
                    leftoverLength = headerResult.leftoverLength,
                    clientSocket = clientSocket,
                    clientIn = clientIn,
                    clientOut = clientOut,
                    dispatcher = dispatcher
                )
            }
        } catch (_: CancellationException) {
            // Normal shutdown cancellation
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "Client connection timed out: ${e.message}")
        } catch (e: IOException) {
            Log.d(TAG, "Client connection closed: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected proxy client error", e)
        } finally {
            closeQuietly(remoteSocket)
            remoteSocket?.let { activeSockets.remove(it) }
            closeQuietly(clientSocket)
            activeSockets.remove(clientSocket)
            activeConnections.decrementAndGet().coerceAtLeast(0)
        }
    }

    private suspend fun handleHttpsConnect(
        request: ParsedRequestHead,
        leftoverBytes: ByteArray,
        leftoverLength: Int,
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        dispatcher: CoroutineDispatcher
    ): Socket? {
        val target = parseHostPort(request.target, defaultPort = 443)
        if (target == null) {
            writeHttpError(clientOut, 400, "Bad Request")
            return null
        }

        val remoteSocket = try {
            openUpstreamSocket(target.host, target.port)
        } catch (e: SocketTimeoutException) {
            writeHttpError(clientOut, 504, "Gateway Timeout")
            return null
        } catch (e: IOException) {
            writeHttpError(clientOut, 502, "Bad Gateway")
            return null
        }

        activeSockets.add(remoteSocket)

        // Remove header read timeout for long-lived TLS tunnels
        clientSocket.soTimeout = 0
        remoteSocket.soTimeout = 0

        val established = "HTTP/1.1 200 Connection Established\r\nProxy-Agent: Orion/1.0\r\n\r\n"
            .toByteArray(StandardCharsets.US_ASCII)
        clientOut.write(established)
        clientOut.flush()
        rxBytes.addAndGet(established.size.toLong())

        val remoteOut = remoteSocket.getOutputStream()
        val remoteIn = remoteSocket.getInputStream()

        // Forward any early bytes that arrived in the same TCP segment as CONNECT
        if (leftoverLength > 0) {
            remoteOut.write(leftoverBytes, 0, leftoverLength)
            txBytes.addAndGet(leftoverLength.toLong())
        }

        pipeBidirectionally(
            clientSocket = clientSocket,
            clientIn = clientIn,
            clientOut = clientOut,
            remoteSocket = remoteSocket,
            remoteIn = remoteIn,
            remoteOut = remoteOut,
            dispatcher = dispatcher
        )
        return remoteSocket
    }

    private suspend fun handleHttpForward(
        request: ParsedRequestHead,
        leftoverBytes: ByteArray,
        leftoverLength: Int,
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        dispatcher: CoroutineDispatcher
    ): Socket? {
        val targetInfo = resolveHttpTarget(request)
        if (targetInfo == null) {
            writeHttpError(clientOut, 400, "Bad Request")
            return null
        }

        val remoteSocket = try {
            openUpstreamSocket(targetInfo.host, targetInfo.port)
        } catch (e: SocketTimeoutException) {
            writeHttpError(clientOut, 504, "Gateway Timeout")
            return null
        } catch (e: IOException) {
            writeHttpError(clientOut, 502, "Bad Gateway")
            return null
        }

        activeSockets.add(remoteSocket)
        clientSocket.soTimeout = 0
        remoteSocket.soTimeout = 0

        val remoteOut = remoteSocket.getOutputStream()
        val remoteIn = remoteSocket.getInputStream()

        // Rebuild request head in origin-form with clean non-proxy headers
        val outboundHead = buildString(512) {
            append(request.method)
            append(' ')
            append(targetInfo.originPath)
            append(' ')
            append(request.httpVersion)
            append("\r\n")

            var hasHostHeader = false
            for ((name, value) in request.headers) {
                val lower = name.lowercase()
                if (lower in HOP_BY_HOP_HEADERS) continue
                if (lower == "host") hasHostHeader = true
                append(name).append(": ").append(value).append("\r\n")
            }
            if (!hasHostHeader) {
                val hostHeader = if (targetInfo.port == 80) {
                    targetInfo.host
                } else {
                    "${targetInfo.host}:${targetInfo.port}"
                }
                append("Host: ").append(hostHeader).append("\r\n")
            }
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)

        remoteOut.write(outboundHead)

        val contentLength = request.headers
            .firstOrNull { it.first.equals("Content-Length", ignoreCase = true) }
            ?.second
            ?.trim()
            ?.toLongOrNull()

        val hasChunkedTransfer = request.headers.any {
            it.first.equals("Transfer-Encoding", ignoreCase = true) &&
                it.second.contains("chunked", ignoreCase = true)
        }

        var remainingBody = contentLength ?: 0L
        if (leftoverLength > 0 && ((contentLength != null && contentLength > 0L) || hasChunkedTransfer)) {
            val toWrite = if (contentLength != null) {
                minOf(leftoverLength.toLong(), remainingBody).toInt()
            } else {
                leftoverLength
            }
            if (toWrite > 0) {
                remoteOut.write(leftoverBytes, 0, toWrite)
                txBytes.addAndGet(toWrite.toLong())
                if (contentLength != null) {
                    remainingBody -= toWrite.toLong()
                }
            }
        }

        coroutineScope {
            val bodyUploadJob = if (remainingBody > 0L || hasChunkedTransfer) {
                launch(dispatcher) {
                    try {
                        if (contentLength != null && remainingBody > 0L) {
                            copyFixedBytes(clientIn, remoteOut, remainingBody, txBytes)
                        } else if (hasChunkedTransfer) {
                            copyStream(clientIn, remoteOut, txBytes)
                        }
                        try {
                            remoteSocket.shutdownOutput()
                        } catch (_: IOException) {
                            // Ignore if remote already closed
                        }
                    } catch (_: IOException) {
                        closeQuietly(remoteSocket)
                    }
                }
            } else {
                null
            }

            try {
                copyStream(remoteIn, clientOut, rxBytes)
            } finally {
                bodyUploadJob?.cancel()
            }
        }

        return remoteSocket
    }

    /**
     * Runs remote->client (the primary high-throughput download direction) directly on the
     * current coroutine thread and spawns only 1 child coroutine for client->remote, cutting
     * thread overhead in half compared to spawning 2 child coroutines.
     */
    private suspend fun pipeBidirectionally(
        clientSocket: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        remoteSocket: Socket,
        remoteIn: InputStream,
        remoteOut: OutputStream,
        dispatcher: CoroutineDispatcher
    ) = coroutineScope {
        val clientToRemote = launch(dispatcher) {
            try {
                copyStream(clientIn, remoteOut, txBytes)
                try {
                    remoteSocket.shutdownOutput()
                } catch (_: IOException) {
                    // Socket may already be closed
                }
            } catch (_: IOException) {
                closeQuietly(remoteSocket)
            }
        }

        try {
            copyStream(remoteIn, clientOut, rxBytes)
            try {
                clientSocket.shutdownOutput()
            } catch (_: IOException) {
                // Socket may already be closed
            }
        } catch (_: IOException) {
            closeQuietly(clientSocket)
        } finally {
            clientToRemote.join()
        }
    }

    @Throws(IOException::class)
    private fun openUpstreamSocket(host: String, port: Int): Socket {
        val addresses = resolveHostAddresses(host)
        val factory = upstreamSocketFactoryProvider()

        var lastException: IOException? = null
        // Try up to 3 resolved addresses (IPv4 preferred first for reliable mobile carrier paths)
        for (address in addresses.take(3)) {
            val socket = factory.createSocket()
            try {
                tuneSocket(socket)
                socket.connect(InetSocketAddress(address, port), UPSTREAM_CONNECT_TIMEOUT_MS)
                return socket
            } catch (e: IOException) {
                closeQuietly(socket)
                lastException = e
            }
        }

        // Fallback to direct hostname connect if custom resolution yielded nothing
        val fallbackSocket = factory.createSocket()
        try {
            tuneSocket(fallbackSocket)
            fallbackSocket.connect(InetSocketAddress(host, port), UPSTREAM_CONNECT_TIMEOUT_MS)
            return fallbackSocket
        } catch (e: IOException) {
            closeQuietly(fallbackSocket)
            throw lastException ?: e
        }
    }

    private fun resolveHostAddresses(host: String): List<InetAddress> {
        val key = host.lowercase()
        val now = System.nanoTime() / 1_000_000L
        val cached = dnsCache[key]
        if (cached != null && now < cached.expiresAtMs) {
            return cached.addresses
        }

        val resolved = try {
            upstreamDnsResolver(host).toList()
        } catch (_: Exception) {
            emptyList()
        }

        if (resolved.isEmpty()) return emptyList()

        // Prefer IPv4 addresses first to avoid IPv6 blackhole delays on cellular NAT64/dual-stack networks
        val sorted = resolved.sortedBy { if (it is Inet4Address) 0 else 1 }
        if (dnsCache.size > MAX_DNS_CACHE_ENTRIES) {
            dnsCache.clear()
        }
        dnsCache[key] = CachedDnsEntry(sorted, now + DNS_CACHE_TTL_MS)
        return sorted
    }

    private fun tuneSocket(socket: Socket) {
        runCatching {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.receiveBufferSize = SOCKET_BUFFER_SIZE
            socket.sendBufferSize = SOCKET_BUFFER_SIZE
        }
    }

    private fun copyStream(
        input: InputStream,
        output: OutputStream,
        counter: AtomicLong
    ) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            counter.addAndGet(read.toLong())
        }
    }

    private fun copyFixedBytes(
        input: InputStream,
        output: OutputStream,
        bytesToCopy: Long,
        counter: AtomicLong
    ) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var remaining = bytesToCopy
        while (remaining > 0L) {
            val maxRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, maxRead)
            if (read == -1) break
            output.write(buffer, 0, read)
            remaining -= read.toLong()
            counter.addAndGet(read.toLong())
        }
    }

    /**
     * Reads the HTTP request head in chunked syscalls (up to 8 KB per read) rather than
     * byte-by-byte, splits cleanly at "\r\n\r\n", and returns any extra bytes already
     * received in the same TCP packet so zero TLS/body bytes are lost.
     */
    @Throws(IOException::class)
    private fun readHeaderBlockBuffered(input: InputStream): HeaderReadResult? {
        val out = ByteArrayOutputStream(1024)
        val chunk = ByteArray(HEADER_CHUNK_SIZE)

        while (out.size() < MAX_HEADER_BYTES) {
            val read = input.read(chunk)
            if (read == -1) {
                if (out.size() == 0) return null
                return HeaderReadResult(out.toByteArray(), ByteArray(0), 0)
            }

            val previousSize = out.size()
            out.write(chunk, 0, read)
            val combined = out.toByteArray()
            val searchStart = (previousSize - 3).coerceAtLeast(0)
            val endIndex = findHeaderEndIndex(combined, searchStart)
            if (endIndex != -1) {
                val headerBytes = combined.copyOfRange(0, endIndex)
                val leftoverLen = combined.size - endIndex
                val leftover = if (leftoverLen > 0) {
                    combined.copyOfRange(endIndex, combined.size)
                } else {
                    ByteArray(0)
                }
                return HeaderReadResult(headerBytes, leftover, leftoverLen)
            }
        }
        throw IOException("HTTP header exceeded $MAX_HEADER_BYTES bytes")
    }

    private fun findHeaderEndIndex(bytes: ByteArray, startFrom: Int): Int {
        val limit = bytes.size - 3
        var i = startFrom
        while (i < limit) {
            if (bytes[i] == '\r'.code.toByte() &&
                bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() &&
                bytes[i + 3] == '\n'.code.toByte()
            ) {
                return i + 4
            }
            i++
        }
        return -1
    }

    private fun writeHttpError(output: OutputStream, statusCode: Int, reason: String) {
        try {
            val response = ("HTTP/1.1 $statusCode $reason\r\n" +
                "Content-Type: text/plain\r\n" +
                "Connection: close\r\n" +
                "Content-Length: ${reason.length}\r\n" +
                "\r\n$reason").toByteArray(StandardCharsets.US_ASCII)
            output.write(response)
            output.flush()
            rxBytes.addAndGet(response.size.toLong())
        } catch (_: IOException) {
            // Client already disconnected
        }
    }

    private fun closeQuietly(socket: Socket?) {
        if (socket == null) return
        try {
            if (!socket.isClosed) socket.close()
        } catch (_: IOException) {
            // Ignore close errors
        }
    }

    private fun closeQuietly(socket: ServerSocket?) {
        if (socket == null) return
        try {
            if (!socket.isClosed) socket.close()
        } catch (_: IOException) {
            // Ignore close errors
        }
    }

    internal data class ParsedRequestHead(
        val method: String,
        val target: String,
        val httpVersion: String,
        val headers: List<Pair<String, String>>
    )

    internal data class HostPort(
        val host: String,
        val port: Int
    )

    internal data class HttpTarget(
        val host: String,
        val port: Int,
        val originPath: String
    )

    internal fun parseHttpRequestHead(rawBytes: ByteArray): ParsedRequestHead? {
        val text = String(rawBytes, StandardCharsets.ISO_8859_1)
        val lines = text.split("\r\n")
        val requestLine = lines.firstOrNull()?.trim().orEmpty()
        if (requestLine.isEmpty()) return null

        val parts = requestLine.split(' ')
        if (parts.size < 3) return null

        val method = parts[0].trim()
        val target = parts[1].trim()
        val version = parts[2].trim()
        if (method.isEmpty() || target.isEmpty() || !version.startsWith("HTTP/")) return null

        val headers = mutableListOf<Pair<String, String>>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val name = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                headers.add(name to value)
            }
        }
        return ParsedRequestHead(method, target, version, headers)
    }

    internal fun parseHostPort(authority: String, defaultPort: Int): HostPort? {
        val trimmed = authority.trim()
        if (trimmed.isEmpty()) return null

        // Handle IPv6 literal: [2001:db8::1]:443 or [2001:db8::1]
        if (trimmed.startsWith("[")) {
            val closingBracket = trimmed.indexOf(']')
            if (closingBracket <= 1) return null
            val host = trimmed.substring(1, closingBracket)
            val remainder = trimmed.substring(closingBracket + 1)
            val port = if (remainder.startsWith(":")) {
                remainder.substring(1).toIntOrNull() ?: return null
            } else if (remainder.isEmpty()) {
                defaultPort
            } else {
                return null
            }
            return if (port in 1..65535) HostPort(host, port) else null
        }

        val lastColon = trimmed.lastIndexOf(':')
        if (lastColon > 0) {
            val host = trimmed.substring(0, lastColon).trim()
            val port = trimmed.substring(lastColon + 1).trim().toIntOrNull() ?: return null
            return if (host.isNotEmpty() && port in 1..65535) HostPort(host, port) else null
        }

        return HostPort(trimmed, defaultPort)
    }

    internal fun resolveHttpTarget(request: ParsedRequestHead): HttpTarget? {
        val target = request.target
        if (target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) {
            val uri = try {
                URI(target)
            } catch (_: Exception) {
                return null
            }
            val host = uri.host ?: return null
            val defaultPort = if (target.startsWith("https://", ignoreCase = true)) 443 else 80
            val port = if (uri.port > 0) uri.port else defaultPort
            val rawPath = uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/"
            val originPath = if (uri.rawQuery != null) "$rawPath?${uri.rawQuery}" else rawPath
            return HttpTarget(host, port, originPath)
        }

        val hostHeader = request.headers
            .firstOrNull { it.first.equals("Host", ignoreCase = true) }
            ?.second
            ?: return null

        val hostPort = parseHostPort(hostHeader, defaultPort = 80) ?: return null
        val originPath = if (target.startsWith("/")) target else "/$target"
        return HttpTarget(hostPort.host, hostPort.port, originPath)
    }
}
