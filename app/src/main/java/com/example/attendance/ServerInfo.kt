package com.example.attendance

/**
 * Which Code.gs is live on the server. Every signed-in reply carries "sv" (Code.gs: SERVER_VERSION, written as a date and a number,
 * for example 2026-11-07.01). The Admin Panel (Users and App tabs) shows it next to [EXPECTED], so a Code.gs that was not pasted, or
 * pasted but not deployed as a New version, is seen at once.
 */
object ServerInfo {
    /** The Code.gs this app was built for. Raise it, together with SERVER_VERSION in Code.gs, whenever the app needs a newer Code.gs. */
    const val EXPECTED = "2026-11-07.01"

    enum class State {
        /** The server did not send a version: its Code.gs is older than this feature, or the newest one was not deployed. */
        UNKNOWN,
        /** The server runs an older Code.gs than this app was built for. */
        OLDER,
        /** The server runs the version this app was built for, or a newer one. */
        OK
    }

    /** The version text is "yyyy-MM-dd.nn", so plain text order is also date order. */
    fun state(sv: String?): State = when {
        sv.isNullOrBlank() -> State.UNKNOWN
        sv < EXPECTED -> State.OLDER
        else -> State.OK
    }
}
