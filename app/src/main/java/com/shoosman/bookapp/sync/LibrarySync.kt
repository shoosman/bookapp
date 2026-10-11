package com.shoosman.bookapp.sync

import android.content.Context
import com.shoosman.bookapp.CrashLog
import com.shoosman.bookapp.data.AnchorEntity
import com.shoosman.bookapp.data.BookEntity
import com.shoosman.bookapp.data.BookRepository
import com.shoosman.bookapp.data.BookStatus
import com.shoosman.bookapp.data.genreList
import com.shoosman.bookapp.data.BookmarkEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SyncUi(
    val connected: Boolean = false,
    val login: String = "",
    val repo: String = LibrarySync.DEFAULT_REPO,
    val running: Boolean = false,
    val lastSync: Long = 0,
    val error: String? = null,
    /** Идёт вход: код, который нужно ввести на GitHub. */
    val userCode: String? = null,
    val verificationUri: String = "https://github.com/login/device",
)

/**
 * Синхронизация библиотеки с приватным репозиторием на GitHub.
 *
 * В репозитории: books/<id>.json — данные книги, covers/<id>.jpg — обложка,
 * texts/<id>.<расширение> — файл книги, README.md — таблица «Мои книги».
 * Каждая синхронизация — один коммит со всеми изменениями.
 */
class LibrarySync(ctx: Context, private val repo: BookRepository) {

    private val dao = repo.dao
    private val prefs = ctx.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var pending: Job? = null
    private var authJob: Job? = null
    @Volatile private var applying = false

    private val _ui = MutableStateFlow(
        SyncUi(
            connected = prefs.getString(KEY_TOKEN, null) != null,
            login = prefs.getString(KEY_LOGIN, "").orEmpty(),
            repo = prefs.getString(KEY_REPO, DEFAULT_REPO) ?: DEFAULT_REPO,
            lastSync = prefs.getLong(KEY_LAST, 0),
        ),
    )
    val ui: StateFlow<SyncUi> = _ui.asStateFlow()

    private val token: String? get() = prefs.getString(KEY_TOKEN, null)
    private val repoName: String get() = prefs.getString(KEY_REPO, DEFAULT_REPO) ?: DEFAULT_REPO

    // ---------------- когда синхронизироваться ----------------

    /** Следить за изменениями в библиотеке и отправлять их через пару минут тишины. */
    fun startAutoSync() {
        scope.launch {
            dao.changes().drop(1).collect {
                if (!applying && token != null) requestSync(120_000)
            }
        }
    }

