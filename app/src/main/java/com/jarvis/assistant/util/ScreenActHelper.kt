package com.jarvis.assistant.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.model.ActionType
import com.jarvis.assistant.model.AssistantCommand

/**
 * Stark L4 — read what's on screen, then propose / run a follow-up action.
 * Offline heuristics first; no API key required for basic act.
 */
object ScreenActHelper {
    private const val TAG = "ScreenActHelper"

    data class Plan(
        val summary: String,
        val action: AssistantCommand?,
        val spoken: String
    )

    fun captureText(): String {
        val svc = JarvisAccessibilityService.instance
            ?: return ""
        return try {
            svc.getScreenText().orEmpty().trim()
        } catch (e: Exception) {
            Log.e(TAG, "getScreenText", e)
            ""
        }
    }

    /**
     * From screen text + optional user intent, build one follow-up command.
     */
    fun plan(context: Context, screenText: String, userIntent: String? = null): Plan {
        val text = screenText.replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) {
            return Plan(
                summary = "empty",
                action = null,
                spoken = "I cannot see any text on the screen. Enable Accessibility for Jarvis."
            )
        }

        val lower = text.lowercase()
        val intent = userIntent?.lowercase().orEmpty()
        val clipped = if (text.length > 280) text.take(280).trimEnd() + "…" else text

        // Explicit user-requested screen actions. Never auto-click destructive or
        // externally committing controls through generic screen instructions.
        val accessibility = JarvisAccessibilityService.instance
        if (accessibility != null) {
            val scrollForward = intent.contains("scroll down") || intent.contains("scroll forward") ||
                intent.contains("neeche scroll") || intent.contains("aage scroll")
            val scrollBackward = intent.contains("scroll up") || intent.contains("scroll backward") ||
                intent.contains("upar scroll") || intent.contains("peeche scroll")
            if (scrollForward || scrollBackward) {
                val moved = accessibility.scrollScreen(forward = scrollForward)
                return Plan(clipped, null, if (moved) {
                    if (scrollForward) "Screen neeche scroll kar diya." else "Screen upar scroll kar diya."
                } else "Is screen par scroll karna possible nahi hua.")
            }

            val clickMatch = Regex("""(?:tap|click|press|select|chuno|dabao)\s+(.+)$""")
                .find(intent)
            if (clickMatch != null) {
                val label = clickMatch.groupValues[1].trim().trimEnd('.', '?', '!')
                val riskyLabel = listOf("send", "delete", "remove", "pay", "payment", "buy", "purchase",
                    "transfer", "confirm", "submit", "call", "message", "भेज", "हटाओ", "भुगतान", "खरीद")
                    .any { label.contains(it) }
                if (riskyLabel) {
                    return Plan(clipped, null,
                        "Yeh action sensitive ho sakta hai. Iske liye dedicated command aur confirmation chahiye.")
                }
                if (label.length >= 2) {
                    val clicked = accessibility.clickVisibleText(label)
                    return Plan(clipped, null, if (clicked) {
                        "Screen par '$label' ko tap kar diya."
                    } else "Screen par '$label' ka clickable control nahi mila.")
                }
            }
        }

        // Explicit user intent overrides
        when {
            intent.contains("copy") || intent.contains("clipboard") -> {
                return Plan(
                    clipped,
                    AssistantCommand(action = ActionType.REPLY.key, message = "Screen text captured."),
                    "On screen: $clipped"
                )
            }
            intent.contains("summar") || intent.contains("padh") || intent.contains("read") -> {
                return Plan(clipped, null, "On screen I can see: $clipped")
            }
        }

        // URL on screen → open
        val url = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE).find(text)?.value
        if (url != null && (intent.isBlank() || intent.contains("open") || intent.contains("link"))) {
            return Plan(
                clipped,
                AssistantCommand(action = ActionType.OPEN_URL.key, target = url),
                "I found a link. Opening it."
            )
        }

        // Phone number → dial
        val phone = Regex("""(\+?\d[\d\s\-()]{8,}\d)""").find(text)?.value?.replace(Regex("[\\s\\-()]"), "")
        if (phone != null && phone.length in 10..15 &&
            (intent.contains("call") || intent.contains("dial") || intent.isBlank() && lower.contains("call"))
        ) {
            return Plan(
                clipped,
                AssistantCommand(action = ActionType.CALL.key, target = phone),
                "I found a number. Calling $phone."
            )
        }

        // Email
        val email = Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}""").find(text)?.value
        if (email != null && (intent.contains("mail") || intent.contains("email"))) {
            return Plan(
                clipped,
                AssistantCommand(action = ActionType.OPEN_URL.key, target = "mailto:$email"),
                "Opening email to $email."
            )
        }

        // Default: report only (safe)
        return Plan(
            summary = clipped,
            action = null,
            spoken = "On screen: $clipped"
        )
    }

    fun openUrl(context: Context, url: String): Boolean {
        return try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            true
        } catch (e: Exception) {
            Log.e(TAG, "openUrl", e)
            false
        }
    }
}
