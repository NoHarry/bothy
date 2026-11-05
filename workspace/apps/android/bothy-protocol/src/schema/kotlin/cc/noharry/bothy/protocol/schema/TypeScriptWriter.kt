package cc.noharry.bothy.protocol.schema

import kotlinx.serialization.json.JsonPrimitive

/**
 * The protocol as TypeScript declarations: a type for each named type, and for each group of methods an interface
 * from method name to its params and result.
 */
internal class TypeScriptWriter(
    private val schema: ProtocolSchema,
) {
    fun write(): String = buildString {
        append(HEADER)
        appendLine()
        appendLine("export declare const PROTOCOL_VERSION: ${schema.version};")
        schema.definitions.forEach { (name, definition) ->
            appendLine()
            append(declaration(name, definition))
        }
        ProtocolSchema.GROUPS.forEach { (kind, group) ->
            check(schema.names.values.none { it == group }) { "a type is called $group like a group of methods" }
            appendLine()
            appendLine("export interface $group {")
            schema.methodsOf(kind).forEach { appendLine("  ${quote(it.method.name)}: ${method(it)};") }
            appendLine("}")
        }
        check(schema.names.values.none { it == EXTENSIONS }) { "a type is called $EXTENSIONS like the extension sections" }
        appendLine()
        appendLine("/** What an extension section's `data` holds, by its `name`. */")
        appendLine("export interface $EXTENSIONS {")
        schema.extensionTypes.forEach { (name, type) -> appendLine("  ${key(name)}: ${type(type)};") }
        appendLine("}")
    }

    private fun method(types: MethodTypes): String =
        types.result?.let { "{ params: ${type(types.params)}; result: ${type(it)} }" } ?: "{ params: ${type(types.params)} }"

    private fun declaration(name: String, definition: Definition): String = when (definition) {
        is Definition.Record ->
            if (definition.properties.isEmpty()) {
                "export type $name = Record<string, never>;\n"
            } else {
                buildString {
                    appendLine("export interface $name {")
                    definition.properties.forEach { appendLine("  ${key(it.name)}${if (it.required) "" else "?"}: ${type(it.type)};") }
                    appendLine("}")
                }
            }
        is Definition.Enumeration -> "export type $name = ${definition.values.joinToString(" | ", transform = ::quote)};\n"
        is Definition.Union -> buildString {
            appendLine("export type $name =")
            definition.members.forEachIndexed { index, (tag, id) ->
                append("  | ${member(tag, id)}")
                appendLine(if (index == definition.members.lastIndex) ";" else "")
            }
        }
    }

    /** A member with no fields of its own is only its `type`. */
    private fun member(tag: String, id: String): String {
        val fields = (schema.model.definitions[id] as? Definition.Record)?.properties
        return if (fields?.isEmpty() == true) "{ type: ${quote(tag)} }" else "({ type: ${quote(tag)} } & ${schema.names.getValue(id)})"
    }

    private fun type(type: TypeRef): String = when (type) {
        is TypeRef.Primitive -> if (type.type == "integer" || type.type == "number") "number" else type.type
        is TypeRef.Named -> schema.names.getValue(type.id)
        is TypeRef.ListOf -> "Array<${type(type.item)}>"
        is TypeRef.MapOf -> {
            val key = type.key
            val enumKey = key is TypeRef.Named && schema.model.definitions[key.id] is Definition.Enumeration
            if (enumKey) "Partial<Record<${type(key)}, ${type(type.value)}>>" else "Record<string, ${type(type.value)}>"
        }
        is TypeRef.Nullable -> "${type(type.inner)} | null"
        TypeRef.AnyJson -> "unknown"
        TypeRef.AnyObject -> "Record<string, unknown>"
        TypeRef.AnyArray -> "unknown[]"
    }

    private fun key(name: String): String = if (IDENTIFIER.matches(name)) name else quote(name)

    private fun quote(text: String): String = JsonPrimitive(text).toString()

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        const val EXTENSIONS = "ExtensionSections"

        val HEADER = """
            |// Bothy's protocol as TypeScript types, generated from bothy-protocol by
            |// `./gradlew :bothy-protocol:generateSchema`; do not edit.
            |// JSON-RPC 2.0 without the "jsonrpc" member, one message per line; unknown members are ignored and null ones
            |// left out. A sealed type's member with a `type` of its own is named after both ("StateSectionRun" is the
            |// `run` section).
            |
        """.trimMargin()
    }
}
