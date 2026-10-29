package com.audiobridge.sender

import com.audiobridge.protocol.FrameCodec
import com.audiobridge.protocol.FrameHeader
import com.audiobridge.protocol.Protocol
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

/**
 * Sends 10 ms PCM frames as chunked UDP datagrams.
 *
 * One socket, one reusable buffer, no allocation per packet. Each frame is cut into
 * at most [Protocol.MAX_PAYLOAD] byte chunks so the resulting datagrams stay below the
 * Ethernet/Wi-Fi MTU and never get IP-fragmented.
 */
class UdpSender(host: String, private val port: Int) {

    private val socket = DatagramSocket().apply {
        sendBufferSize = 256 * 1024
    }

    private val address: InetAddress = InetAddress.getByName(host)
    private val buffer = ByteArray(Protocol.HEADER_SIZE + Protocol.MAX_PAYLOAD)
    private val packet = DatagramPacket(buffer, buffer.size, address, port)

    val streamId: Int = SecureRandom().nextInt()

    var packetsSent: Long = 0
        private set
    var bytesSent: Long = 0
        private set
    var sendErrors: Long = 0
        private set

    val target: String = "$host:$port"

    /** Returns false when the datagram could not be sent. */
    fun send(sequence: Long, timestampMs: Long, pcm: ByteArray, flags: Int): Boolean {
        val chunkCount = Protocol.chunksFor(pcm.size)
        if (chunkCount == 0 || chunkCount > Protocol.MAX_CHUNKS) return false

        for (chunk in 0 until chunkCount) {
            val offset = chunk * Protocol.MAX_PAYLOAD
            val length = minOf(Protocol.MAX_PAYLOAD, pcm.size - offset)
            val header = FrameHeader(
                flags = flags,
                streamId = streamId,
                sequence = sequence,
                timestampMs = timestampMs,
                chunkIndex = chunk,
                chunkCount = chunkCount,
                frameBytes = pcm.size,
            )
            val total = FrameCodec.encodeInto(header, pcm, offset, length, buffer)
            packet.setData(buffer, 0, total)
            try {
                socket.send(packet)
                packetsSent++
                bytesSent += total
            } catch (e: IOException) {
                sendErrors++
                return false
            }
        }
        return true
    }

    fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}
