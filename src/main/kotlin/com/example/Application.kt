package com.example

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import java.net.InetSocketAddress

val goodHttpResponse = "" +
"""HTTP/1.1 200 OK
Content-Type: text/plain
transfer-encoding: chunked

5
hello
0

"""

val badHttpResponse = "" +
"""HTTP/1.1 200 OK
Content-Type: text/plain
transfer-encoding: chunked

5
hello
5
h"""

class TcpServer(port: Int, private val httpResponse: String) {
    val tcpServer: ServerSocket

    init {
        tcpServer = aSocket(ActorSelectorManager(Dispatchers.IO))
            .tcp()
            .bind(InetSocketAddress("127.0.0.1", port))

        println("[TCP server] Started TCP server on port $port")
    }

    suspend fun runTcpServer() {
        println("[TCP server] Listening...")
        while (true) {
            val socket = tcpServer.accept()

            coroutineScope {
                launch {
                    try {
                        val input = socket.openReadChannel()
                        val output = socket.openWriteChannel(autoFlush = true)

                        println("[TCP server] Reading request...")
                        val lines = mutableListOf<String>()
                        var line: String?
                        do {
                            line = input.readUTF8Line()

                            if (line != null) {
                                lines.add(line)
                            }
                        } while (line != null && line != "")
                        val request = lines.joinToString(separator = "\n")

                        println("[TCP server] Received request:\n$request")

                        println("[TCP server] Responding...")
                        // Respond with static http response
                        output.writeStringUtf8(httpResponse)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        // Delay 1s so client can start reading
                        delay(1000)
                        // Close socket so client doesn't wait for the missing data,
                        // thus triggering EOFException
                        socket.close()
                    }
                }
            }
        }
    }
}

suspend fun isClientHealthy(httpClient: HttpClient): Boolean = try {
    val response = httpClient
        .request<HttpStatement>("http://127.0.0.1:$goodPort")
        .execute()

    response.status.value == 200
} catch (ex: Exception) {
    ex.printStackTrace()
    false
}

val goodPort = 10001
val badPort = 10002

fun main() {
    val goodServer = TcpServer(goodPort, goodHttpResponse)
    val badServer = TcpServer(badPort, badHttpResponse)

    GlobalScope.launch {
        badServer.runTcpServer()
    }
    GlobalScope.launch {
        goodServer.runTcpServer()
    }

    val httpClient = HttpClient(CIO) {
        expectSuccess = false
    }

    runBlocking {
        // Initially the client is healthy
        println("[Client] Is http client healthy? ${isClientHealthy(httpClient)}")

        // Break the client by streaming truncated response
        try {
            println("[Client] Making request...")
            httpClient
                .request<HttpStatement>("http://127.0.0.1:$badPort")
                .execute { response ->
                    val receiveChannel = response.receive<ByteReadChannel>()

                    println("[Client] Reading response...")
                    val chunks = mutableListOf<ByteArray>()
                    while (!receiveChannel.isClosedForRead) {
                        val packet = receiveChannel.readRemaining(
                            limit = DEFAULT_BUFFER_SIZE.toLong(),
                            headerSizeHint = 1
                        )

                        while (!packet.isEmpty) {
                            val bytes = packet.readBytes()
                            chunks.add(bytes)
                        }
                    }
                    val responseData = chunks.reduce(ByteArray::plus).decodeToString()

                    println("Response: ${response.status.value} $responseData")
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // The client is now unhealthy
        // All of these will fail with a copy of the initial EOFException exception.
        println("[Client] Is http client working? ${isClientHealthy(httpClient)}")
        println("[Client] Is http client working? ${isClientHealthy(httpClient)}")
        println("[Client] Is http client working? ${isClientHealthy(httpClient)}")
    }
}