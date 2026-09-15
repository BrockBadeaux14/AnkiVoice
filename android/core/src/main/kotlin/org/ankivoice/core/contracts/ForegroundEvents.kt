package org.ankivoice.core.contracts

/** Activity visibility only. Audio focus and screen-off signals belong to :speech. */
enum class ForegroundEvent { RESUME, PAUSE, STOP }

/** Delivered on the session's owning thread. RESUME never authorizes restarting study. */
fun interface ForegroundEventPort {
    fun onForegroundEvent(event: ForegroundEvent)
}
