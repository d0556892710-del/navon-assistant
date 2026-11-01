package com.navon.assistant

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal client for the Claude Messages API with tool use.
 * The API key is the user's own key from console.anthropic.com (pay-as-you-go),
 * which is separate from any Claude Pro subscription.
 */
class ClaudeClient(private val apiKey: String, private val model: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json".toMediaType()

    data class Result(
        val assistantContent: JSONArray, // raw content blocks to append to history
        val toolUses: List<ToolUse>,
        val text: String,                // any plain text Claude said
        val stopReason: String
    )

    data class ToolUse(val id: String, val name: String, val input: JSONObject)

    /** Sends the running [messages] history and returns the parsed reply. */
    fun send(messages: JSONArray): Result {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 1024)
            put("system", SYSTEM_PROMPT)
            put("tools", TOOLS)
            put("messages", messages)
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody(mediaType))
            .build()

        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw RuntimeException("שגיאת API (${resp.code}): ${raw.take(300)}")
            }
            val json = JSONObject(raw)
            val content = json.optJSONArray("content") ?: JSONArray()
            val toolUses = ArrayList<ToolUse>()
            val textSb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                when (block.optString("type")) {
                    "text" -> textSb.append(block.optString("text"))
                    "tool_use" -> toolUses.add(
                        ToolUse(
                            id = block.optString("id"),
                            name = block.optString("name"),
                            input = block.optJSONObject("input") ?: JSONObject()
                        )
                    )
                }
            }
            return Result(
                assistantContent = content,
                toolUses = toolUses,
                text = textSb.toString(),
                stopReason = json.optString("stop_reason")
            )
        }
    }

    companion object {
        private val SYSTEM_PROMPT = """
            אתה "נבון", עוזר שמפעיל טלפון אנדרואיד עבור המשתמש.
            אתה מקבל פקודה בעברית או באנגלית, ותיאור של האלמנטים על המסך הנוכחי.
            המטרה שלך היא לבצע את הפקודה שלב-אחר-שלב באמצעות הכלים הזמינים.

            כללים:
            - בכל תור, בחר פעולה אחת (כלי אחד) שמקדמת את המשימה.
            - אחרי כל פעולה תקבל את מצב המסך המעודכן. התבסס עליו, אל תנחש.
            - כדי לפתוח אפליקציה השתמש ב-open_app. כדי ללחוץ על כפתור/איש קשר/טקסט
              השתמש ב-tap עם הטקסט המדויק שמופיע על המסך.
            - להקלדה בשדה טקסט, קודם לחץ על השדה ואז השתמש ב-input_text.
            - אם המסך לא השתנה כמצופה, נסה גישה אחרת או גלול.
            - כשסיימת את המשימה במלואה, קרא ל-finish עם סיכום קצר בעברית.
            - אם אתה חסר מידע הכרחי מהמשתמש (למשל איזה איש קשר), קרא ל-ask_user.
            - אל תבצע פעולות מסוכנות (מחיקה, תשלום, שליחת כסף) בלי שהמשתמש ביקש זאת מפורשות.
        """.trimIndent()

        /** The tools Claude may call. Each maps to a method in ActionExecutor. */
        val TOOLS: JSONArray = JSONArray().apply {
            put(tool("open_app", "פתח אפליקציה לפי שם (עברית או אנגלית).",
                JSONObject().put("query", strProp("שם האפליקציה, למשל 'וואטסאפ'"))))
            put(tool("tap", "לחץ על אלמנט על המסך לפי הטקסט שלו, או לפי מספר אינדקס מרשימת האלמנטים.",
                JSONObject()
                    .put("text", strProp("הטקסט שמופיע על האלמנט שרוצים ללחוץ עליו"))
                    .put("index", JSONObject().put("type", "integer")
                        .put("description", "לחלופין, מספר האלמנט מרשימת המסך"))))
            put(tool("input_text", "הקלד טקסט לתוך שדה הטקסט הפעיל.",
                JSONObject().put("text", strProp("הטקסט להקלדה"))))
            put(tool("swipe", "גלול את המסך.",
                JSONObject().put("direction", JSONObject().put("type", "string")
                    .put("enum", JSONArray(listOf("up", "down", "left", "right")))
                    .put("description", "כיוון הגלילה"))))
            put(tool("press", "לחץ על כפתור מערכת.",
                JSONObject().put("button", JSONObject().put("type", "string")
                    .put("enum", JSONArray(listOf("back", "home", "recents", "notifications")))
                    .put("description", "הכפתור"))))
            put(tool("wait", "המתן מספר שניות שהמסך ייטען.",
                JSONObject().put("seconds", JSONObject().put("type", "number")
                    .put("description", "מספר שניות"))))
            put(tool("ask_user", "שאל את המשתמש שאלה כשחסר מידע הכרחי.",
                JSONObject().put("question", strProp("השאלה למשתמש"))))
            put(tool("finish", "סיים את המשימה והחזר סיכום.",
                JSONObject().put("message", strProp("סיכום קצר של מה שבוצע"))))
        }

        private fun tool(name: String, desc: String, props: JSONObject): JSONObject {
            val required = JSONArray()
            // first property is required by convention
            props.keys().asSequence().firstOrNull()?.let { required.put(it) }
            return JSONObject()
                .put("name", name)
                .put("description", desc)
                .put("input_schema", JSONObject()
                    .put("type", "object")
                    .put("properties", props)
                    .put("required", required))
        }

        private fun strProp(desc: String) =
            JSONObject().put("type", "string").put("description", desc)
    }
}
