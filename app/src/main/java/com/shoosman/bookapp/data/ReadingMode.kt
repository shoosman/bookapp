package com.shoosman.bookapp.data

import android.content.Context

/**
 * Последний реально открытый режим для карточки «Продолжить» на главной.
 * Хранится отдельно от прогресса: фоновое сохранение позиции аудио не должно
 * переключать карточку обратно на прослушивание после перехода к тексту.
 * Не меняет БД книг и файлы — безопасно для существующей библиотеки.
 */
object ReadingMode {
    const val TEXT = "text"
    const val AUDIO = "audio"
    private const val PREFS = "last_reading_mode"

    fun set(context: Context, bookId: Long, mode: String) {
        if (mode != TEXT && mode != AUDIO) return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(bookId.toString(), mode).apply()
    }

    fun get(context: Context, bookId: Long): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(bookId.toString(), null)
}
