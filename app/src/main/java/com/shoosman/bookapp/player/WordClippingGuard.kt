package com.shoosman.bookapp.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * Обход обрезания первых слогов на Bluetooth-магнитолах с noise gate.
 *
 * Подмешивает очень тихий шум (~-52 дБ относительно полной шкалы) в PCM:
 * даже в межсловных паузах на выход поступает ненулевой сигнал.
 * Исходные файлы не меняются. По умолчанию выключен.
 */
class WordClippingGuard : BaseAudioProcessor() {
    @Volatile
    var enabled: Boolean = false

    private var seed = 0x5271C4D3

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Для нестандартного PCM не активируем процессор, чтобы не портить звук.
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT)
            inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val output = replaceOutputBuffer(inputBuffer.remaining())
        while (inputBuffer.remaining() >= 2) {
            // PCM16 little-endian, независимо от ByteBuffer.order().
            val lo = inputBuffer.get().toInt() and 0xff
            val hi = inputBuffer.get().toInt() and 0xff
            val original = ((hi shl 8) or lo).toShort().toInt()
            val noise = if (enabled) {
                // Детерминированный PRNG без аллокаций на аудиопотоке.
                seed = seed xor (seed shl 13)
                seed = seed xor (seed ushr 17)
                seed = seed xor (seed shl 5)
                ((seed ushr 16) and 0x7f) - 64
            } else 0
            val mixed = (original + noise).coerceIn(-32768, 32767)
            output.put((mixed and 0xff).toByte())
            output.put((mixed shr 8).toByte())
        }
        // Звуковые фреймы PCM16 всегда выровнены по два байта.
        output.flip()
    }
}
