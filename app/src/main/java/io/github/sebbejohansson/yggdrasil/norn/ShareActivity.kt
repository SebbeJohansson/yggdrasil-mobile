package io.github.sebbejohansson.yggdrasil.norn

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.github.sebbejohansson.yggdrasil.YggApp
import io.github.sebbejohansson.yggdrasil.hermodr.MessageRequest
import io.github.sebbejohansson.yggdrasil.hermodr.Priority
import kotlinx.coroutines.launch

/**
 * "Share → Ygg" on text you sent (a mail, a Slack or Teams message): the
 * local model looks for promises in it and keeps them. No screen of its own;
 * a message says what it found.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (text.isNullOrEmpty()) {
            finish()
            return
        }
        val c = (application as YggApp).container
        if (!PromiseText.mayPromise(text)) {
            Toast.makeText(this, "No promise in that: nothing like \"I'll …\" in it.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Toast.makeText(this, "Looking for promises…", Toast.LENGTH_SHORT).show()
        c.appScope.launch {
            val found = c.promises.read(text, chat = false)
            c.hermodr.send(
                MessageRequest(
                    sender = "norn",
                    title = if (found.isEmpty()) "No promise found" else "${found.size} ${if (found.size == 1) "promise" else "promises"} kept",
                    text = found.joinToString("\n") { p ->
                        "· ${p.what}" + (if (p.to.isNotEmpty()) " (to ${p.to})" else "") + (p.due?.let { " · $it" } ?: "")
                    }.ifEmpty { "The model found nothing you said you'd do." },
                    priority = Priority.LOW,
                    tag = "promises-shared",
                ),
            )
        }
        finish()
    }
}
