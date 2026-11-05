package cc.noharry.bothy.protocol.schema

import cc.noharry.agent.appserver.protocol.MethodKind
import cc.noharry.agent.appserver.protocol.PROTOCOL_VERSION
import cc.noharry.agent.appserver.protocol.ProtocolMethod
import cc.noharry.bothy.protocol.BothyExtensions
import cc.noharry.bothy.protocol.ProtocolMethods
import java.io.File
import kotlinx.serialization.KSerializer

/** A method with the types of its params and its result. */
class MethodTypes(val method: ProtocolMethod, val params: TypeRef, val result: TypeRef?)

/**
 * The protocol as published for clients in any language, as Codex's `app-server generate-json-schema` and
 * `generate-ts` publish theirs: [files] are the JSON Schema and the TypeScript types, written from [ProtocolMethods]
 * into `bothy-protocol/schema/` and kept in step with the code by a test.
 */
class ProtocolSchema(
    methods: List<ProtocolMethod> = ProtocolMethods.all,
    /** What an extension section's `data` holds, by the section's name. */
    extensions: Map<String, KSerializer<*>> = BothyExtensions.sections,
    val version: Int = PROTOCOL_VERSION,
) {
    val model = SchemaModel(
        methods.flatMap { listOfNotNull(it.params.descriptor, it.result?.descriptor) } + extensions.values.map { it.descriptor },
    )
    val methods: List<MethodTypes> = methods.map { method ->
        MethodTypes(method, model.typeOf(method.params.descriptor), method.result?.let { model.typeOf(it.descriptor) })
    }

    /** Each extension section's name with the type of its `data`. */
    val extensionTypes: List<Pair<String, TypeRef>> = extensions.map { (name, serializer) -> name to model.typeOf(serializer.descriptor) }

    /** Each definition's name in both files. */
    val names: Map<String, String> = model.names()

    /** The definitions by name, as both files list them. */
    val definitions: List<Pair<String, Definition>> =
        model.definitions.map { (id, definition) -> names.getValue(id) to definition }.sortedBy { it.first }

    fun files(): Map<String, String> = mapOf(
        JSON_SCHEMA_FILE to JsonSchemaWriter(this).write(),
        TYPESCRIPT_FILE to TypeScriptWriter(this).write(),
    )

    fun methodsOf(kind: MethodKind): List<MethodTypes> = methods.filter { it.method.kind == kind }

    companion object {
        const val JSON_SCHEMA_FILE = "bothy-protocol.schema.json"
        const val TYPESCRIPT_FILE = "bothy-protocol.d.ts"

        /** The methods by who sends them, and what each group is called in both files. */
        val GROUPS = listOf(
            MethodKind.CLIENT_REQUEST to "ClientRequests",
            MethodKind.CLIENT_NOTIFICATION to "ClientNotifications",
            MethodKind.SERVER_REQUEST to "ServerRequests",
            MethodKind.SERVER_NOTIFICATION to "ServerNotifications",
        )
    }
}

/** `./gradlew :bothy-protocol:generateSchema`: writes [ProtocolSchema.files] into the directory given. */
fun main(args: Array<String>) {
    val dir = File(args.single()).apply { mkdirs() }
    ProtocolSchema().files().forEach { (name, text) -> File(dir, name).writeText(text) }
    println("wrote ${ProtocolSchema.JSON_SCHEMA_FILE} and ${ProtocolSchema.TYPESCRIPT_FILE} to $dir")
}
