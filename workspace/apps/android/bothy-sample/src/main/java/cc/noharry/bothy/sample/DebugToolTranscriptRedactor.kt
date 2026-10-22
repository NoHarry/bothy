package cc.noharry.bothy.sample

import cc.noharry.agent.kernel.core.DefaultTraceRedactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun redactDebugToolPayload(rawValue: String): String {
    val parsed = runCatching { DebugToolPayloadJson.parseToJsonElement(rawValue) }.getOrNull()
    return parsed?.redacted()?.toString() ?: redactDebugToolText(rawValue)
}

private fun JsonElement.redacted(): JsonElement =
    when (this) {
        is JsonObject -> JsonObject(
            entries.associate { (key, value) ->
                key to if (key.isSensitiveDebugField()) {
                    JsonPrimitive(REDACTED_VALUE)
                } else {
                    value.redacted()
                }
            },
        )
        is JsonArray -> JsonArray(map { it.redacted() })
        is JsonPrimitive -> if (isString) JsonPrimitive(redactDebugToolText(content)) else this
        else -> this
    }

private fun String.isSensitiveDebugField(): Boolean {
    val normalized = lowercase().filter(Char::isLetterOrDigit)
    return normalized in SENSITIVE_FIELDS || SENSITIVE_FIELD_SUFFIXES.any(normalized::endsWith)
}

private fun redactDebugToolText(rawValue: String): String {
    var redacted = QUOTED_SECRET_ASSIGNMENT.replace(rawValue) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}$REDACTED_SECRET${match.groupValues[2]}"
    }
    redacted = UNQUOTED_SECRET_ASSIGNMENT.replace(redacted) { match ->
        "${match.groupValues[1]}$REDACTED_SECRET"
    }
    redacted = BEARER_TOKEN.replace(redacted, "Bearer $REDACTED_SECRET")
    redacted = EMAIL_ADDRESS.replace(redacted, REDACTED_EMAIL)
    return DefaultTraceRedactor.redact(redacted)
}

private val DebugToolPayloadJson = Json { ignoreUnknownKeys = true }
private val SENSITIVE_FIELDS = setOf(
    "accesstoken",
    "address",
    "apikey",
    "auth",
    "authorization",
    "clientsecret",
    "cookie",
    "email",
    "password",
    "passphrase",
    "phone",
    "phonenumber",
    "privatekey",
    "refreshtoken",
    "secret",
    "setcookie",
    "socialsecuritynumber",
    "ssn",
    "streetaddress",
    "token",
)
private val SENSITIVE_FIELD_SUFFIXES = setOf("apikey", "password", "secret", "token")
private val QUOTED_SECRET_ASSIGNMENT = Regex(
    """(?i)((?:"|')?(?:access[_-]?token|refresh[_-]?token|token|api[_-]?key|password|passphrase|auth|authorization|cookie|client[_-]?secret|private[_-]?key|secret|email|phone(?:[_-]?number)?|address|street[_-]?address|ssn|social[_-]?security[_-]?number)(?:"|')?\s*[:=]\s*)(["'])[^"']*\2""",
)
private val UNQUOTED_SECRET_ASSIGNMENT = Regex(
    """(?i)((?:"|')?(?:access[_-]?token|refresh[_-]?token|token|api[_-]?key|password|passphrase|auth|authorization|cookie|client[_-]?secret|private[_-]?key|secret|email|phone(?:[_-]?number)?|address|street[_-]?address|ssn|social[_-]?security[_-]?number)(?:"|')?\s*[:=]\s*)(?:Bearer\s+)?[^\s,;"'<>}]+""",
)
private val BEARER_TOKEN = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
private val EMAIL_ADDRESS = Regex("""(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""")
private const val REDACTED_VALUE = "[REDACTED]"
private const val REDACTED_SECRET = "[REDACTED_SECRET]"
private const val REDACTED_EMAIL = "[REDACTED_EMAIL]"
