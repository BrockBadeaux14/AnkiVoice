package org.ankivoice.core.contracts

/** Elapsed review time only. Never a wall clock, never used for scheduling. */
fun interface MonotonicClock {
    fun nowMs(): Long
}

/** The JVM's monotonic source. The app may supply a platform clock instead. */
object SystemMonotonicClock : MonotonicClock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000
}
