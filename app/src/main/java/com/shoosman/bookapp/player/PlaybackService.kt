package com.shoosman.bookapp.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.shoosman.bookapp.App
import com.shoosman.bookapp.MainActivity
import com.shoosman.bookapp.R

/** Фоновое воспроизведение: уведомление, кнопки на наушниках, экран блокировки. */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var chapterPlayer: ChapterPlayer? = null

    private val skipBack = SessionCommand(ACTION_SKIP_BACK, Bundle.EMPTY)
    private val skipForward = SessionCommand(ACTION_SKIP_FORWARD, Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        raw = exo
        current = this
        val cp = ChapterPlayer(exo, packageName) { session }
        chapterPlayer = cp
        session = MediaSession.Builder(this, cp)
            .setSessionActivity(openApp)
            .setCallback(Callback())
            .build()
            .also { it.setCustomLayout(seekButtons()) }
    }

    /** Кнопки перемотки на секунды — в шторке и на экране блокировки, с числом секунд в иконке. */
    private fun seekButtons(): ImmutableList<CommandButton> {
        val conn = App.instance.player
        val back = (conn.skipBackMs / 1000).toInt()
        val fwd = (conn.skipForwardMs / 1000).toInt()
        return ImmutableList.of(
            CommandButton.Builder()
                .setDisplayName("Назад на $back с")
                .setIconResId(backIcon(back))
                .setSessionCommand(skipBack)
                .build(),
            CommandButton.Builder()
                .setDisplayName("Вперёд на $fwd с")
                .setIconResId(forwardIcon(fwd))
                .setSessionCommand(skipForward)
                .build(),
        )
    }

    fun refreshButtons() {
        session?.setCustomLayout(seekButtons())
    }

    /** Глава сменилась: шторка перечитывает название и длительность. */
    fun chapterChanged() {
        android.os.Handler(mainLooper).post { chapterPlayer?.notifyChapterChanged() }
    }

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(skipBack)
                .add(skipForward)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val conn = App.instance.player
            when (customCommand.customAction) {
                ACTION_SKIP_BACK -> conn.skipBackFromSystem(session.player)
                ACTION_SKIP_FORWARD -> conn.skipForwardFromSystem(session.player)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /**
     * Обёртка над плеером для кнопок ⏮/⏭:
     *  - в шторке, на экране блокировки и в самом Чтеце — листают главы;
     *  - в машине, на Bluetooth- и проводных наушниках — перематывают на секунды
     *    (шаг из настроек), потому что вслепую главу легко проскочить.
     * Кнопки показываются, даже если книга — один файл.
     */
    private class ChapterPlayer(
        player: Player,
        private val ownPackage: String,
        private val session: () -> MediaSession?,
    ) : ForwardingPlayer(player) {
        private val extra = listOf(
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        )

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(*extra.toIntArray()).build()

        override fun isCommandAvailable(command: Int): Boolean =
            command in extra || super.isCommandAvailable(command)

        private fun chapters() = App.instance.player.takeIf { it.hasChapters }

        /** Нажали на «железе» — машина, наушники, гарнитура, Android Auto? */
        private fun fromHardware(): Boolean {
            val pkg = runCatching { session()?.controllerForCurrentRequest?.packageName }.getOrNull()
                ?: return true
            return pkg != ownPackage && pkg != "com.android.systemui"
        }

        private fun back(fallback: () -> Unit) {
            when {
                fromHardware() -> App.instance.player.skipBackFromSystem(this)
                chapters() != null -> App.instance.player.previousChapter()
                else -> fallback()
            }
        }

        private fun forward(fallback: () -> Unit) {
            when {
                fromHardware() -> App.instance.player.skipForwardFromSystem(this)
                chapters() != null -> App.instance.player.nextChapter()
                else -> fallback()
            }
        }

        override fun seekToPrevious() = back { super.seekToPrevious() }
        override fun seekToNext() = forward { super.seekToNext() }
        override fun seekToPreviousMediaItem() = back { super.seekToPreviousMediaItem() }
        override fun seekToNextMediaItem() = forward { super.seekToNextMediaItem() }

        override fun hasPreviousMediaItem(): Boolean = chapters() != null || super.hasPreviousMediaItem()
        override fun hasNextMediaItem(): Boolean = chapters() != null || super.hasNextMediaItem()

        // ---- Таймлайн главы для шторки, экрана блокировки и машины ----
        // (Приложение берёт позицию прямо из ExoPlayer, поэтому его это не затрагивает.)

        private val listeners = java.util.concurrent.CopyOnWriteArraySet<Player.Listener>()

        override fun addListener(listener: Player.Listener) {
            super.addListener(listener)
            listeners.add(listener)
        }

        override fun removeListener(listener: Player.Listener) {
            super.removeListener(listener)
            listeners.remove(listener)
        }

        private fun window(): LongArray? {
            val dur = super.getDuration().takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            return App.instance.player.chapterWindowInItem(super.getCurrentMediaItemIndex(), super.getCurrentPosition(), dur)
        }

        override fun getDuration(): Long = window()?.let { it[1] - it[0] } ?: super.getDuration()
        override fun getContentDuration(): Long = window()?.let { it[1] - it[0] } ?: super.getContentDuration()

        override fun getCurrentPosition(): Long =
            window()?.let { (super.getCurrentPosition() - it[0]).coerceIn(0, it[1] - it[0]) } ?: super.getCurrentPosition()

        override fun getContentPosition(): Long =
            window()?.let { (super.getContentPosition() - it[0]).coerceIn(0, it[1] - it[0]) } ?: super.getContentPosition()

        override fun getBufferedPosition(): Long =
            window()?.let { (super.getBufferedPosition() - it[0]).coerceIn(0, it[1] - it[0]) } ?: super.getBufferedPosition()

        override fun getContentBufferedPosition(): Long =
            window()?.let { (super.getContentBufferedPosition() - it[0]).coerceIn(0, it[1] - it[0]) } ?: super.getContentBufferedPosition()

        /** Перемотка ползунком в шторке — внутри главы. */
        override fun seekTo(positionMs: Long) {
            val w = window()
            super.seekTo(if (w != null) w[0] + positionMs.coerceIn(0, w[1] - w[0]) else positionMs)
        }

        /** В шторке — название главы, ниже — книга и автор. */
        override fun getMediaMetadata(): androidx.media3.common.MediaMetadata {
            val md = super.getMediaMetadata()
            val ui = App.instance.player.ui.value
            if (ui.chapterTitle.isBlank() || window() == null) return md
            return md.buildUpon()
                .setTitle(ui.chapterTitle)
                .setDisplayTitle(ui.chapterTitle)
                .setArtist(listOf(ui.author, ui.title).filter { it.isNotBlank() }.joinToString(" · "))
                .build()
        }

        fun notifyChapterChanged() {
            val md = mediaMetadata
            val events = Player.Events(
                androidx.media3.common.FlagSet.Builder()
                    .add(Player.EVENT_MEDIA_METADATA_CHANGED)
                    .build(),
            )
            listeners.forEach {
                it.onMediaMetadataChanged(md)
                it.onEvents(this, events)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        raw = null
        current = null
        chapterPlayer = null
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_SKIP_BACK = "chtets.SKIP_BACK"
        const val ACTION_SKIP_FORWARD = "chtets.SKIP_FORWARD"

        /** Сам ExoPlayer — для точной позиции в приложении (шторке отдаётся время главы). */
        @Volatile var raw: Player? = null
            private set

        @Volatile var current: PlaybackService? = null
            private set

        fun backIcon(sec: Int) = when (sec) {
            5 -> R.drawable.ic_n_back_5
            10 -> R.drawable.ic_n_back_10
            15 -> R.drawable.ic_n_back_15
            30 -> R.drawable.ic_n_back_30
            else -> R.drawable.ic_n_rewind
        }

        fun forwardIcon(sec: Int) = when (sec) {
            10 -> R.drawable.ic_n_fwd_10
            15 -> R.drawable.ic_n_fwd_15
            30 -> R.drawable.ic_n_fwd_30
            60 -> R.drawable.ic_n_fwd_60
            else -> R.drawable.ic_n_forward
        }
    }
}
