package com.nuvio.app.core.auth

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Strips `data:image/svg+xml` wrappers and decodes percent-encoded or Base64 SVG payloads
 * returned by GoTrue `auth.mfa.enroll(FactorType.TOTP)`.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun normalizeTotpQrSvg(rawQrCode: String): String {
    val trimmed = rawQrCode.trim()
    if (trimmed.isEmpty()) return ""
    if (!trimmed.startsWith("data:image/svg+xml", ignoreCase = true)) {
        return trimmed
    }

    val commaIndex = trimmed.indexOf(',')
    if (commaIndex < 0 || commaIndex + 1 >= trimmed.length) return ""

    val header = trimmed.substring(0, commaIndex).lowercase()
    val payload = trimmed.substring(commaIndex + 1)

    if (";base64" in header) {
        return runCatching { Base64.decode(payload).decodeToString().trim() }
            .getOrDefault("")
    }

    val decoded = if ('%' in payload) decodePercentEncodedUtf8(payload) else payload
    return decoded.trim()
}

internal fun formatTotpSecretForDisplay(secret: String): String {
    val normalized = secret.trim().replace(" ", "").uppercase()
    if (normalized.isEmpty()) return ""
    return normalized.chunked(4).joinToString(" ")
}

private fun decodePercentEncodedUtf8(input: String): String {
    val bytes = ByteArray(input.length)
    var outIndex = 0
    var i = 0
    while (i < input.length) {
        val ch = input[i]
        if (ch == '%' && i + 2 < input.length) {
            val hi = input[i + 1].hexDigitValueOrNull()
            val lo = input[i + 2].hexDigitValueOrNull()
            if (hi != null && lo != null) {
                bytes[outIndex++] = ((hi shl 4) or lo).toByte()
                i += 3
                continue
            }
        }
        val encodedChar = ch.toString().encodeToByteArray()
        encodedChar.copyInto(bytes, destinationOffset = outIndex)
        outIndex += encodedChar.size
        i += 1
    }
    return bytes.decodeToString(0, outIndex)
}

private fun Char.hexDigitValueOrNull(): Int? = when (this) {
    in '0'..'9' -> this - '0'
    in 'a'..'f' -> 10 + (this - 'a')
    in 'A'..'F' -> 10 + (this - 'A')
    else -> null
}
