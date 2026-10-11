package com.shoosman.bookapp.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shoosman.bookapp.App
import com.shoosman.bookapp.CrashLog
import com.shoosman.bookapp.data.Downloader
import com.shoosman.bookapp.data.ImportProgress
import com.shoosman.bookapp.ui.common.ErrorDialog
import com.shoosman.bookapp.ui.common.NoticeDialog
import com.shoosman.bookapp.ui.common.ProgressDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class PendingDownload(val url: String, val userAgent: String, val disposition: String?, val mime: String?, val size: Long, val name: String, val referer: String?)

private val quickLinks = listOf(
    "Литрес" to "https://www.litres.ru/",
    "Яндекс Книги" to "https://books.yandex.ru/",
    "Author.Today" to "https://author.today/",
    "Литнет" to "https://litnet.com/",
    "ЛитМаркет" to "https://litmarket.ru/",
    "Rulate" to "https://tl.rulate.ru/",
)

/**
 * Встроенный браузер: входите в магазин один раз (вход запоминается), нажимаете «Скачать» —
 * файл сразу попадает в Чтец, новой книгой или к уже существующей.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(startUrl: String?, onBack: () -> Unit, onOpenBook: (Long) -> Unit) {
    val ctx = LocalContext.current
    val repo = App.instance.repo
    val snackbar = remember { SnackbarHostState() }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var address by remember { mutableStateOf(startUrl.orEmpty()) }
    var pageUrl by remember { mutableStateOf(startUrl.orEmpty()) }
    var loading by remember { mutableStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var showStart by remember { mutableStateOf(startUrl.isNullOrBlank()) }

    var pending by remember { mutableStateOf<PendingDownload?>(null) }
    var choosingBook by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ImportProgress?>(null) }
    var error by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun open(url: String) {
        val u = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
        showStart = false
        address = u
        webView?.loadUrl(u)
    }

    fun startDownload(d: PendingDownload, attachTo: Long?) {
        pending = null
        choosingBook = false
        progress = ImportProgress("Скачиваю «${d.name}»", null)
        CrashLog.note("Браузер: скачивание ${d.name}")
        repo.appScope.launch {
            val r = runCatching {
                val file = Downloader.download(ctx, d.url, d.userAgent, d.disposition, d.mime, d.referer) { progress = it }
                repo.importDownloaded(file.file, file.name, file.mime, attachTo) { progress = it }
            }
            withContext(Dispatchers.Main) {
                progress = null
                r.onSuccess { res ->
                    notice = res.notice
                    val result = snackbar.showSnackbar("Добавлено в библиотеку", actionLabel = "Открыть", withDismissAction = true)
                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onOpenBook(res.bookId)
                }.onFailure {
                    CrashLog.note("Браузер: ошибка скачивания ${it.message}")
                    error = (it.message ?: "Не удалось скачать файл") to CrashLog.describe(it, "Ошибка скачивания")
                }
            }
        }
    }

    BackHandler {
        val w = webView
        if (!showStart && w != null && w.canGoBack()) w.goBack() else onBack()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.Close, "Закрыть браузер") } },
                    title = {
                        TextField(
                            value = address,
                            onValueChange = { address = it },
                            placeholder = { Text("Адрес сайта") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { open(address) }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    actions = {
                        IconButton(onClick = { webView?.reload() }) { Icon(Icons.Filled.Refresh, "Обновить") }
                    },
                )
                if (loading in 1..99) {
                    LinearProgressIndicator(progress = { loading / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                pageUrl = url
                                address = url
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                canGoBack = view.canGoBack()
                                CookieManager.getInstance().flush()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                loading = newProgress
                            }
                        }
                        setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                            val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
                            pending = PendingDownload(url, userAgent, contentDisposition, mimetype, contentLength, name, pageUrl)
                        }
                        if (!startUrl.isNullOrBlank()) loadUrl(startUrl)
                        webView = this
                    }
                },
            )

            if (showStart) {
                LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp)) {
                    item {
                        Text(
                            "Войдите в магазин, где покупаете книги, — вход запомнится. Потом нажмите на сайте «Скачать» (FB2, EPUB или MP3), и файл сразу попадёт в Чтец.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    items(quickLinks) { (title, url) ->
                        ListItem(
                            headlineContent = { Text(title) },
                            supportingContent = { Text(url.removePrefix("https://").trimEnd('/')) },
                            modifier = Modifier.clickable { open(url) },
                        )
                    }
                }
            }
        }
    }

    pending?.let { d ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Добавить в Чтец?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(d.name, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (d.size > 0) {
                        Text(
                            "%.1f МБ".format(d.size / 1048576.0),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                Column {
                    TextButton(onClick = { startDownload(d, null) }) { Text("Новой книгой") }
                    TextButton(onClick = { choosingBook = true }) { Text("К книге в библиотеке…") }
                    TextButton(onClick = { pending = null }) { Text("Отмена") }
                }
            },
        )
    }

    if (choosingBook) {
        val d = pending
        val books by repo.dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
        AlertDialog(
            onDismissRequest = { choosingBook = false },
            title = { Text("К какой книге?") },
            text = {
                LazyColumn(Modifier.height(360.dp)) {
                    items(books, key = { it.id }) { b ->
                        ListItem(
                            headlineContent = { Text(b.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { if (b.author.isNotBlank()) Text(b.author, maxLines = 1) },
                            modifier = Modifier.clickable { if (d != null) startDownload(d, b.id) },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingBook = false }) { Text("Назад") } },
        )
    }

    progress?.let { ProgressDialog(it) }
    notice?.let { NoticeDialog(it) { notice = null } }
    error?.let { (msg, details) -> ErrorDialog(msg, details, onDismiss = { error = null }) }

    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()
            webView?.destroy()
        }
    }
}
