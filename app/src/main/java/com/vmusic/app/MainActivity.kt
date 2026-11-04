package com.vmusic.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

object Tick { var v by mutableIntStateOf(0) }

@UnstableApi
class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        // edge-to-edge: the app draws under the status bar and nav bar, so no black strip
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val ic = WindowCompat.getInsetsController(window, window.decorView)
        ic.isAppearanceLightStatusBars = false; ic.isAppearanceLightNavigationBars = false
        PlayerHolder.init(applicationContext)
        try {
            MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
                .buildAsync().also { f -> f.addListener({ try { controller = f.get() } catch (_: Exception) {} }, ContextCompat.getMainExecutor(this)) }
        } catch (_: Exception) {}
        setContent { VMusicApp(this) }
    }
    override fun onResume() { super.onResume(); Tick.v++ }
    override fun onDestroy() { controller?.release(); super.onDestroy() }
}

fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

@Composable
fun Cover(bmp: Bitmap?, modifier: Modifier, round: Int = 12) {
    Box(modifier.clip(RoundedCornerShape(round.dp)).background(Color(0x33FFFFFF)), Alignment.Center) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.MusicNote, null, tint = Color.White.copy(.6f))
    }
}

@Composable
fun LyricsView(lines: List<LrcLine>, pos: Long, onPick: () -> Unit, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) {
        Column(modifier, Arrangement.Center, Alignment.CenterHorizontally) {
            Text("No lyrics found", color = Color.White.copy(.7f))
            TextButton(onPick) { Text("Choose .lrc file") }
        }
        return
    }
    val synced = lines.first().time >= 0
    val idx = if (synced) lines.indexOfLast { it.time <= pos }.coerceAtLeast(0) else -1
    val st = rememberLazyListState()
    LaunchedEffect(idx) { if (synced) st.animateScrollToItem((idx - 2).coerceAtLeast(0)) }
    LazyColumn(modifier, st, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 40.dp)) {
        itemsIndexed(lines) { i, l ->
            Text(l.text, textAlign = TextAlign.Center, fontWeight = if (i == idx) FontWeight.Bold else FontWeight.Normal,
                color = if (!synced || i == idx) Color.White else Color.White.copy(.4f),
                fontSize = if (i == idx) 24.sp else 18.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(t: Track, current: Boolean, fav: Boolean, selecting: Boolean, selected: Boolean, accent: Color,
             onClick: () -> Unit, onLong: () -> Unit) {
    val ctx = LocalContext.current
    val thumb by produceState<Bitmap?>(null, t.uri) { value = loadCover(ctx, t, 128) }
    Row(Modifier.fillMaxWidth().background(if (selected) accent.copy(.28f) else Color.Transparent)
        .combinedClickable(onClick = onClick, onLongClick = onLong).padding(16.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (selecting) { Checkbox(selected, null); Spacer(Modifier.width(8.dp)) }
        Cover(thumb, Modifier.size(54.dp), 12)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = if (current) accent else Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((t.artist.ifBlank { if (t.isVideo) "Video" else "Unknown artist" }) + "  \u00B7  " + fmt(t.duration),
                    color = Color.White.copy(.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                if (t.isShort()) { Spacer(Modifier.width(6.dp)); Icon(Icons.Filled.Warning, null, tint = Color(0xFFFFB74D), modifier = Modifier.size(14.dp)) }
            }
        }
        if (fav) Icon(Icons.Filled.Favorite, null, tint = accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun PlaylistPicker(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var n by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onDismiss) { Text("Cancel") } },
        title = { Text("Add to playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Store.playlists.keys.sorted().forEach { k -> TextButton({ onPick(k) }, Modifier.fillMaxWidth()) { Text(k, maxLines = 1) } }
                OutlinedTextField(n, { n = it }, label = { Text("New playlist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button({ if (n.isNotBlank()) onPick(n.trim()) }, enabled = n.isNotBlank()) { Text("Create & add") }
            }
        })
}

@UnstableApi
@Composable
fun VMusicApp(act: ComponentActivity) {
    val ctx = LocalContext.current
    val p = PlayerHolder.player
    var tracks by remember { mutableStateOf(listOf<Track>()) }
    var curId by remember { mutableStateOf(p.currentMediaItem?.mediaId) }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var full by remember { mutableStateOf(false) }
    var showFx by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }            // 0 songs 1 videos 2 favorites 3 playlists 4 settings
    var openList by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    var pickFor by remember { mutableStateOf<List<String>?>(null) }
    var newListDlg by remember { mutableStateOf(false) }
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    var lyrics by remember { mutableStateOf(listOf<LrcLine>()) }
    var greet by remember { mutableIntStateOf(0) }           // 1 = ask name, 2 = hello
    var hour by remember { mutableLongStateOf(System.currentTimeMillis() / 3600000L) }

    val perms = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tracks = loadTracks(ctx) }
    val lrcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) try { lyrics = parseLrc(ctx.contentResolver.openInputStream(u)!!.bufferedReader().readText()) } catch (_: Exception) {}
    }
    val wpPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { us ->
        if (us.isNotEmpty()) { importWallpapers(ctx, us); Store.wpVersion++ }
    }

    LaunchedEffect(Tick.v) {
        if (perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED } ||
            (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED))
            tracks = withContextIO { loadTracks(ctx) }
        else if (Tick.v == 0 || tracks.isEmpty()) permLauncher.launch(perms)
    }
    LaunchedEffect(Unit) {
        if (Store.name.isBlank()) greet = 1
        else if (System.currentTimeMillis() - Store.lastGreet > 24 * 3600_000L) { greet = 2; Store.greeted(); delay(2200); greet = 0 }
    }
    LaunchedEffect(Unit) { while (true) { hour = System.currentTimeMillis() / 3600000L; delay(30_000) } }
    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onMediaItemTransition(m: MediaItem?, r: Int) { curId = m?.mediaId }
            override fun onIsPlayingChanged(b: Boolean) { playing = b }
            override fun onPlayerError(e: PlaybackException) { Toast.makeText(ctx, "Can't play this file (${e.errorCodeName})", Toast.LENGTH_LONG).show() }
        }
        p.addListener(l); playing = p.isPlaying; curId = p.currentMediaItem?.mediaId
        onDispose { p.removeListener(l) }
    }
    LaunchedEffect(Unit) { while (true) { pos = p.currentPosition; dur = p.duration.let { if (it == C.TIME_UNSET) 0L else it }; delay(250) } }

    val hiddenSet = Store.hidden.toSet()
    val visible = tracks.filter { it.id !in hiddenSet }
    val track = tracks.firstOrNull { it.id == curId }
    LaunchedEffect(track?.id) {
        cover = null; lyrics = emptyList()
        if (track != null) { cover = loadCover(ctx, track, 600); lyrics = loadLrc(track) }
    }
    LaunchedEffect(Store.asMusic, focus) {
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, Store.asMusic && !focus).build()
    }
    DisposableEffect(focus) {
        val ctl = WindowCompat.getInsetsController(act.window, act.window.decorView)
        if (focus) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
            act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            p.repeatMode = Player.REPEAT_MODE_ALL; p.play()
        }
        onDispose {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ctl.show(WindowInsetsCompat.Type.systemBars())
            act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    BackHandler(focus || full || selected.isNotEmpty() || openList != null) {
        when { focus -> focus = false; full -> full = false; selected.isNotEmpty() -> selected.clear(); else -> openList = null }
    }

    fun play(list: List<Track>, i: Int) {
        p.setMediaItems(list.map { it.toItem() }, i, 0L); p.prepare(); p.play(); curId = list[i].id
    }

    /* ---------- background + accent ---------- */
    val mode = Store.themeMode
    val wpIds = remember(Store.wpVersion) { wallpaperIds(ctx) }
    val wpId = if (wpIds.isEmpty()) null else if (Store.wpMode == 0) wpIds[(hour % wpIds.size).toInt()]
        else (Store.wpSingle.takeIf { it in wpIds } ?: wpIds.first())
    val wpBmp by produceState<Bitmap?>(null, wpId, mode) { value = if (mode == 2 && wpId != null) loadWallpaper(ctx, wpId, 1080) else null }
    val bgBmp = when (mode) { 1 -> cover; 2 -> wpBmp; else -> null }
    val base = Color(0xFF9D8CFF)
    val accentRaw by produceState(base, bgBmp) { value = bgBmp?.let { accentOf(it, base) } ?: base }
    val accent by animateColorAsState(accentRaw, label = "accent")
    val scheme = darkColorScheme(primary = accent, onPrimary = Color.Black, secondary = accent, surface = Color(0xFF14141C),
        surfaceVariant = Color(0x33FFFFFF), background = Color.Black, secondaryContainer = accent.copy(.35f), onSecondaryContainer = Color.White)

    MaterialTheme(colorScheme = scheme) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Crossfade(bgBmp, label = "bg") { b ->
                if (b != null) Box(Modifier.fillMaxSize()) {
                    Image(b.asImageBitmap(), null, Modifier.fillMaxSize().then(if (mode == 1) Modifier.blur(36.dp) else Modifier), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(if (mode == 1) .55f else .5f)))
                }
            }
            val sel = selected.isNotEmpty()
            when {
                focus -> Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(.8f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Cover(cover, Modifier.fillMaxHeight(.7f).aspectRatio(1f), 20)
                        Spacer(Modifier.height(14.dp))
                        Text(track?.title ?: "", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track?.artist ?: "", color = Color.White.copy(.7f), maxLines = 1)
                    }
                    LyricsView(lyrics, pos, { lrcPicker.launch(arrayOf("*/*")) }, Modifier.weight(1.2f).fillMaxHeight())
                }
                full -> Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ full = false }) { Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color.White) }
                        Spacer(Modifier.weight(1f))
                        TextButton({ showLyrics = !showLyrics }) { Text(if (showLyrics) "Cover" else "Lyrics") }
                        IconButton({ track?.let { Store.toggleFav(it.id) } }) {
                            val f = track?.id in Store.favs
                            Icon(if (f) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null, tint = if (f) accent else Color.White) }
                        IconButton({ showFx = true }) { Icon(Icons.Filled.Tune, null, tint = Color.White) }
                        IconButton({ focus = true }) { Icon(Icons.Filled.Fullscreen, null, tint = Color.White) }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                        when {
                            showLyrics -> LyricsView(lyrics, pos, { lrcPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxSize())
                            track?.isVideo == true && !Store.asMusic -> AndroidView({ PlayerView(it).apply { player = p; useController = false } }, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                            else -> Cover(cover, Modifier.fillMaxWidth().aspectRatio(1f), 24)
                        }
                    }
                    Text(track?.title ?: "", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track?.artist ?: "", color = Color.White.copy(.7f), maxLines = 1)
                    Slider(drag ?: pos.toFloat(), { drag = it }, valueRange = 0f..maxOf(dur, 1L).toFloat(),
                        onValueChangeFinished = { drag?.let { p.seekTo(it.toLong()) }; drag = null })
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text(fmt(pos), color = Color.White.copy(.7f), fontSize = 12.sp); Text(fmt(dur), color = Color.White.copy(.7f), fontSize = 12.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconButton({ p.shuffleModeEnabled = !p.shuffleModeEnabled }) { Icon(Icons.Filled.Shuffle, null, tint = Color.White) }
                        IconButton({ p.seekToPrevious() }) { Icon(Icons.Filled.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
                        FilledIconButton({ if (playing) p.pause() else p.play() }, Modifier.size(68.dp)) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, Modifier.size(38.dp)) }
                        IconButton({ p.seekToNext() }) { Icon(Icons.Filled.SkipNext, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
                        IconButton({ p.repeatMode = if (p.repeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF }) { Icon(Icons.Filled.Repeat, null, tint = Color.White) }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).statusBarsPadding()) {
                        val list: List<Track> = when {
                            tab == 0 -> visible.filter { !it.isVideo }
                            tab == 1 -> visible.filter { it.isVideo }
                            tab == 2 -> visible.filter { it.id in Store.favs }
                            tab == 3 && openList != null -> { val ids = Store.playlists[openList] ?: emptyList(); ids.mapNotNull { id -> visible.firstOrNull { it.id == id } } }
                            else -> emptyList()
                        }
                        val showList = tab in 0..2 || (tab == 3 && openList != null)
                        if (sel) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton({ selected.clear() }) { Icon(Icons.Filled.Close, null, tint = Color.White) }
                                Text("${selected.size} selected", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton({ selected.clear(); selected.addAll(list.map { it.id }) }) { Text("All") }
                                IconButton({ Store.addFavs(selected.toList()); selected.clear() }) { Icon(Icons.Filled.Favorite, null, tint = Color.White) }
                                IconButton({ pickFor = selected.toList() }) { Icon(Icons.Filled.LibraryMusic, null, tint = Color.White) }
                                if (tab == 3 && openList != null)
                                    IconButton({ Store.removeFromPlaylist(openList!!, selected.toList()); selected.clear() }) { Icon(Icons.Filled.Delete, null, tint = Color.White) }
                                else IconButton({ Store.hide(selected.toList()); selected.clear() }) { Icon(Icons.Filled.VisibilityOff, null, tint = Color.White) }
                            }
                        } else {
                            val title = when { tab == 0 -> "Songs"; tab == 1 -> "Videos"; tab == 2 -> "Favorites"; tab == 3 -> openList ?: "Playlists"; else -> "Settings" }
                            Column(Modifier.padding(20.dp, 14.dp, 20.dp, 6.dp)) {
                                if (tab == 0 && Store.name.isNotBlank()) Text("Hello, ${Store.name}", color = Color.White.copy(.65f), fontSize = 14.sp)
                                Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (tab == 0 || tab == 1) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            placeholder = { Text("Search") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                            leadingIcon = { Icon(Icons.Filled.Search, null) },
                            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color(0x40000000), unfocusedContainerColor = Color(0x40000000),
                                unfocusedBorderColor = Color.Transparent, focusedBorderColor = accent))
                        if (showList) {
                            val shown = list.filter { query.isBlank() || tab > 1 || it.title.contains(query, true) || it.artist.contains(query, true) }
                            if (shown.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { Text("Nothing here yet", color = Color.White.copy(.5f)) }
                            else LazyColumn(Modifier.weight(1f)) {
                                itemsIndexed(shown, key = { _, t -> t.id }) { i, t ->
                                    TrackRow(t, t.id == curId, t.id in Store.favs, sel, t.id in selected, accent,
                                        onClick = { if (sel) { if (t.id in selected) selected.remove(t.id) else selected.add(t.id) } else { play(shown, i); full = true } },
                                        onLong = { if (t.id in selected) selected.remove(t.id) else selected.add(t.id) })
                                }
                            }
                        } else if (tab == 3) {
                            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                item { Button({ newListDlg = true }, Modifier.fillMaxWidth()) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("New playlist") } }
                                items(Store.playlists.keys.sorted(), key = { it }) { k ->
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x40000000)).clickable { openList = k; query = "" }.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.LibraryMusic, null, tint = accent)
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f)) { Text(k, color = Color.White, fontWeight = FontWeight.Medium); Text("${Store.playlists[k]?.size ?: 0} tracks", color = Color.White.copy(.6f), fontSize = 12.sp) }
                                        IconButton({ Store.deletePlaylist(k) }) { Icon(Icons.Filled.Delete, null, tint = Color.White.copy(.6f)) }
                                    }
                                }
                            }
                        } else SettingsScreen(accent, { showFx = true }, { wpPicker.launch(arrayOf("image/*")) }, wpIds, Modifier.weight(1f))
                    }
                    if (track != null) Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clip(RoundedCornerShape(18.dp))
                        .background(Color(0x99000000)).clickable { full = true }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cover(cover, Modifier.size(42.dp), 10)
                        Text(track.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        IconButton({ if (playing) p.pause() else p.play() }) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White) }
                        IconButton({ p.seekToNext() }) { Icon(Icons.Filled.SkipNext, null, tint = Color.White) }
                    }
                    NavigationBar(containerColor = Color(0x99000000), contentColor = Color.White) {
                        listOf(Icons.Filled.MusicNote to "Songs", Icons.Filled.Videocam to "Videos", Icons.Filled.Favorite to "Favs",
                            Icons.Filled.LibraryMusic to "Lists", Icons.Filled.Settings to "Settings").forEachIndexed { i, (ic, n) ->
                            NavigationBarItem(tab == i, { tab = i; openList = null; selected.clear(); query = "" }, { Icon(ic, n) }, label = { Text(n, fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.Black, selectedTextColor = accent, indicatorColor = accent,
                                    unselectedIconColor = Color.White.copy(.7f), unselectedTextColor = Color.White.copy(.7f)))
                        }
                    }
                }
            }
            if (greet != 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(.92f)).clickable(enabled = false) {}, Alignment.Center) {
                if (greet == 1) {
                    var nm by remember { mutableStateOf("") }
                    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Welcome to V Music", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("What's your name?", color = Color.White.copy(.7f))
                        OutlinedTextField(nm, { nm = it }, singleLine = true, shape = RoundedCornerShape(16.dp), label = { Text("Your name") })
                        Button({ if (nm.isNotBlank()) { Store.updateName(nm); Store.greeted(); greet = 2 } }, enabled = nm.isNotBlank()) { Text("Continue") }
                    }
                } else {
                    LaunchedEffect(Unit) { delay(2000); greet = 0 }
                    Text("Hey ${Store.name}", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (showFx) FxDialog { showFx = false }
        if (newListDlg) PlaylistPicker({ n -> Store.addToPlaylist(n, emptyList()); if (n !in Store.playlists) Store.playlists[n] = emptyList(); newListDlg = false }, { newListDlg = false })
        pickFor?.let { ids -> PlaylistPicker({ n -> Store.addToPlaylist(n, ids); selected.clear(); pickFor = null }, { pickFor = null }) }
    }
}

