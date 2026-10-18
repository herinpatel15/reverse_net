package dev.reversenet.nat

/** Internet checksum (RFC 1071). */
object Checksum {
    /** One's-complement partial sum over [len] bytes of [data] from [off]. */
    fun partial(data: ByteArray, off: Int, len: Int, initial: Long = 0L): Long {
        var sum = initial
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        return sum
    }

    /** Fold carries and invert. A valid packet (checksum field included) yields 0. */
    fun finish(sum: Long): Int {
        var s = sum
        while (s ushr 16 != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return (s.inv() and 0xFFFF).toInt()
    }

    fun compute(data: ByteArray, off: Int, len: Int): Int = finish(partial(data, off, len))

    /** Sum of the IPv4 pseudo-header used by UDP and TCP. */
    fun pseudoHeader(src: Int, dst: Int, protocol: Int, length: Int): Long =
        (src ushr 16).toLong() + (src and 0xFFFF) +
            (dst ushr 16) + (dst and 0xFFFF) +
            protocol + length
}
