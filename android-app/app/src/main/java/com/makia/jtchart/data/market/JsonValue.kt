package com.makia.jtchart.data.market

internal sealed interface JsonValue {
    data class Object(val values: Map<String, JsonValue>) : JsonValue
    data class Array(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val lexeme: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

internal object StrictJson {
    fun parse(input: String): JsonValue = Parser(input).parse()

    private class Parser(private val input: String) {
        private var position = 0

        fun parse(): JsonValue {
            skipWhitespace()
            val result = value()
            skipWhitespace()
            require(position == input.length) { "Trailing JSON content" }
            return result
        }

        private fun value(): JsonValue {
            skipWhitespace()
            require(position < input.length) { "Unexpected end of JSON" }
            return when (input[position]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> JsonValue.StringValue(string())
                't' -> literal("true", JsonValue.BooleanValue(true))
                'f' -> literal("false", JsonValue.BooleanValue(false))
                'n' -> literal("null", JsonValue.Null)
                else -> number()
            }
        }

        private fun objectValue(): JsonValue.Object {
            position++
            skipWhitespace()
            val values = linkedMapOf<String, JsonValue>()
            if (consume('}')) return JsonValue.Object(values)
            while (true) {
                require(peek() == '"') { "Expected JSON object key" }
                val key = string()
                require(key !in values) { "Duplicate JSON object key" }
                skipWhitespace()
                require(consume(':')) { "Expected colon" }
                values[key] = value()
                skipWhitespace()
                if (consume('}')) return JsonValue.Object(values)
                require(consume(',')) { "Expected comma" }
                skipWhitespace()
            }
        }

        private fun arrayValue(): JsonValue.Array {
            position++
            skipWhitespace()
            val values = mutableListOf<JsonValue>()
            if (consume(']')) return JsonValue.Array(values)
            while (true) {
                values += value()
                skipWhitespace()
                if (consume(']')) return JsonValue.Array(values)
                require(consume(',')) { "Expected comma" }
                skipWhitespace()
            }
        }

        private fun string(): String {
            require(consume('"')) { "Expected quote" }
            val result = StringBuilder()
            while (position < input.length) {
                when (val character = input[position++]) {
                    '"' -> return result.toString()
                    '\\' -> {
                        require(position < input.length) { "Incomplete escape" }
                        when (val escaped = input[position++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                require(position + 4 <= input.length) { "Incomplete unicode escape" }
                                result.append(input.substring(position, position + 4).toInt(16).toChar())
                                position += 4
                            }
                            else -> throw IllegalArgumentException("Invalid escape")
                        }
                    }
                    else -> {
                        require(character.code >= 0x20) { "Control character in string" }
                        result.append(character)
                    }
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun number(): JsonValue.NumberValue {
            val start = position
            if (peek() == '-') position++
            require(position < input.length) { "Invalid JSON number" }
            if (input[position] == '0') {
                position++
            } else {
                require(input[position] in '1'..'9') { "Invalid JSON number" }
                while (peek()?.isDigit() == true) position++
            }
            if (peek() == '.') {
                position++
                require(peek()?.isDigit() == true) { "Invalid fraction" }
                while (peek()?.isDigit() == true) position++
            }
            if (peek() == 'e' || peek() == 'E') {
                position++
                if (peek() == '+' || peek() == '-') position++
                require(peek()?.isDigit() == true) { "Invalid exponent" }
                while (peek()?.isDigit() == true) position++
            }
            return JsonValue.NumberValue(input.substring(start, position))
        }

        private fun <T : JsonValue> literal(expected: String, result: T): T {
            require(input.regionMatches(position, expected, 0, expected.length)) { "Invalid literal" }
            position += expected.length
            return result
        }

        private fun consume(expected: Char): Boolean {
            if (peek() != expected) return false
            position++
            return true
        }

        private fun peek(): Char? = input.getOrNull(position)
        private fun skipWhitespace() {
            while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') position++
        }
    }
}
