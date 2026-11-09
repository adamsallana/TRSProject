package com.trsproject.server

import kotlin.test.Test
import kotlin.test.assertEquals

class CallAudioBridgeTest {

    private fun pcm(vararg samples: Int): ByteArray =
        samples.flatMap { listOf((it and 0xFF).toByte(), ((it shr 8) and 0xFF).toByte()) }.toByteArray()

    @Test
    fun mulawMatchesG711ReferenceValues() {
        assertEquals(0xFF.toByte(), linearToMulaw(0))
        assertEquals(0x80.toByte(), linearToMulaw(32767))
        assertEquals(0x00.toByte(), linearToMulaw(-32768))
        assertEquals(0xF2.toByte(), linearToMulaw(100))
        assertEquals(0x72.toByte(), linearToMulaw(-100))
    }

    @Test
    fun eightKhzPassesThroughOneBytePerSample() {
        val out = PcmToMulawEncoder(8000).encode(pcm(0, 100, -100, 32767))
        assertEquals(listOf(0xFF, 0xF2, 0x72, 0x80).map { it.toByte() }, out.toList())
    }

    @Test
    fun sixteenKhzIsAveragedDownToEightKhz() {
        val out = PcmToMulawEncoder(16000).encode(pcm(50, 150, -50, -150))
        assertEquals(listOf(linearToMulaw(100), linearToMulaw(-100)), out.toList())
    }

    @Test
    fun samplesSplitAcrossFramesCarryOver() {
        val encoder = PcmToMulawEncoder(16000)
        val all = pcm(50, 150, -50, -150)
        val first = encoder.encode(all.copyOfRange(0, 3)) // splits a sample in half
        val second = encoder.encode(all.copyOfRange(3, all.size))
        assertEquals(listOf(linearToMulaw(100), linearToMulaw(-100)), (first + second).toList())
    }
}
