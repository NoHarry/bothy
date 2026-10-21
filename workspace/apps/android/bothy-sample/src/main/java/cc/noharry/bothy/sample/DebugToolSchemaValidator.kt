package cc.noharry.bothy.sample

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun validateDebugInputSchema(inputSchemaJson: String, argumentsJson: String): DebugSchemaValidation {
    val arguments = parseDebugJsonObject(argumentsJson) ?: return DebugSchemaValidation.Invalid(
        code = "SCHEMA_VALIDATION_FAILED",
        message = "Tool arguments must be a JSON object.",
        schemaPath = "/arguments",
    )
    val schema = parseDebugJsonObject(inputSchemaJson) ?: return DebugSchemaValidation.Invalid(
        code = "INVALID_TOOL_SCHEMA",
        message = "Tool schema must be a JSON object.",
    )
    return validateObjectSchema(schema, arguments)
}

internal fun parseDebugJsonObject(content: String): JsonObject? =
    try {
        Json.parseToJsonElement(content) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun validateObjectSchema(schema: JsonObject, arguments: JsonObject): DebugSchemaValidation {
    requiredFields(schema).firstOrNull { !arguments.containsKey(it) }?.let { missing ->
        return DebugSchemaValidation.Invalid(
            code = "SCHEMA_VALIDATION_FAILED",
            message = "Missing required field: $missing",
            schemaPath = "/required",
        )
    }
    val properties = schema["properties"] as? JsonObject ?: return DebugSchemaValidation.Valid
    for ((name, propertySchema) in properties) {
        val value = arguments[name] ?: continue
        val property = propertySchema as? JsonObject ?: continue
        property.stringField("type")?.let { expectedType ->
            if (!matchesType(value, expectedType)) {
                return DebugSchemaValidation.Invalid(
                    code = "SCHEMA_VALIDATION_FAILED",
                    message = "Field $name must be $expectedType.",
                    schemaPath = "/properties/$name/type",
                )
            }
        }
        val allowedValues = property["enum"] as? JsonArray ?: continue
        if (allowedValues.none { it == value }) {
            return DebugSchemaValidation.Invalid(
                code = "SCHEMA_VALIDATION_FAILED",
                message = "Field $name must be one of the declared enum values.",
                schemaPath = "/properties/$name/enum",
            )
        }
    }
    return DebugSchemaValidation.Valid
}

private fun requiredFields(schema: JsonObject): List<String> =
    (schema["required"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }

private fun matchesType(value: JsonElement, expectedType: String): Boolean =
    when (expectedType) {
        "string" -> value is JsonPrimitive && value.isString
        "number" -> value is JsonPrimitive && value.doubleOrNull != null
        "integer" -> value is JsonPrimitive && value.intOrNull != null
        "boolean" -> value is JsonPrimitive && value.booleanOrNull != null
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        else -> true
    }

private fun JsonObject.stringField(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

internal sealed interface DebugSchemaValidation {
    data object Valid : DebugSchemaValidation

    data class Invalid(
        val code: String,
        val message: String,
        val schemaPath: String? = null,
    ) : DebugSchemaValidation
}
