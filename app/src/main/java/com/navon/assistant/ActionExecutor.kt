package com.navon.assistant

import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Translates the high-level tool calls Claude emits into concrete device
 * actions, and returns a short Hebrew result string that is fed back to Claude.
 */
class ActionExecutor(private val context: Context) {

    /** Common app aliases so the user can say "וואטסאפ" / "whatsapp". */
    private val aliases = mapOf(
        "whatsapp" to "com.whatsapp",
        "וואטסאפ" to "com.whatsapp",
        "ואטסאפ" to "com.whatsapp",
        "telegram" to "org.telegram.messenger",
        "טלגרם" to "org.telegram.messenger",
        "instagram" to "com.instagram.android",
        "אינסטגרם" to "com.instagram.android",
        "chrome" to "com.android.chrome",
        "כרום" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "ג'ימייל" to "com.google.android.gm",
        "settings" to "com.android.settings",
        "הגדרות" to "com.android.settings",
        "youtube" to "com.google.android.youtube",
        "יוטיוב" to "com.google.android.youtube",
        "maps" to "com.google.android.apps.maps",
        "מפות" to "com.google.android.apps.maps",
        "spotify" to "com.spotify.music"
    )

    /**
     * Runs one tool call. [name] is the tool name, [input] its JSON arguments.
     * Returns a short description of what happened, for Claude's next turn.
     */
    fun execute(name: String, input: JSONObject): String {
        val svc = NavonAccessibilityService.instance
            ?: return "שגיאה: שירות הנגישות אינו פעיל. בקש מהמשתמש להפעיל אותו בהגדרות."

        val result = when (name) {
            "open_app" -> openApp(input.optString("query"))
            "tap" -> {
                if (input.has("index")) svc.tapByIndex(input.optInt("index"))
                else svc.tapByText(input.optString("text"))
            }
            "input_text" -> svc.inputText(input.optString("text"))
            "swipe" -> svc.swipe(input.optString("direction"))
            "press" -> svc.pressButton(input.optString("button"))
            "wait" -> {
                val secs = input.optDouble("seconds", 1.0)
                Thread.sleep((secs * 1000).toLong().coerceIn(0, 10000))
                "המתנתי $secs שניות."
            }
            else -> "כלי לא מוכר: $name"
        }

        // Give the UI a moment to settle before the next screen read.
        if (name != "wait") Thread.sleep(800)
        return result
    }

    private fun openApp(query: String): String {
        if (query.isBlank()) return "לא צוין שם אפליקציה."
        val pm = context.packageManager
        val q = query.trim().lowercase()

        // 1) direct alias
        aliases[q]?.let { pkg ->
            pm.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
                return "פתחתי את $query."
            }
        }

        // 2) match against installed launchable apps by visible label
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        for (info in apps) {
            val label = info.loadLabel(pm).toString().lowercase()
            if (label.contains(q)) {
                val pkg = info.activityInfo.packageName
                pm.getLaunchIntentForPackage(pkg)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                    return "פתחתי את ${info.loadLabel(pm)}."
                }
            }
        }
        return "לא מצאתי אפליקציה בשם \"$query\"."
    }
}
