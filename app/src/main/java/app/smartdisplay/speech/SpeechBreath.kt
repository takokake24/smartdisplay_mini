package app.smartdisplay.speech

private val BreathBreak = setOf(
    '、', '。', '！', '？',
    '，', ',', '!', '?',
    '；', ';',
)

private enum class TokenKind {
    Letter,
    Digit,
    Other,
}

/**
 * 半角スペースを読点にして、読み上げで一呼吸置く。連続する空白は1回にする。
 * 英語の文中はそのまま。英語のあとにスペースを挟んで日本語が続くときは読点にする。
 */
fun breathAtHalfWidthSpace(text: String): String {
    if (' ' !in text) {
        return text
    }
    val out = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val ch = text[index]
        if (ch != ' ') {
            out.append(ch)
            index++
            continue
        }
        val spaceAt = index
        while (index < text.length && text[index] == ' ') {
            index++
        }
        if (out.isEmpty() || index >= text.length) {
            continue
        }
        val next = text[index]
        if (next !in BreathBreak &&
            !isJapaneseScript(next) &&
            (isEnglishInterior(text, spaceAt - 1, index) || followsEnglishWord(text, spaceAt - 1))
        ) {
            out.append(' ')
            continue
        }
        val last = text[spaceAt - 1]
        if (last !in BreathBreak && next !in BreathBreak) {
            out.append('、')
        }
    }
    return out.toString()
}

private fun followsEnglishWord(text: String, prevIndex: Int): Boolean {
    val kind = tokenKindBefore(text, prevIndex)
    if (kind == TokenKind.Letter) {
        return true
    }
    if (kind != TokenKind.Digit) {
        return false
    }
    var cursor = prevIndex
    while (cursor >= 0 && isEnglishPunctuation(text[cursor])) {
        cursor--
    }
    while (cursor >= 0 && isEnglishWordChar(text[cursor])) {
        cursor--
    }
    while (cursor >= 0 && text[cursor] == ' ') {
        cursor--
    }
    if (cursor < 0) {
        return false
    }
    return tokenKindBefore(text, cursor) == TokenKind.Letter
}

private fun isEnglishInterior(text: String, prevIndex: Int, nextIndex: Int): Boolean {
    val left = tokenKindBefore(text, prevIndex)
    val right = tokenKindAfter(text, nextIndex)
    val leftWord = left == TokenKind.Letter || left == TokenKind.Digit
    val rightWord = right == TokenKind.Letter || right == TokenKind.Digit
    if (!leftWord || !rightWord) {
        return false
    }
    return left == TokenKind.Letter || right == TokenKind.Letter
}

private fun tokenKindBefore(text: String, index: Int): TokenKind {
    var cursor = index
    while (cursor >= 0 && isEnglishPunctuation(text[cursor])) {
        cursor--
    }
    var sawLetter = false
    var sawDigit = false
    while (cursor >= 0 && isEnglishWordChar(text[cursor])) {
        when (text[cursor]) {
            in 'A'..'Z', in 'a'..'z' -> sawLetter = true
            in '0'..'9' -> sawDigit = true
        }
        cursor--
    }
    return tokenKind(sawLetter, sawDigit)
}

private fun tokenKindAfter(text: String, index: Int): TokenKind {
    var cursor = index
    while (cursor < text.length && isEnglishPunctuation(text[cursor])) {
        cursor++
    }
    var sawLetter = false
    var sawDigit = false
    while (cursor < text.length && isEnglishWordChar(text[cursor])) {
        when (text[cursor]) {
            in 'A'..'Z', in 'a'..'z' -> sawLetter = true
            in '0'..'9' -> sawDigit = true
        }
        cursor++
    }
    return tokenKind(sawLetter, sawDigit)
}

private fun tokenKind(sawLetter: Boolean, sawDigit: Boolean): TokenKind {
    return when {
        sawLetter -> TokenKind.Letter
        sawDigit -> TokenKind.Digit
        else -> TokenKind.Other
    }
}

private fun isJapaneseScript(ch: Char): Boolean {
    return ch in '\u3040'..'\u30FF' ||
        ch in '\u31F0'..'\u31FF' ||
        ch in '\u3400'..'\u9FFF' ||
        ch in '\uF900'..'\uFAFF' ||
        ch in '\uFF66'..'\uFF9D'
}

private fun isEnglishWordChar(ch: Char): Boolean {
    return ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '\'' || ch == '’' || ch == '-'
}

private fun isEnglishPunctuation(ch: Char): Boolean {
    return ch == '.' || ch == ',' || ch == '!' || ch == '?' || ch == ';' || ch == ':' ||
        ch == '"' || ch == '\'' || ch == '’' || ch == '‘' || ch == '“' || ch == '”' ||
        ch == '(' || ch == ')' || ch == '[' || ch == ']'
}