suspend fun <T> withContextIO(block: () -> T): T = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

@Composable
fun SettingsScreen(accent: Color, openFx: () -> Unit, addWp: () -> Unit, wpIds: List<String>, modifier: Modifier) {
    val ctx = LocalContext.current
    var nm by remember { mutableStateOf(Store.name) }
    var sec by remember { mutableStateOf(ShortCfg.sec.toString()) }
    @Composable fun Sec(t: String) = Text(t, color = accent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Sec("Profile")
        OutlinedTextField(nm, { nm = it; Store.updateName(it) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Sec("Look")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Pure black", "Song cover", "Wallpapers").forEachIndexed { i, n -> FilterChip(Store.themeMode == i, { Store.setTheme(i) }, { Text(n) }) }
        }
        Text(when (Store.themeMode) { 0 -> "Solid black everywhere."; 1 -> "The whole app follows the cover of the song that is playing."; else -> "Background and colors follow your wallpapers." },
            color = Color.White.copy(.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        if (Store.themeMode == 2) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(Store.wpMode == 0, { Store.updateWpMode(0) }, { Text("Change every hour") })
                FilterChip(Store.wpMode == 1, { Store.updateWpMode(1) }, { Text("One wallpaper") })
            }
            if (wpIds.isEmpty()) Text("No wallpapers yet. Add some below.", color = Color.White.copy(.6f), modifier = Modifier.padding(top = 8.dp))
            LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(wpIds, key = { it }) { id ->
                    val b by produceState<Bitmap?>(null, id) { value = loadWallpaper(ctx, id, 240) }
                    Box(Modifier.size(70.dp, 110.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x33FFFFFF))
                        .border(if (Store.wpMode == 1 && Store.wpSingle == id) 2.dp else 0.dp, accent, RoundedCornerShape(12.dp)).clickable { Store.updateWpSingle(id) }) {
                        b?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                }
            }
            OutlinedButton(addWp, Modifier.padding(top = 10.dp)) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add wallpapers") }
        }
        Sec("Playback")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Play videos as audio only", color = Color.White); Text("Videos play without picture", color = Color.White.copy(.6f), fontSize = 12.sp) }
            Switch(Store.asMusic, { Store.updateAsMusic(it) })
        }
        OutlinedTextField(sec, { sec = it; it.toIntOrNull()?.let { v -> ShortCfg.updateSec(v) } }, label = { Text("Mark songs shorter than (seconds)") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Button(openFx, Modifier.padding(top = 12.dp)) { Icon(Icons.Filled.Tune, null); Spacer(Modifier.width(6.dp)); Text("Sound: equalizer, bass, volume") }
        Sec("Hidden")
        Text("${Store.hidden.size} hidden tracks", color = Color.White.copy(.7f))
        if (Store.hidden.isNotEmpty()) OutlinedButton({ Store.unhideAll() }, Modifier.padding(top = 6.dp)) { Text("Show all hidden") }
        Spacer(Modifier.height(30.dp))
    }
}
