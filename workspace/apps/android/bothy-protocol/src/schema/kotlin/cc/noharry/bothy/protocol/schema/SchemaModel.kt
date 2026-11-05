package cc.noharry.bothy.protocol.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames

/** A type where it is used: a primitive, a named definition, or a list, map or nullable of one. */
sealed interface TypeRef {
    /** A JSON Schema primitive type: string, integer, number or boolean. */
    data class Primitive(val type: String, val format: String? = null) : TypeRef

    data class Named(val id: String) : TypeRef

    data class ListOf(val item: TypeRef, val unique: Boolean) : TypeRef

    /** A JSON object keyed by [key]'s values (strings, enum names or numbers written as strings). */
    data class MapOf(val key: TypeRef, val value: TypeRef) : TypeRef

    /** Left out when null (`explicitNulls = false`); a peer may also send null. */
    data class Nullable(val inner: TypeRef) : TypeRef

    data object AnyJson : TypeRef

    data object AnyObject : TypeRef

    data object AnyArray : TypeRef
}

/** A member of a class: [required] when a peer must send it (no default, not nullable). */
class Property(val name: String, val type: TypeRef, val required: Boolean)

sealed interface Definition {
    /** A class or an object: its members, written with defaults (`encodeDefaults = true`). */
    class Record(val properties: List<Property>) : Definition

    class Enumeration(val values: List<String>) : Definition

    /** A sealed type: each member's `type` value and its definition, whose members sit beside `type`. */
    class Union(val members: List<Pair<String, String>>) : Definition
}

/**
 * The named types the protocol uses, read from their serial descriptors as [cc.noharry.agent.appserver.protocol.jsonrpc.ProtocolJson]
 * writes them. Each named type is kept once under its serial name ([definitions]); [names] gives it a short name.
 */
