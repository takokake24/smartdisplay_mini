package app.smartdisplay.speech

/**
 * 読み上げ用の置換辞書。
 * 表示テキストはそのままで、TTS に渡す直前に適用する。
 */
data class SpeechDictionaryEntry(
    val source: String,
    val reading: String,
)

object SpeechDictionary {
    val builtInEntries: List<SpeechDictionaryEntry> = listOf(
        SpeechDictionaryEntry(source = "第二WI", reading = "だいにだぶるあい"),
        SpeechDictionaryEntry(source = "MTG", reading = "みーてぃんぐ"),
    )

    fun apply(
        text: String,
        extraEntries: List<SpeechDictionaryEntry> = emptyList(),
    ): String {
        if (text.isEmpty()) {
            return text
        }
        val entries = (builtInEntries + extraEntries)
            .asSequence()
            .filter { it.source.isNotBlank() && it.reading.isNotBlank() }
            .sortedByDescending { it.source.length }
            .toList()
        var result = text
        for (entry in entries) {
            if (result.contains(entry.source)) {
                result = result.replace(entry.source, entry.reading)
            }
        }
        return result
    }
}
