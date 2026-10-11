package com.shoosman.bookapp.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Settings
import com.shoosman.bookapp.ui.catalog.CatalogScreen
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shoosman.bookapp.App
import com.shoosman.bookapp.data.AppSettings
import com.shoosman.bookapp.ui.common.MiniPlayer
import com.shoosman.bookapp.ui.library.LibraryTab
import com.shoosman.bookapp.ui.settings.SettingsScreen

/** Главный экран с нижними вкладками: Главная · Библиотека · Настройки. */
@Composable
fun MainScreen(
    settings: AppSettings,
    onOpenBook: (Long) -> Unit,
    onRead: (Long) -> Unit,
    onListen: (Long) -> Unit,
    onOpenBrowser: (String?) -> Unit,
) {
    val books by App.instance.repo.dao.observeAll().collectAsStateWithLifecycle(initialValue = null)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var shelf by rememberSaveable { mutableIntStateOf(SHELF_ALL) }
    val importer = remember { Importer() }

    // После обновления один раз дополняем библиотеку (обложки, жанры, циклы) — когда облако уже подтянулось.
    androidx.compose.runtime.LaunchedEffect(books?.size) {
        if ((books?.size ?: 0) > 0) {
            kotlinx.coroutines.delay(15_000)
            App.instance.enricher.run(auto = true)
        }
    }

    // Поделились ссылкой — открываем «Новую книгу» с ней.
    val shared by App.instance.sharedText.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(shared) {
        shared?.let {
            importer.manual = it
            App.instance.sharedText.value = null
        }
    }

    Scaffold(
        bottomBar = {
            Column {
                MiniPlayer(onOpen = onListen, skipBackSec = settings.skipBackSec, insetBottom = false)
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Outlined.Home, null) },
                        label = { Text("Главная") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.AutoMirrored.Outlined.LibraryBooks, null) },
                        label = { Text("Библиотека") },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Outlined.Explore, null) },
                        label = { Text("Каталог") },
                    )
                    NavigationBarItem(
                        selected = tab == 3,
                        onClick = { tab = 3 },
                        icon = { Icon(Icons.Outlined.Settings, null) },
                        label = { Text("Настройки") },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> androidx.compose.runtime.CompositionLocalProvider(
                    LocalTileScale provides when (settings.tileSize) { "small" -> 0.8f; "large" -> 1.3f; else -> 1f },
                ) { HomeTab(
                    books = books,
                    importer = importer,
                    onOpenBook = onOpenBook,
                    onRead = onRead,
                    onListen = onListen,
                    onShelf = { shelf = it; tab = 1 },
                ) }
                1 -> LibraryTab(
                    books = books,
                    shelf = shelf,
                    onShelfChange = { shelf = it },
                    importer = importer,
                    onOpenBook = onOpenBook,
                )
                2 -> CatalogScreen(onOpenBrowser = { onOpenBrowser(it) }, onOpenBook = onOpenBook)
                else -> SettingsScreen(settings = settings, onBack = null)
            }
        }
    }

    ImporterHost(importer, onOpenBook, onOpenBrowser)
}
