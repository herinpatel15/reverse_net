package dev.reversenet

/** Tiny logger so core code stays free of android.* and is unit-testable. */
object RnLog {
    const val TAG = "ReverseNet"

    @Volatile
    var sink: (level: Char, msg: String) -> Unit = { level, msg -> println("$level/$TAG: $msg") }

    fun d(msg: String) = sink('D', msg)
    fun i(msg: String) = sink('I', msg)
    fun w(msg: String) = sink('W', msg)
}
