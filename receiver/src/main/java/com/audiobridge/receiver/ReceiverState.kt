package com.audiobridge.receiver

/** Snapshot of the receiver state, polled by the activity every 400 ms. */
object ReceiverState {

    data class Snapshot(
        val listening: Boolean = false,
        val phase: String = "",
        val detail: String = "",
        val peer: String? = null,
        val localAddress: String = "",
        val port: Int = 0,
        val bufferMs: Int = 0,
        val targetMs: Int = 150,
        val configuredTargetMs: Int = 150,
        val lossPercent: Double = 0.0,
        val jitterMs: Int = 0,
        val receivedFrames: Long = 0,
        val lostFrames: Long = 0,
        val concealedFrames: Long = 0,
        val audioInfo: String = "",
        val error: String? = null,
    )

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    fun update(transform: (Snapshot) -> Snapshot) {
        snapshot = transform(snapshot)
    }

    fun setError(message: String) = update {
        it.copy(listening = false, phase = "", error = message, detail = message)
    }

    fun setIdle(detail: String) = update {
        it.copy(listening = false, phase = "", detail = detail, error = null)
    }
}
