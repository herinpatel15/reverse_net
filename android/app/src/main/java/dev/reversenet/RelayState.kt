package dev.reversenet

/** Status shared between [RelayService] and [MainActivity]. */
object RelayState {
    @Volatile var running = false
    @Volatile var status = "Stopped"
    @Volatile var debugDump = false
    @Volatile var listener: (() -> Unit)? = null

    fun update(text: String) {
        status = text
        listener?.invoke()
    }
}
