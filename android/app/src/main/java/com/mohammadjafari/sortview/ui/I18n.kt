package com.mohammadjafari.sortview.ui

import android.content.Context
import androidx.annotation.StringRes

/**
 * Status messages with the desktop "{x}" placeholder style
 * (compatibility contract #6): the string resources keep the exact
 * canonical English texts (values/) and the exact Persian translations
 * copied from desktop/app_i18n.py (values-fa/), and {x} tokens are filled
 * with .replace() instead of Android's %1$s format.
 */
data class StatusPart(@StringRes val res: Int, val args: List<Pair<String, Any>> = emptyList())

data class StatusMessage(val parts: List<StatusPart>)

fun statusOf(@StringRes res: Int, vararg args: Pair<String, Any>): StatusMessage =
    StatusMessage(listOf(StatusPart(res, args.toList())))

/** Concatenate messages without separator, like the desktop status.setText(a + b). */
operator fun StatusMessage.plus(other: StatusMessage): StatusMessage =
    StatusMessage(parts + other.parts)

/** Resolve + format a message for display. */
fun Context.resolveStatus(msg: StatusMessage?): String {
    if (msg == null) return ""
    val sb = StringBuilder()
    for (part in msg.parts) {
        var text = getString(part.res)
        for ((key, value) in part.args) {
            text = text.replace("{$key}", value.toString())
        }
        sb.append(text)
    }
    return sb.toString()
}
