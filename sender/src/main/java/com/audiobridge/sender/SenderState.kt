package com.audiobridge.sender

/** Snapshot of the sender state, polled by the activity every 500 ms. */
object SenderState {

    enum class Phase { IDLE, WAITING, STREAMING, ERROR }

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        val status: String = "",
        val detail: String = "",
        val hint: String? = null,
        val streaming: Boolean = false,
        val discovering: Boolean = false,
        val receivers: List<NsdDiscovery.ReceiverInfo> = emptyList(),
    )

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    fun update(transform: (Snapshot) -> Snapshot) {
        snapshot = transform(snapshot)
    }

    fun setStatus(status: String, detail: String = snapshot.detail) =
        update { it.copy(status = status, detail = detail) }

    fun setStreaming(status: String, detail: String = "") =
        update {
            it.copy(
                phase = Phase.STREAMING,
                status = status,
                detail = detail,
                streaming = true,
            )
        }

    fun setWaiting(status: String) =
        update { it.copy(phase = Phase.WAITING, status = status, streaming = false) }

    fun setError(message: String) =
        update { it.copy(phase = Phase.ERROR, status = message, streaming = false) }

    fun setIdle(status: String = "") =
        update { it.copy(phase = Phase.IDLE, status = status, streaming = false, detail = "") }

    fun setHint(hint: String?) = update { it.copy(hint = hint) }

    fun setDiscovering(discovering: Boolean, message: String) = update {
        it.copy(discovering = discovering, detail = message)
    }

    fun addReceiver(receiver: NsdDiscovery.ReceiverInfo) = update {
        if (it.receivers.any { r -> r.key == receiver.key }) it
        else it.copy(receivers = it.receivers + receiver)
    }

    fun clearReceivers() = update { it.copy(receivers = emptyList()) }
}