    /** Запланировать синхронизацию (повторный вызов переносит её). */
    fun requestSync(delayMs: Long = 0) {
        if (token == null) return
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            syncNow()
        }
    }

    // ---------------- вход ----------------

    fun startLogin() {
        authJob?.cancel()
        authJob = scope.launch {
            try {
                CrashLog.note("Синхронизация: вход по коду")
                val code = GitHubAuth.start()
                _ui.update { it.copy(userCode = code.userCode, verificationUri = code.verificationUri, error = null) }
                var interval = code.interval.coerceAtLeast(5)
                val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(interval * 1000L)
                    when (val r = GitHubAuth.poll(code)) {
                        is GitHubAuth.Poll.Token -> {
                            finishLogin(r.token)
                            return@launch
                        }
                        GitHubAuth.Poll.Pending -> Unit
                        GitHubAuth.Poll.SlowDown -> interval += 5
                        is GitHubAuth.Poll.Failed -> {
                            _ui.update { it.copy(userCode = null, error = r.message) }
                            return@launch
                        }
                    }
                }
                _ui.update { it.copy(userCode = null, error = "Время на ввод кода вышло. Попробуйте ещё раз.") }
            } catch (e: Exception) {
                CrashLog.note("Синхронизация: ошибка входа ${e.message}")
                _ui.update { it.copy(userCode = null, error = "Не удалось войти: ${e.message}") }
            }
        }
    }

    private suspend fun finishLogin(token: String) {
        // Доступ получен — сохраняем сразу, чтобы сбой сети дальше не заставлял входить заново.
        prefs.edit().putString(KEY_TOKEN, token).apply()
        _ui.update { it.copy(connected = true, userCode = null, error = null) }
        val api = GitHubApi(token, repoName)
        try {
            val login = api.user().optString("login")
            prefs.edit().putString(KEY_LOGIN, login).apply()
            _ui.update { it.copy(login = login) }
            api.repoInfo()
        } catch (e: GitHubException) {
            if (e.code == 404) {
                _ui.update {
                    it.copy(error = "Нет доступа к репозиторию $repoName. Проверьте, что приложение Chtets Sync установлено на него в настройках GitHub.")
                }
                return
            }
            _ui.update { it.copy(error = e.message) }
            return
        } catch (e: java.io.IOException) {
            CrashLog.note("Синхронизация: после входа нет связи ${e.message}")
            _ui.update { it.copy(error = "Вход выполнен, но сейчас нет связи с GitHub. Синхронизация запустится сама, когда связь появится.") }
            return
        }
        CrashLog.note("Синхронизация: вход выполнен")
        syncNow()
    }

    fun cancelLogin() {
        authJob?.cancel()
        _ui.update { it.copy(userCode = null) }
    }

    fun logout() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_LOGIN).remove(KEY_STATE).remove(KEY_BRANCH).apply()
        _ui.update { SyncUi(repo = repoName) }
    }

    // ---------------- синхронизация ----------------

    suspend fun syncNow() {
        if (token == null) return
        mutex.withLock {
            _ui.update { it.copy(running = true, error = null) }
            try {
                val count = kotlinx.coroutines.withContext(Dispatchers.IO) { doSync() }
                val now = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST, now).apply()
                CrashLog.note("Синхронизация: готово, изменений $count")
                _ui.update { it.copy(running = false, lastSync = now) }
            } catch (e: Exception) {
                CrashLog.note("Синхронизация: ошибка ${e.javaClass.simpleName} ${e.message}")
                val msg = when {
                    e is GitHubException && e.code == 401 -> {
                        prefs.edit().remove(KEY_TOKEN).apply()
                        _ui.update { it.copy(connected = false) }
                        e.message
                    }
                    e is java.net.UnknownHostException -> "Не удалось найти адрес GitHub. Проверьте интернет или VPN — попробую ещё раз позже."
                    e is java.io.IOException -> "Нет связи с GitHub: ${e.message}"
                    else -> e.message ?: e.javaClass.simpleName
                }
                _ui.update { it.copy(running = false, error = msg) }
            }
        }
    }

    /** Возвращает число книг, отправленных в облако. */
    private fun doSync(): Int {
        val api = GitHubApi(token ?: return 0, repoName)
        if (prefs.getString(KEY_LOGIN, "").isNullOrBlank()) {
            runCatching { api.user().optString("login") }.getOrNull()?.takeIf { it.isNotBlank() }?.let { login ->
                prefs.edit().putString(KEY_LOGIN, login).apply()
                _ui.update { it.copy(login = login) }
            }
        }
        val branch = prefs.getString(KEY_BRANCH, null)
            ?: api.repoInfo().optString("default_branch", "main").also { prefs.edit().putString(KEY_BRANCH, it).apply() }

        repeat(3) { attempt ->
            val head = api.branchHead(branch)
            val baseTree = api.commitTree(head)
            val files = api.tree(baseTree)
            val state = loadState()
            val newState = JSONObject(state.toString())
            val changes = LinkedHashMap<String, String?>()
            var uploaded = 0

            val locals = dao.allForSyncBlocking().associateBy { it.uid }
            val remoteIds = files.keys
                .filter { it.startsWith("books/") && it.endsWith(".json") }
                .map { it.removePrefix("books/").removeSuffix(".json") }

            for (id in (locals.keys + remoteIds).toSet()) {
                val path = "books/$id.json"
                val remoteSha = files[path]
                val known = state.optJSONObject(id)
                val remoteDoc = if (remoteSha != null && remoteSha != known?.optString("sha")) {
                    runCatching { BookDoc.parse(String(api.blob(remoteSha))) }.getOrNull()
                } else {
                    null
                }

                var local = locals[id]
                if (local == null) {
                    if (remoteDoc != null && !remoteDoc.deleted) {
                        local = createLocal(remoteDoc)
                        applyRemote(local, docOf(local), remoteDoc, api, files)
                    }
                    if (remoteSha != null && remoteDoc != null) {
                        newState.put(id, JSONObject().put("sha", remoteSha).put("hash", hash(remoteDoc.toJson())))
                    }
                    continue
                }

                val localDoc = docOf(local)
                val merged = if (remoteDoc != null) BookDoc.merge(localDoc, remoteDoc) else localDoc
                if (remoteDoc != null) applyRemote(local, localDoc, merged, api, files)

                val json = merged.toJson()
                val mergedHash = hash(json)
                val remoteHash = remoteDoc?.let { hash(it.toJson()) } ?: known?.optString("hash")
                if (remoteSha == null || mergedHash != remoteHash) {
                    val fresh = dao.getBlocking(local.id) ?: local
                    stageFiles(fresh, merged, files, changes, api)
                    val blob = api.createBlob(json.toByteArray())
                    changes[path] = blob
                    newState.put(id, JSONObject().put("sha", blob).put("hash", mergedHash))
                    uploaded++
                } else {
                    newState.put(id, JSONObject().put("sha", remoteSha).put("hash", mergedHash))
                }
            }

            if (uploaded > 0 || files["README.md"] == null) {
                val readme = buildReadme().toByteArray()
                if (files["README.md"] != GitHubApi.gitSha(readme)) changes["README.md"] = api.createBlob(readme)
            }

            if (changes.isEmpty()) {
                saveState(newState)
                return uploaded
            }
            val message = if (uploaded == 1) "Синхронизация: 1 книга" else "Синхронизация: книг — $uploaded"
            val commit = api.commit(head, baseTree, changes, message)
            try {
                api.moveBranch(branch, commit)
                saveState(newState)
                return uploaded
            } catch (e: GitHubException) {
                // Кто-то (другое устройство) успел раньше — повторяем с новыми данными.
                if (e.code != 422 || attempt == 2) throw e
            }
        }
        return 0
    }

    // ---------------- локальная книга → документ ----------------

    private fun docOf(b: BookEntity): BookDoc {
        val dir = File(b.dir)
        val text = if (b.textPath != null) {
            val source = File(dir, "source")
            val name = runCatching { File(dir, "source.name").readText() }.getOrDefault("book.fb2")
            if (source.exists()) {
                BookDoc.TextDoc(
                    path = "texts/${b.uid}.${extension(name)}",
                    name = name,
                    format = b.textFormat.orEmpty(),
                    sha = fileSha(source),
                )
            } else {
                null
            }
        } else {
            null
        }
        val cover = b.coverPath?.takeIf { File(it).exists() }?.let { "covers/${b.uid}.jpg" }
        return BookDoc(
            id = b.uid,
            title = b.title, author = b.author, annotation = b.annotation, sourceUrl = b.sourceUrl,
            addedAt = b.addedAt, cover = cover, metaTs = b.metaUpdatedAt,
            series = b.series, seriesIndex = Math.round(b.seriesIndex * 100.0) / 100.0,
            genres = b.genreList,
            status = BookDoc.statusName(b.status), rating = b.rating, notes = b.notes,
            startedAt = b.startedAt, finishedAt = b.finishedAt, shelfTs = b.shelfUpdatedAt,
            text = text, textTs = b.textUpdatedAt,
            textChar = b.readPos,
            textPercent = if (b.totalChars > 0) b.readPos.toDouble() / b.totalChars else 0.0,
            audioMs = b.audioGlobalMs,
            audioPercent = if (b.audioTotalMs > 0) b.audioGlobalMs.toDouble() / b.audioTotalMs else 0.0,
            progressTs = b.progressUpdatedAt,
            audioTotalMs = b.audioTotalMs,
            bookmarks = dao.allBookmarksBlocking(b.id).map {
                BookDoc.MarkDoc(it.uid, it.charPos, it.audioMs, it.snippet, it.note, it.createdAt, it.deleted)
            },
            anchors = dao.allAnchorsBlocking(b.id).map { BookDoc.AnchorDoc(it.uid, it.audioMs, it.charPos, it.deleted) },
            deleted = b.deleted,
        )
    }

    // ---------------- документ из облака → локальная книга ----------------

    private fun createLocal(doc: BookDoc): BookEntity {
        val dir = repo.newBookDir()
        val entity = BookEntity(
            uid = doc.id, title = doc.title, author = doc.author, dir = dir.absolutePath,
            addedAt = doc.addedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            metaUpdatedAt = 0, shelfUpdatedAt = 0, textUpdatedAt = 0, progressUpdatedAt = 0,
        )
        val id = dao.insertBlocking(entity)
        return entity.copy(id = id)
    }

    private fun applyRemote(local: BookEntity, localDoc: BookDoc, merged: BookDoc, api: GitHubApi, files: Map<String, String>) {
        applying = true
        try {
            var b = local
            val dir = File(b.dir).apply { mkdirs() }

            if (merged.metaTs > localDoc.metaTs) {
                b = b.copy(
                    title = merged.title, author = merged.author, annotation = merged.annotation,
                    sourceUrl = merged.sourceUrl, metaUpdatedAt = merged.metaTs,
                    series = merged.series, seriesIndex = merged.seriesIndex.toFloat(),
                    genres = merged.genres.joinToString("; "),
                )
                val coverFile = repo.coverFile(dir)
                val remoteCover = merged.cover?.let { files[it] }
                if (remoteCover != null) {
                    if (!coverFile.exists() || fileSha(coverFile) != remoteCover) {
                        runCatching { coverFile.writeBytes(api.blob(remoteCover)) }
                    }
                    if (coverFile.exists()) b = b.copy(coverPath = coverFile.absolutePath)
                } else if (merged.cover == null) {
                    coverFile.delete()
                    b = b.copy(coverPath = null)
                }
            }
            if (merged.addedAt > 0) b = b.copy(addedAt = merged.addedAt)

            if (merged.shelfTs > localDoc.shelfTs) {
                b = b.copy(
                    status = BookDoc.statusCode(merged.status), rating = merged.rating, notes = merged.notes,
                    startedAt = merged.startedAt, finishedAt = merged.finishedAt, shelfUpdatedAt = merged.shelfTs,
                )
            }

            if (merged.textTs > localDoc.textTs) {
                val t = merged.text
                if (t == null) {
                    File(dir, "source").delete()
                    repo.contentFile(dir).delete()
                    b = b.copy(textPath = null, textFormat = null, totalChars = 0, chapterCount = 0)
                } else if (t.sha != localDoc.text?.sha) {
                    val blobSha = files[t.path] ?: t.sha
                    runCatching {
                        File(dir, "source").writeBytes(api.blob(blobSha))
                        val parsed = repo.parseSource(dir, t.name)
                        b = b.copy(
                            textPath = repo.contentFile(dir).absolutePath,
                            textFormat = parsed.format,
                            totalChars = parsed.content.totalChars,
                            chapterCount = parsed.content.chapters.size,
                        )
                        if (b.coverPath == null) {
                            parsed.cover?.let {
                                val f = repo.coverFile(dir)
                                f.writeBytes(it)
                                b = b.copy(coverPath = f.absolutePath)
                            }
                        }
                    }.onFailure { CrashLog.note("Синхронизация: не удалось скачать текст ${it.message}") }
                }
                b = b.copy(textUpdatedAt = merged.textTs)
                repo.invalidateContent()
            }

            if (merged.progressTs > localDoc.progressTs) {
                val tracks = dao.tracksBlocking(b.id)
                var track = 0
                var pos = merged.audioMs
                var start = 0L
                for ((i, t) in tracks.withIndex()) {
                    if (merged.audioMs >= start) {
                        track = i
                        pos = merged.audioMs - start
                    }
                    start += t.durationMs
                }
                b = b.copy(
                    readPos = if (b.totalChars > 0) merged.textChar.coerceIn(0, b.totalChars) else merged.textChar,
                    audioGlobalMs = merged.audioMs, audioTrack = track, audioPosMs = pos.coerceAtLeast(0),
                    progressUpdatedAt = merged.progressTs,
                )
            }

            if (merged.deleted && !b.deleted) {
                dao.deleteTracksBlocking(b.id)
                dir.deleteRecursively()
                b = b.copy(deleted = true, textPath = null, textFormat = null, coverPath = null, audioTotalMs = 0)
            }
            dao.updateBlocking(b)

            val marks = dao.allBookmarksBlocking(b.id).associateBy { it.uid }
            for (m in merged.bookmarks) {
                val existing = marks[m.id]
                if (existing == null) {
                    dao.insertBookmarkBlocking(
                        BookmarkEntity(
                            bookId = b.id, charPos = m.charPos, audioMs = m.audioMs, snippet = m.snippet,
                            note = m.note, createdAt = m.createdAt, uid = m.id, deleted = m.deleted,
                        ),
                    )
                } else if (existing.deleted != m.deleted) {
                    dao.updateBookmarkBlocking(existing.copy(deleted = m.deleted))
                }
            }
            val anchors = dao.allAnchorsBlocking(b.id).associateBy { it.uid }
            for (a in merged.anchors) {
                val existing = anchors[a.id]
                if (existing == null) {
                    dao.insertAnchorBlocking(AnchorEntity(bookId = b.id, audioMs = a.audioMs, charPos = a.charPos, uid = a.id, deleted = a.deleted))
                } else if (existing.deleted != a.deleted) {
                    dao.updateAnchorBlocking(existing.copy(deleted = a.deleted))
                }
            }
        } finally {
            applying = false
        }
    }

    /** Положить в коммит файлы книги (текст, обложку), если их ещё нет в облаке или они поменялись. */
    private fun stageFiles(b: BookEntity, merged: BookDoc, files: Map<String, String>, changes: MutableMap<String, String?>, api: GitHubApi) {
        val dir = File(b.dir)
        val textPaths = files.keys.filter { it.startsWith("texts/${b.uid}.") }
        val t = merged.text
        if (t == null || merged.deleted) {
            textPaths.forEach { changes[it] = null }
        } else {
            val source = File(dir, "source")
            if (source.exists() && fileSha(source) == t.sha && files[t.path] != t.sha) {
                changes[t.path] = api.createBlob(source.readBytes())
            }
            textPaths.filter { it != t.path }.forEach { changes[it] = null }
        }

        val coverPath = "covers/${b.uid}.jpg"
        val coverFile = b.coverPath?.let { File(it) }
        if (merged.cover == null || merged.deleted) {
            if (files.containsKey(coverPath)) changes[coverPath] = null
        } else if (coverFile != null && coverFile.exists()) {
            val sha = fileSha(coverFile)
            if (files[coverPath] != sha) changes[coverPath] = api.createBlob(coverFile.readBytes())
        }
    }

    // ---------------- README ----------------

    private fun buildReadme(): String {
        val books = dao.allForSyncBlocking().filter { !it.deleted }
        val date = SimpleDateFormat("d MMM yyyy", Locale("ru"))
        val sb = StringBuilder("# Мои книги\n\n")
        sb.append("Книг в библиотеке: ${books.size}. Обновлено приложением «Чтец».\n\n")
        for (status in listOf(BookStatus.READING, BookStatus.WANT, BookStatus.FINISHED, BookStatus.DROPPED)) {
            val list = books.filter { it.status == status }.let { l ->
                if (status == BookStatus.FINISHED) l.sortedByDescending { it.finishedAt } else l.sortedBy { it.title.lowercase() }
            }
            if (list.isEmpty()) continue
            sb.append("## ${BookStatus.label(status)} · ${list.size}\n\n")
            sb.append("| | Книга | Автор | Оценка | Даты |\n|---|---|---|---|---|\n")
            for (b in list) {
                val cover = if (b.coverPath != null) "<img src=\"covers/${b.uid}.jpg\" width=\"40\">" else ""
                val title = b.title.replace("|", "\\|").let { if (b.sourceUrl.isNotBlank()) "[$it](${b.sourceUrl})" else it }
                val stars = if (b.rating > 0) "★".repeat(b.rating) else ""
                val dates = listOfNotNull(
                    b.startedAt.takeIf { it > 0 }?.let { date.format(Date(it)) },
                    b.finishedAt.takeIf { it > 0 }?.let { date.format(Date(it)) },
                ).joinToString(" — ")
                sb.append("| $cover | $title | ${b.author.replace("|", "\\|")} | $stars | $dates |\n")
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    // ---------------- служебное ----------------

    private fun loadState(): JSONObject = runCatching { JSONObject(prefs.getString(KEY_STATE, "{}")!!) }.getOrDefault(JSONObject())
    private fun saveState(state: JSONObject) = prefs.edit().putString(KEY_STATE, state.toString()).apply()

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Git-хеш файла с кэшем по размеру и дате изменения. */
    private fun fileSha(f: File): String {
        val key = "sha:" + f.absolutePath
        val stamp = "${f.length()}:${f.lastModified()}"
        prefs.getString(key, null)?.let { if (it.startsWith("$stamp|")) return it.substringAfter('|') }
        val sha = GitHubApi.gitSha(f.readBytes())
        prefs.edit().putString(key, "$stamp|$sha").apply()
        return sha
    }

    private fun extension(name: String): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".fb2.zip") -> "fb2.zip"
            '.' in n -> n.substringAfterLast('.').filter { it.isLetterOrDigit() }.ifBlank { "bin" }
            else -> "bin"
        }
    }

    companion object {
        const val DEFAULT_REPO = "shoosman/chtets-library"
        private const val KEY_TOKEN = "token"
        private const val KEY_LOGIN = "login"
        private const val KEY_REPO = "repo"
        private const val KEY_BRANCH = "branch"
        /** v3: циклы и жанры — после обновления облако перечитывается целиком один раз. */
        private const val KEY_STATE = "state_v3"
        private const val KEY_LAST = "lastSync"
    }
}
