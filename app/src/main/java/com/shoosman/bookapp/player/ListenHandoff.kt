package com.shoosman.bookapp.player

/**
 * Точное место в аудио при переходе «аудио → текст». Если человек посмотрел текст и вернулся
 * к аудио с той же страницы, продолжаем ровно с этого места, а не с начала абзаца или страницы
 * (иначе при каждом переключении туда-обратно аудио отставало бы на десятки секунд).
 */
object ListenHandoff {
    private var bookId = -1L
    private var charPos = -1
    private var audioMs = -1L
    private var at = 0L

    fun remember(bookId: Long, charPos: Int, audioMs: Long) {
        this.bookId = bookId
        this.charPos = charPos
        this.audioMs = audioMs
        this.at = System.currentTimeMillis()
    }

    /**
     * Время для возврата к аудио, если человек остался на той же странице.
     * [pageStart] — первый символ видимой страницы, [pageChars] — сколько символов примерно на странице.
     */
    fun resumeMs(bookId: Long, pageStart: Int, pageChars: Int = 3000): Long? {
        if (bookId != this.bookId || audioMs < 0) return null
        if (System.currentTimeMillis() - at > 6 * 60 * 60 * 1000L) return null
        val samePage = charPos >= pageStart - 50 && charPos - pageStart <= pageChars
        return if (samePage) audioMs else null
    }

    fun clear() {
        bookId = -1L
        audioMs = -1L
    }
}
