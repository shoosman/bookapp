package com.shoosman.bookapp.data

/** Поиск без зависимости от порядка имени/фамилии, пунктуации и регистра. */
object BookSearch {
    private val separator = Regex("[^\\p{L}\\p{N}]+")
    fun normalize(text: String): String =
        text.lowercase().replace('ё', 'е').replace(separator, " ").trim()

    fun matches(query: String, vararg fields: String): Boolean {
        val q = normalize(query)
        if (q.isBlank()) return true
        val tokens = q.split(' ').filter { it.isNotBlank() }
        val fieldText = fields.map(::normalize)
        if (fieldText.any { it.contains(q) }) return true
        val words = fieldText.flatMap { it.split(' ') }.filter { it.isNotBlank() }
        return tokens.all { token -> words.any { word -> word.startsWith(token) } }
    }
}
