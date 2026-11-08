package app.orbit.tv.core

import java.text.Normalizer

/** Turns untrusted, sender-supplied names into safe leaf names. Mirrors the Windows implementation. */
object FileNameSanitizer {
    private const val MAX_LENGTH = 180
    private val reserved = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    fun sanitize(raw: String?): String {
        var name = raw ?: ""
        val cut = maxOf(name.lastIndexOf('/'), name.lastIndexOf('\\'))
        if (cut >= 0) name = name.substring(cut + 1)

        val sb = StringBuilder()
        for (ch in Normalizer.normalize(name, Normalizer.Form.NFC)) {
            if (Character.isISOControl(ch) || ch in "<>:\"|?*" || ch == '‮' || ch == '‭') continue
            sb.append(ch)
        }
        name = sb.toString().trim().trimEnd('.', ' ').trimStart('.')
        if (name.isEmpty()) name = "file"

        val dot = name.lastIndexOf('.')
        var stem = if (dot > 0) name.substring(0, dot) else name
        var ext = if (dot > 0) name.substring(dot) else ""
        if (stem.uppercase() in reserved || stem.substringBefore('.').uppercase() in reserved) stem = "_$stem"
        if (ext.length > 16) { stem += ext; ext = "" }
        if (stem.length + ext.length > MAX_LENGTH) stem = stem.take(maxOf(1, MAX_LENGTH - ext.length))
        if (stem.isEmpty()) stem = "file"
        return stem + ext
    }

    fun makeUnique(fileName: String, isTaken: (String) -> Boolean): String {
        if (!isTaken(fileName)) return fileName
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        for (i in 1 until 100_000) {
            val candidate = "$stem ($i)$ext"
            if (!isTaken(candidate)) return candidate
        }
        return "$stem (${System.nanoTime()})$ext"
    }
}
