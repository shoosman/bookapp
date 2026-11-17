package com.shoosman.bookapp.player

/**
 * Точное место аудио при переходе «слушать → читать → слушать».
 *
 * Пагинация и пересчёт шрифтов меняют первый символ видимой страницы даже тогда,
 * когда читатель ничего не листал. Поэтому сравнивать координаты страниц нельзя:
 * используем исходную миллисекунду до первого реального действия навигации.
 */
object ListenHandoff {
    private var bookId = -1L
    private var charPos = -1
    private var audioMs = -1L
    private var at = 0L
    private var textWasMoved = false

    fun remember(bookId: Long, charPos: Int, audioMs: Long) {
        this.bookId = bookId
        this.charPos = charPos
        this.audioMs = audioMs
        this.at = System.currentTimeMillis()
        this.textWasMoved = false
    }

    /** Перелистывание, прокрутка, выбор главы или перемещение ползунка. */
    fun invalidate(bookId: Long) {
        if (this.bookId == bookId) textWasMoved = true
    }

    /**
     * Возвращает исходную позицию, если читатель не перемещался по тексту.
     * Потребляется один раз: старый переход не влияет на будущие открытия книги.
     */
    fun consumeResumeMs(bookId: Long): Long? {
        val exact = if (
            this.bookId == bookId &&
            audioMs >= 0 &&
            !textWasMoved &&
            System.currentTimeMillis() - at < 6 * 60 * 60 * 1000L
        ) audioMs else null
        clear()
        return exact
    }

    fun clear() {
        bookId = -1L
        charPos = -1
        audioMs = -1L
        at = 0L
        textWasMoved = false
    }
}
