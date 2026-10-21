package cc.noharry.bothy.sample

internal fun prettyJsonString(src: String): String {
    val out = StringBuilder(src.length + src.length / 4)
    var indent = 0
    var inString = false
    var escape = false
    var i = 0
    while (i < src.length) {
        val c = src[i]
        when {
            escape -> {
                out.append(c)
                escape = false
            }
            inString -> {
                out.append(c)
                escape = c == '\\'
                if (c == '"') inString = false
            }
            c == '"' -> {
                inString = true
                out.append(c)
            }
            c == '{' || c == '[' -> indent = out.appendOpen(src, i, indent, c)
            c == '}' || c == ']' -> indent = out.appendClose(src, i, indent, c)
            c == ',' -> out.appendComma(indent)
            c == ':' -> out.append(": ")
            !c.isWhitespace() -> out.append(c)
        }
        i++
    }
    return out.toString()
}

private fun StringBuilder.appendOpen(src: String, index: Int, indent: Int, c: Char): Int {
    append(c)
    val next = src.skipSpace(index + 1)
    if (next < src.length && src[next] != '}' && src[next] != ']') {
        append('\n')
        val nextIndent = indent + 1
        appendIndent(nextIndent)
        return nextIndent
    }
    return indent
}

private fun StringBuilder.appendClose(src: String, index: Int, indent: Int, c: Char): Int {
    val prev = src.prevNonSpace(index - 1)
    val nextIndent = if (prev >= 0 && src[prev] != '{' && src[prev] != '[') {
        val reduced = (indent - 1).coerceAtLeast(0)
        append('\n')
        appendIndent(reduced)
        reduced
    } else {
        indent
    }
    append(c)
    return nextIndent
}

private fun StringBuilder.appendComma(indent: Int) {
    append(',')
    append('\n')
    appendIndent(indent)
}

private fun String.skipSpace(from: Int): Int {
    var i = from
    while (i < length && this[i].isWhitespace()) i++
    return i
}

private fun String.prevNonSpace(from: Int): Int {
    var i = from
    while (i >= 0 && this[i].isWhitespace()) i--
    return i
}

private fun StringBuilder.appendIndent(level: Int) {
    repeat(level) { append("  ") }
}
