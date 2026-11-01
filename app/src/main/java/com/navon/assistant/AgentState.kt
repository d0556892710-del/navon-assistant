package com.navon.assistant

/**
 * Shared, observable state between the agent service and the UI.
 * Kept deliberately simple (a singleton with a log + listener).
 */
object AgentState {
    private val logBuffer = StringBuilder()

    @Volatile var running: Boolean = false
    @Volatile var cancelRequested: Boolean = false

    /** UI sets this to receive log updates on the main thread. */
    var listener: ((fullLog: String, running: Boolean) -> Unit)? = null

    @Synchronized
    fun log(line: String) {
        logBuffer.append(line).append("\n\n")
        notifyUi()
    }

    @Synchronized
    fun clear() {
        logBuffer.setLength(0)
        notifyUi()
    }

    @Synchronized
    fun currentLog(): String = logBuffer.toString()

    fun notifyUi() {
        listener?.invoke(logBuffer.toString(), running)
    }
}
