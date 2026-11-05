package cc.noharry.bothy.protocol.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The protocol as JSON Schema 2020-12: every named type under `$defs`, and under `methods` each method's params and
 * result by who sends it. A message's params are checked against `methods.<group>.<method>.params`.
 */
internal class JsonSchemaWriter(
    private val schema: ProtocolSchema,
) {
    fun write(): String = PRETTY.encodeToString(JsonObject.serializer(), root()) + "\n"

    private fun root() = buildJsonObject {
        put("\$schema", "https://json-schema.org/draft/2020-12/schema")
        put("title", "Bothy protocol")
        put("description", DESCRIPTION)
        put("protocolVersion", schema.version)
        putJsonObject("methods") {
            ProtocolSchema.GROUPS.forEach { (kind, group) ->
                putJsonObject(group.replaceFirstChar(Char::lowercaseChar)) {
                    schema.methodsOf(kind).forEach { put(it.method.name, method(it)) }
                }
            }
        }
        putJsonObject("extensionSections") {
            schema.extensionTypes.forEach { (name, type) -> put(name, type(type)) }
        }
        putJsonObject("\$defs") { schema.definitions.forEach { (name, definition) -> put(name, definition(definition)) } }
    }

    private fun method(types: MethodTypes) = buildJsonObject {
        put("params", type(types.params))
        types.result?.let { put("result", type(it)) }
    }

    private fun definition(definition: Definition): JsonObject = when (definition) {
        is Definition.Record -> buildJsonObject {
            put("type", "object")
            if (definition.properties.isNotEmpty()) {
                putJsonObject("properties") { definition.properties.forEach { put(it.name, type(it.type)) } }
            }
            val required = definition.properties.filter { it.required }
            if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it.name) } }
        }
        is Definition.Enumeration -> buildJsonObject {
            put("type", "string")
            putJsonArray("enum") { definition.values.forEach { add(it) } }
        }
        is Definition.Union -> buildJsonObject {
            putJsonArray("oneOf") { definition.members.forEach { (tag, id) -> add(member(tag, id)) } }
        }
    }

    /** A sealed type's member: its own members beside `type`. */
    private fun member(tag: String, id: String) = buildJsonObject {
        putJsonArray("allOf") {
            add(ref(id))
            add(
                buildJsonObject {
                    putJsonObject("properties") { putJsonObject("type") { put("const", tag) } }
                    putJsonArray("required") { add("type") }
                },
            )
        }
    }

    private fun type(type: TypeRef): JsonObject = when (type) {
        is TypeRef.Primitive -> buildJsonObject {
            put("type", type.type)
            type.format?.let { put("format", it) }
        }
        is TypeRef.Named -> ref(type.id)
        is TypeRef.ListOf -> buildJsonObject {
            put("type", "array")
            put("items", type(type.item))
            if (type.unique) put("uniqueItems", true)
        }
        is TypeRef.MapOf -> buildJsonObject {
            put("type", "object")
            put("additionalProperties", type(type.value))
            (type.key as? TypeRef.Named)?.let { put("propertyNames", ref(it.id)) }
        }
        is TypeRef.Nullable -> buildJsonObject {
            putJsonArray("anyOf") {
                add(type(type.inner))
                add(buildJsonObject { put("type", "null") })
            }
        }
        TypeRef.AnyJson -> JsonObject(emptyMap())
        TypeRef.AnyObject -> buildJsonObject { put("type", "object") }
        TypeRef.AnyArray -> buildJsonObject { put("type", "array") }
    }

    private fun ref(id: String) = buildJsonObject { put("\$ref", "#/\$defs/${schema.names.getValue(id)}") }

    private companion object {
        const val DESCRIPTION = "Messages between Bothy's server and its clients: JSON-RPC 2.0 without the \"jsonrpc\" " +
            "member, one message per line. Generated from bothy-protocol by ./gradlew :bothy-protocol:generateSchema; " +
            "do not edit. Unknown members are ignored and null ones left out. An extension section's data is typed " +
            "under extensionSections by the section's name."

        @OptIn(ExperimentalSerializationApi::class)
        val PRETTY = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}
