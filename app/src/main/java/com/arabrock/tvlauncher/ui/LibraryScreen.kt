package com.arabrock.tvlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arabrock.tvlauncher.LauncherController
import com.arabrock.tvlauncher.data.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun rememberIcon(c: LauncherController, pkg: String): ImageBitmap? {
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) { c.apps.icon(pkg) }
    }
    return icon
}

/**
 * Application library. [assignSlot] = null: quick launcher (opens apps full screen).
 * Otherwise the chosen app is assigned to that window.
 */
@Composable
fun LibraryScreen(c: LauncherController, assignSlot: Int?) {
    val apps by c.apps.apps.collectAsState()
    val settings by c.settings.state.collectAsState()
    var query by remember { mutableStateOf("") }
    val firstItem = remember { FocusRequester() }

    LaunchedEffect(Unit) { delay(80); runCatching { firstItem.requestFocus() } }

    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
    }
    val favorites = remember(apps, settings.favorites) { settings.favorites.mapNotNull { f -> apps.firstOrNull { it.packageName == f } } }
    val recents = remember(apps, settings.recents) { settings.recents.mapNotNull { r -> apps.firstOrNull { it.packageName == r } }.take(6) }

    fun choose(app: AppEntry) {
        if (assignSlot == null) {
            c.goHome()
            c.launchFullScreen(app.packageName)
        } else {
            c.goHome()
            c.assign(assignSlot, app.packageName)
        }
    }

    ScreenFrame(
        title = if (assignSlot == null) "التطبيقات" else "اختر تطبيقاً للنافذة ${assignSlot + 1}",
        subtitle = "OK للاختيار • اضغط مطوّلاً لإضافة/إزالة من المفضلة • ⭐ = مفضلة",
    ) {
        Column {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("بحث:", color = Palette.Muted, fontSize = 15.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Palette.Text, fontSize = 18.sp),
                    modifier = Modifier
                        .width(360.dp)
                        .background(Palette.Panel, RoundedCornerShape(8.dp))
                        .border(1.dp, Palette.PanelHigh, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )
                if (assignSlot != null) TvButton("إفراغ النافذة") { c.goHome(); c.assign(assignSlot, null) }
                TvButton("رجوع") { c.goHome() }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(170.dp),
                modifier = Modifier.fillMaxSize().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                var first = true
                fun mod(): Modifier = if (first) { first = false; Modifier.focusRequester(firstItem) } else Modifier
                if (query.isBlank() && favorites.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Text("المفضلة", color = Palette.Accent, fontSize = 16.sp) }
                    val m = mod()
                    items(favorites, key = { "fav_" + it.packageName }) { app ->
                        AppTile(app.label, rememberIcon(c, app.packageName), if (app == favorites.first()) m else Modifier, badge = "⭐",
                            onLongClick = { c.settings.toggleFavorite(app.packageName) }) { choose(app) }
                    }
                }
                if (query.isBlank() && recents.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Text("المستخدمة مؤخراً", color = Palette.Accent, fontSize = 16.sp) }
                    val m = mod()
                    items(recents, key = { "rec_" + it.packageName }) { app ->
                        AppTile(app.label, rememberIcon(c, app.packageName), if (app == recents.first()) m else Modifier,
                            onLongClick = { c.settings.toggleFavorite(app.packageName) }) { choose(app) }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Text("كل التطبيقات (${filtered.size})", color = Palette.Accent, fontSize = 16.sp) }
                val m = mod()
                items(filtered, key = { "all_" + it.packageName }) { app ->
                    AppTile(
                        app.label, rememberIcon(c, app.packageName),
                        if (app == filtered.firstOrNull()) m else Modifier,
                        badge = if (app.packageName in settings.favorites) "⭐" else null,
                        onLongClick = { c.settings.toggleFavorite(app.packageName) },
                    ) { choose(app) }
                }
            }
        }
    }
}
