package com.audiobridge.receiver

import com.audiobridge.protocol.ClickTrackGenerator
import com.audiobridge.protocol.Pcm
import com.audiobridge.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmTest {

    @Test
    fun `mono to stereo duplicates every sample`() {
        val mono = byteArrayOf(1, 2, 3, 4, 0, 0)
        val stereo = Pcm.monoToStereo(mono)

        assertEquals(12, stereo.size)
        assertEquals(byteArrayOf(1, 2, 1, 2, 3, 4, 3, 4, 0, 0, 0, 0).toList(), stereo.toList())
    }

    @Test
    fun `downmix averages left and right`() {
        // two frames: left = 100, right = 60 -> 80 ; left = -20, right = 20 -> 0
        val stereo = ByteArray(8)
        Pcm.putSample(stereo, 0, 100)
        Pcm.putSample(stereo, 2, 60)
        Pcm.putSample(stereo, 4, -20)
        Pcm.putSample(stereo, 6, 20)

        val mono = ByteArray(4)
        Pcm.downmixStereoToMono(stereo, mono)

        assertEquals(80, Pcm.sampleAt(mono, 0).toInt())
        assertEquals(0, Pcm.sampleAt(mono, 2).toInt())
    }

    @Test
    fun `downmix of a full frame keeps the size`() {
        val stereo = ByteArray(Protocol.FRAME_BYTES_STEREO) { ((it * 13) and 0xFF).toByte() }
        val mono = ByteArray(Protocol.FRAME_BYTES_MONO)
        Pcm.downmixStereoToMono(stereo, mono)
        assertEquals(Protocol.FRAME_BYTES_MONO, mono.size)
    }

    @Test
    fun `peak absolute detects silence and loud samples`() {
        assertEquals(0, Pcm.peakAbsolute(ByteArray(1920)))
        val loud = ByteArray(1920)
        Pcm.putSample(loud, 100, Short.MAX_VALUE)
        assertEquals(32767, Pcm.peakAbsolute(loud))
    }

    @Test
    fun `sample roundtrip handles negative values`() {
        val buffer = ByteArray(2)
        Pcm.putSample(buffer, 0, (-1234).toShort())
        assertEquals(-1234, Pcm.sampleAt(buffer, 0).toInt())
    }
}

class ClickTrackGeneratorTest {

    @Test
    fun `click appears once per second and is silent elsewhere`() {
        val generator = ClickTrackGenerator()
        // 100 frames = 1 second
        val withClick = (0 until 100).filter { generator.frameContainsClick(it.toLong()) }
        assertEquals((0 until 4).toList(), withClick) // 40 ms click = 4 frames

        for (frameIndex in 0 until 100L) {
            val pcm = generator.frame(frameIndex)
            assertEquals(Protocol.FRAME_BYTES_STEREO, pcm.size)
            val peak = Pcm.peakAbsolute(pcm)
            if (generator.frameContainsClick(frameIndex)) {
                assertTrue("click frame must be audible", peak > 10_000)
            } else {
                assertEquals("frames between clicks must be silent", 0, peak)
            }
        }
    }

    @Test
    fun `stereo click is identical on both channels`() {
        val generator = ClickTrackGenerator()
        val pcm = generator.frame(0)
        var i = 0
        while (i < pcm.size) {
            assertEquals(Pcm.sampleAt(pcm, i).toLong(), Pcm.sampleAt(pcm, i + 2).toLong())
            i += 4
        }
    }

    @Test
    fun `click never exceeds the amplitude limit`() {
        val generator = ClickTrackGenerator(amplitude = 1.0)
        for (frameIndex in 0 until 100L) {
            val peak = Pcm.peakAbsolute(generator.frame(frameIndex))
            assertTrue("click must stay in range", peak <= Short.MAX_VALUE.toInt())
        }
    }
}