@OptIn(ExperimentalSerializationApi::class)
class SchemaModel(
    roots: List<SerialDescriptor>,
) {
    val definitions = LinkedHashMap<String, Definition>()
    private val shapes = HashMap<String, String>()

    /**
     * A sealed member's `@SerialName` ("speech") need only be unique within its sealed type, so a member is known by
     * its sealed type and that name ("…StateSection/speech"); found before any type is named, so a member is the same
     * definition wherever it is used.
     */
    private val memberOf = HashMap<Pair<String, String>, String>()

    init {
        val seen = HashSet<String>()
        roots.forEach { findMembers(it, seen) }
    }

    fun typeOf(descriptor: SerialDescriptor): TypeRef {
        val type = nonNullTypeOf(descriptor)
        return if (descriptor.isNullable) TypeRef.Nullable(type) else type
    }

    private fun nonNullTypeOf(d: SerialDescriptor): TypeRef {
        val id = idOf(d)
        jsonType(id)?.let { return it }
        if (d.isInline) return typeOf(d.getElementDescriptor(0))
        return when (val kind = d.kind) {
            is PrimitiveKind -> primitive(kind, id)
            StructureKind.LIST -> TypeRef.ListOf(typeOf(d.getElementDescriptor(0)), unique = id.endsWith("Set"))
            StructureKind.MAP -> TypeRef.MapOf(typeOf(d.getElementDescriptor(0)), typeOf(d.getElementDescriptor(1)))
            StructureKind.CLASS, StructureKind.OBJECT -> named(id, d) { record(d) }
            SerialKind.ENUM -> named(id, d) { Definition.Enumeration(d.elementNames.toList()) }
            PolymorphicKind.SEALED -> named(id, d) { union(d) }
            else -> error("$id: a ${d.kind} has no schema; give it a serializer the schema generator knows")
        }
    }

    /** Registers [id] before building it, so a type that refers to itself ends. */
    private fun named(id: String, d: SerialDescriptor, build: () -> Definition): TypeRef {
        val shape = shapeOf(d)
        val seen = shapes.putIfAbsent(id, shape)
        check(seen == null || seen == shape) { "two types are both called $id (or one generic type with other types); give one its own @SerialName" }
        if (seen == null) definitions[id] = build()
        return TypeRef.Named(id)
    }

    private fun record(d: SerialDescriptor) = Definition.Record(
        (0 until d.elementsCount).map { i ->
            val element = d.getElementDescriptor(i)
            Property(d.getElementName(i), typeOf(element), required = !d.isElementOptional(i) && !element.isNullable)
        },
    )

    /** A sealed descriptor holds `type` and `value`, whose elements are the members. */
    private fun union(d: SerialDescriptor) = Definition.Union(
        d.getElementDescriptor(1).elementDescriptors.map { member -> member.serialName to (nonNullTypeOf(member) as TypeRef.Named).id },
    )

    /** Lists and maps all share a serial name, so only named types are visited once. */
    private fun findMembers(d: SerialDescriptor, seen: MutableSet<String>) {
        if (jsonType(d.serialName.removeSuffix("?")) != null) return
        val named = d.kind == StructureKind.CLASS || d.kind == StructureKind.OBJECT || d.kind == PolymorphicKind.SEALED
        if (named && !seen.add("${d.serialName.removeSuffix("?")}@${shapeOf(d)}")) return
        if (d.kind == PolymorphicKind.SEALED) {
            val parent = idOf(d)
            d.getElementDescriptor(1).elementDescriptors.forEach { memberOf.putIfAbsent(it.serialName to shapeOf(it), parent) }
        }
        d.elementDescriptors.forEach { findMembers(it, seen) }
    }

    /** The serial name, or for a sealed member with a short one, its sealed type's id and that name. */
    private fun idOf(d: SerialDescriptor): String {
        val name = d.serialName.removeSuffix("?")
        if ('.' in name) return name
        return memberOf[name to shapeOf(d)]?.let { "$it/$name" } ?: name
    }

    /** What tells two types with one serial name apart (a generic class used with other types, say). */
    private fun shapeOf(d: SerialDescriptor) =
        "${d.kind}:" + (0 until d.elementsCount).joinToString(",") { "${d.getElementName(it)}=${d.getElementDescriptor(it).serialName}" }

    private fun primitive(kind: PrimitiveKind, id: String): TypeRef = when (kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> TypeRef.Primitive("string", format = "uri".takeIf { id.endsWith(".Uri") })
        PrimitiveKind.BOOLEAN -> TypeRef.Primitive("boolean")
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> TypeRef.Primitive("integer")
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> TypeRef.Primitive("number")
    }

    private fun jsonType(id: String): TypeRef? = when (id) {
        "kotlinx.serialization.json.JsonObject" -> TypeRef.AnyObject
        "kotlinx.serialization.json.JsonArray" -> TypeRef.AnyArray
        "kotlinx.serialization.json.JsonElement", "kotlinx.serialization.json.JsonPrimitive",
        "kotlinx.serialization.json.JsonLiteral", "kotlinx.serialization.json.JsonNull" -> TypeRef.AnyJson
        else -> null
    }

    /**
     * A short name for each definition: a class is called by its own name ("RunSection" for
     * `cc.noharry.agent.appserver.protocol.RunSection`), or with its outer classes when two share it; a sealed member with its own
     * `@SerialName` by its sealed type and that name ("StateSectionRun" for the `run` section).
     */
    fun names(): Map<String, String> {
        val qualified = definitions.keys.filter { '.' in it && '/' !in it }.associateWith(::classPath)
        val shortCount = qualified.values.groupingBy { it.last() }.eachCount()
        val names = HashMap<String, String>()
        qualified.forEach { (id, path) -> names[id] = if (shortCount.getValue(path.last()) == 1) path.last() else path.joinToString("") }
        fun nameOf(id: String): String = names.getOrPut(id) {
            val parent = id.substringBeforeLast('/', missingDelimiterValue = "")
            (if (parent.isEmpty()) "" else nameOf(parent)) + id.substringAfterLast('/').replaceFirstChar(Char::uppercaseChar)
        }
        definitions.keys.forEach(::nameOf)
        val clashes = names.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        check(clashes.isEmpty()) { "types share a schema name: $clashes" }
        return names
    }

    /** `cc.noharry.agent.appserver.protocol.ConversationItem.MessageItem` -> [ConversationItem, MessageItem]. */
    private fun classPath(id: String): List<String> = id.split('.').dropWhile { it.firstOrNull()?.isUpperCase() != true }
}
