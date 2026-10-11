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

        session = MediaSession.Builder(this, ChapterPlayer(exo, packageName) { session })
            .setSessionActivity(openApp)
            .setCallback(Callback())
            .build()
            .also { it.setCustomLayout(seekButtons()) }
    }

    /** Кнопки перемотки на секунды — в шторке и на экране блокировки. */
    private fun seekButtons(): ImmutableList<CommandButton> {
        val conn = App.instance.player
        return ImmutableList.of(
            CommandButton.Builder()
                .setDisplayName("Назад на ${conn.skipBackMs / 1000} с")
                .setIconResId(R.drawable.ic_n_rewind)
                .setSessionCommand(skipBack)
                .build(),
            CommandButton.Builder()
                .setDisplayName("Вперёд на ${conn.skipForwardMs / 1000} с")
                .setIconResId(R.drawable.ic_n_forward)
                .setSessionCommand(skipForward)
                .build(),
        )
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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
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
    }
}
