package com.shoosman.bookapp.data

/**
 * Цикл по названию книги, когда в файле его нет:
 * «Искажающие реальность. Книга 9. Тайна…», «Попал! Том 6», «Крысиный бег – 2», «Проклятье! (Книга 5)», «Элирм II».
 */
object SeriesGuess {

    class Guess(val series: String, val index: Float)

    private val words = mapOf(
        "первая" to 1, "вторая" to 2, "третья" to 3, "четвертая" to 4, "четвёртая" to 4, "пятая" to 5,
        "шестая" to 6, "седьмая" to 7, "восьмая" to 8, "девятая" to 9, "десятая" to 10,
    )
    private val roman = mapOf("i" to 1, "ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6, "vii" to 7, "viii" to 8, "ix" to 9, "x" to 10)

    private fun num(w: String): Float? {
        val s = w.lowercase()
        return s.toFloatOrNull() ?: roman[s]?.toFloat() ?: words[s]?.toFloat()
    }

    private val bookOrVolume = Regex(
        """^(.+?)[.,:!?]?\s*\(?\s*(?:Книга|Том|книга|том)\s+(\d+|[IVXivx]+|\p{L}+)(?![\p{L}\p{N}])(?:[.:)]\s*(?:Часть\s+(\d+))?.*)?$""",
    )
    private val partOnly = Regex("""^(.+?)[.,:]?\s*Часть\s+(\d+)$""")
    private val trailingNumber = Regex("""^(.+?)\s*(?:[-–—]\s*|\s)(\d{1,2}|[IVX]{1,4})$""")

    fun guess(title: String): Guess? {
        val t = title.trim().trimEnd('.')
        bookOrVolume.find(t)?.let { m ->
            val n = num(m.groupValues[2]) ?: return@let
            val part = m.groupValues[3].toIntOrNull()
            val idx = if (part != null) n + part / 10f else n
            return Guess(m.groupValues[1].trim(' ', '.', ':', '!', '?'), idx)
        }
        partOnly.find(t)?.let { m ->
            return Guess(m.groupValues[1].trim(' ', '.', ':', '!', '?'), m.groupValues[2].toFloat())
        }
        trailingNumber.find(t)?.let { m ->
            val n = num(m.groupValues[2]) ?: return@let
            return Guess(m.groupValues[1].trim(' ', '.', ':', '!', '?'), n)
        }
        return null
    }

    /** «8.1» для частей, «3» для целых. */
    fun format(index: Float): String =
        if (index == index.toInt().toFloat()) index.toInt().toString() else "%.1f".format(index).replace(',', '.')

    /** Ключ группировки: одинаковые циклы с разным регистром и «ё/е» — одна группа. */
    fun key(series: String): String =
        series.lowercase().replace('ё', 'е').replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
}
