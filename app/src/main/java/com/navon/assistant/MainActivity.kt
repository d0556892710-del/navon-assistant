package com.navon.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.navon.assistant.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsStore

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val spoken = res.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) binding.inputCommand.setText(spoken)
    }

    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startSpeech() else toast("אין הרשאת מיקרופון") }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsStore(this)

        binding.btnRun.setOnClickListener { runCommand() }
        binding.btnMic.setOnClickListener { ensureMicThenSpeak() }
        binding.btnStop.setOnClickListener { stopAgent() }
        binding.btnSettings.setOnClickListener { showSettings() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Observe the agent log.
        AgentState.listener = { log, running ->
            runOnUiThread { renderState(log, running) }
        }
        renderState(AgentState.currentLog(), AgentState.running)
    }

    override fun onDestroy() {
        if (AgentState.listener != null) AgentState.listener = null
        super.onDestroy()
    }

    private fun renderState(log: String, running: Boolean) {
        binding.logView.text = log
        binding.btnStop.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnRun.isEnabled = !running
        binding.logScroll.post {
            binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun runCommand() {
        val cmd = binding.inputCommand.text.toString().trim()
        if (cmd.isEmpty()) { toast("כתוב פקודה"); return }

        if (settings.apiKey.isBlank()) {
            toast(getString(R.string.need_api_key)); showSettings(); return
        }
        if (!NavonAccessibilityService.isConnected) {
            promptAccessibility(); return
        }

        binding.inputCommand.setText("")
        val intent = Intent(this, AgentService::class.java)
            .putExtra(AgentService.EXTRA_COMMAND, cmd)
        ContextCompat.startForegroundService(this, intent)
        // Send the user to the home screen so the agent can drive other apps.
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
    }

    private fun stopAgent() {
        val intent = Intent(this, AgentService::class.java).setAction(AgentService.ACTION_STOP)
        startService(intent)
    }

    // --- Voice input ------------------------------------------------------

    private fun ensureMicThenSpeak() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) startSpeech()
        else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startSpeech() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "דבר עכשיו…")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            toast("זיהוי דיבור לא זמין במכשיר")
        }
    }

    // --- Settings ---------------------------------------------------------

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val apiField = view.findViewById<EditText>(R.id.inputApiKey)
        val modelField = view.findViewById<EditText>(R.id.inputModel)
        apiField.setText(settings.apiKey)
        modelField.setText(settings.model)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                settings.apiKey = apiField.text.toString()
                settings.model = modelField.text.toString()
                toast("נשמר")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- Accessibility prompt --------------------------------------------

    private fun promptAccessibility() {
        AlertDialog.Builder(this)
            .setMessage(R.string.need_accessibility)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
