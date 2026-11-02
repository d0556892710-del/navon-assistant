package com.navon.assistant

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for Google's Gemini API (generateContent) with function calling.
 * Uses a free API key from https://aistudio.google.com (no credit card).
 *
 * It keeps the running conversation internally so AgentService only has to
 * add the user command, read actions, and push back action results.
 */
class GeminiClient(private val apiKey: String, private val model: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json".toMediaType()

    /** Full conversation history in Gemini "contents" format. */
    private val contents = JSONArray()

    data class ToolUse(val name: String, val args: JSONObject)
    data class Result(val toolUses: List<ToolUse>, val text: String)

    fun addUserText(text: String) {
        contents.put(content("user", JSONArray().put(JSONObject().put("text", text))))
    }

    /** Sends the current history and returns the model's chosen action(s). */
    fun send(): Result {
        val body = JSONObject().apply {
            put("system_instruction", JSONObject()
                .put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            put("contents", contents)
            put("tools", TOOLS)
            put("tool_config", JSONObject().put(
                "function_calling_config", JSONObject().put("mode", "ANY")))
            put("generationConfig", JSONObject()
                .put("temperature", 0)
                .put("maxOutputTokens", 1024))
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody(mediaType))
            .build()

        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw RuntimeException("שגיאת Gemini (${resp.code}): ${raw.take(300)}")
            }
            val json = JSONObject(raw)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                throw RuntimeException("Gemini לא החזיר תשובה (אולי נחסם). פרטים: ${raw.take(200)}")
            }
            val modelContent = candidates.getJSONObject(0).optJSONObject("content")
                ?: JSONObject().put("role", "model").put("parts", JSONArray())
            // Keep the model turn in history so context is preserved.
            contents.put(modelContent)

            val parts = modelContent.optJSONArray("parts") ?: JSONArray()
            val toolUses = ArrayList<ToolUse>()
            val textSb = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                when {
                    part.has("functionCall") -> {
                        val fc = part.getJSONObject("functionCall")
                        toolUses.add(
                            ToolUse(fc.optString("name"), fc.optJSONObject("args") ?: JSONObject())
                        )
                    }
                    part.has("text") -> textSb.append(part.optString("text"))
                }
            }
            return Result(toolUses, textSb.toString())
        }
    }

    /** Pushes the results of executed actions back to the model. */
    fun addFunctionResults(results: List<Pair<String, String>>) {
        val parts = JSONArray()
        for ((name, result) in results) {
            parts.put(JSONObject().put("functionResponse", JSONObject()
                .put("name", name)
                .put("response", JSONObject().put("result", result))))
        }
        contents.put(content("user", parts))
    }

    private fun content(role: String, parts: JSONArray): JSONObject =
        JSONObject().put("role", role).put("parts", parts)

    companion object {
        private val SYSTEM_PROMPT = """
            אתה "נבון", עוזר שמפעיל טלפון אנדרואיד עבור המשתמש.
            אתה מקבל פקודה בעברית או באנגלית, ותיאור של האלמנטים על המסך הנוכחי.
            המטרה שלך היא לבצע את הפקודה שלב-אחר-שלב באמצעות הכלים (הפונקציות) הזמינים.

            כללים:
            - בכל תור, קרא לפונקציה אחת שמקדמת את המשימה.
            - אחרי כל פעולה תקבל את מצב המסך המעודכן. התבסס עליו, אל תנחש.
            - כדי לפתוח אפליקציה השתמש ב-open_app. כדי ללחוץ על כפתור/איש קשר/טקסט
              השתמש ב-tap עם הטקסט המדויק שמופיע על המסך.
            - להקלדה בשדה טקסט, קודם לחץ על השדה ואז השתמש ב-input_text.
            - אם המסך לא השתנה כמצופה, נסה גישה אחרת או גלול.
            - כשסיימת את המשימה במלואה, קרא ל-finish עם סיכום קצר בעברית.
            - אם חסר לך מידע הכרחי מהמשתמש (למשל איזה איש קשר), קרא ל-ask_user.
            - אל תבצע פעולות מסוכנות (מחיקה, תשלום, שליחת כסף) בלי שהמשתמש ביקש זאת מפורשות.
        """.trimIndent()

        /** Function declarations — one tools entry with all functions. */
        val TOOLS: JSONArray = JSONArray().apply {
            val fns = JSONArray()
            fns.put(fn("open_app", "פתח אפליקציה לפי שם (עברית או אנגלית).",
                obj().put("query", strProp("שם האפליקציה, למשל 'וואטסאפ'")), listOf("query")))
            fns.put(fn("tap", "לחץ על אלמנט על המסך לפי הטקסט שלו.",
                obj().put("text", strProp("הטקסט שמופיע על האלמנט שרוצים ללחוץ עליו")),
                listOf("text")))
            fns.put(fn("input_text", "הקלד טקסט לתוך שדה הטקסט הפעיל.",
                obj().put("text", strProp("הטקסט להקלדה")), listOf("text")))
            fns.put(fn("swipe", "גלול את המסך.",
                obj().put("direction", enumProp("כיוון הגלילה",
                    listOf("up", "down", "left", "right"))), listOf("direction")))
            fns.put(fn("press", "לחץ על כפתור מערכת.",
                obj().put("button", enumProp("הכפתור",
                    listOf("back", "home", "recents", "notifications"))), listOf("button")))
            fns.put(fn("wait", "המתן מספר שניות שהמסך ייטען.",
                obj().put("seconds", JSONObject().put("type", "number")
                    .put("description", "מספר שניות")), listOf("seconds")))
            fns.put(fn("ask_user", "שאל את המשתמש שאלה כשחסר מידע הכרחי.",
                obj().put("question", strProp("השאלה למשתמש")), listOf("question")))
            fns.put(fn("finish", "סיים את המשימה והחזר סיכום.",
                obj().put("message", strProp("סיכום קצר של מה שבוצע")), listOf("message")))
            put(JSONObject().put("function_declarations", fns))
        }

        private fun fn(name: String, desc: String, props: JSONObject, required: List<String>) =
            JSONObject()
                .put("name", name)
                .put("description", desc)
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", props)
                    .put("required", JSONArray(required)))

        private fun obj() = JSONObject()
        private fun strProp(desc: String) =
            JSONObject().put("type", "string").put("description", desc)
        private fun enumProp(desc: String, values: List<String>) =
            JSONObject().put("type", "string").put("description", desc)
                .put("enum", JSONArray(values))
    }
}
