package com.navon.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The long-running "brain" service. It drives the agent loop:
 * send screen → Claude picks an action → execute → read screen → repeat,
 * until Claude calls finish/ask_user, the step limit is hit, or the user stops.
 */
class AgentService : Service() {

    companion object {
        const val EXTRA_COMMAND = "command"
        const val ACTION_STOP = "com.navon.assistant.STOP"
        private const val CHANNEL_ID = "navon_actions"
        private const val NOTIF_ID = 42
        private const val MAX_STEPS = 30
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AgentState.cancelRequested = true
            AgentState.log("⏹️ עצירה התבקשה…")
            return START_NOT_STICKY
        }

        val command = intent?.getStringExtra(EXTRA_COMMAND)?.trim().orEmpty()
        startForeground(NOTIF_ID, buildNotification())

        if (command.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        AgentState.cancelRequested = false
        AgentState.running = true
        AgentState.notifyUi()

        job = scope.launch {
            try {
                runLoop(command)
            } catch (e: Exception) {
                AgentState.log("❌ שגיאה: ${e.message}")
            } finally {
                AgentState.running = false
                AgentState.notifyUi()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun runLoop(command: String) {
        val settings = SettingsStore(this)
        if (settings.apiKey.isBlank()) {
            AgentState.log("❌ חסר מפתח API. היכנס להגדרות והדבק מפתח מ-console.anthropic.com.")
            return
        }
        if (!NavonAccessibilityService.isConnected) {
            AgentState.log("❌ שירות הנגישות כבוי. הפעל אותו בהגדרות ← נגישות ← נבון.")
            return
        }

        val client = ClaudeClient(settings.apiKey, settings.model)
        val executor = ActionExecutor(this)
        val messages = JSONArray()

        AgentState.log("👤 $command")

        // First user turn: the command + the current screen.
        val firstScreen = NavonAccessibilityService.instance?.dumpScreen() ?: ""
        messages.put(userText("$command\n\n--- מצב המסך ---\n$firstScreen"))

        var step = 0
        while (step < MAX_STEPS) {
            if (AgentState.cancelRequested) {
                AgentState.log("⏹️ המשימה נעצרה.")
                return
            }
            step++

            val result = client.send(messages)
            // Record Claude's turn verbatim so the conversation stays valid.
            messages.put(JSONObject().put("role", "assistant").put("content", result.assistantContent))

            if (result.text.isNotBlank()) AgentState.log("🤖 ${result.text.trim()}")

            if (result.toolUses.isEmpty()) {
                // Claude ended without acting.
                return
            }

            val toolResults = JSONArray()
            var terminate = false
            for (tu in result.toolUses) {
                when (tu.name) {
                    "finish" -> {
                        val msg = tu.input.optString("message", "המשימה הושלמה.")
                        AgentState.log("✅ $msg")
                        toolResults.put(toolResult(tu.id, "סיום אושר."))
                        terminate = true
                    }
                    "ask_user" -> {
                        val q = tu.input.optString("question")
                        AgentState.log("❓ נבון שואל: $q\n(ענה בשדה הפקודה ולחץ בצע)")
                        toolResults.put(toolResult(tu.id, "השאלה הועברה למשתמש."))
                        terminate = true
                    }
                    else -> {
                        AgentState.log("⚙️ ${tu.name} ${tu.input}")
                        val actionResult = executor.execute(tu.name, tu.input)
                        val newScreen = NavonAccessibilityService.instance?.dumpScreen() ?: ""
                        toolResults.put(
                            toolResult(tu.id, "$actionResult\n\n--- מצב המסך ---\n$newScreen")
                        )
                    }
                }
            }
            messages.put(JSONObject().put("role", "user").put("content", toolResults))
            if (terminate) return
        }
        AgentState.log("⚠️ הגעתי למגבלת הצעדים ($MAX_STEPS). המשימה אולי לא הושלמה.")
    }

    // --- JSON helpers -----------------------------------------------------

    private fun userText(text: String): JSONObject =
        JSONObject().put("role", "user").put("content", text)

    private fun toolResult(id: String, content: String): JSONObject =
        JSONObject()
            .put("type", "tool_result")
            .put("tool_use_id", id)
            .put("content", content)

    // --- Notification -----------------------------------------------------

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_working))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }
}
