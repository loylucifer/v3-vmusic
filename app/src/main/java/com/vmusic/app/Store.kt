package com.vmusic.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject

/** Everything the user sets lives here and is saved immediately. */
object Store {
    private var sp: SharedPreferences? = null
    private var loaded = false
    var name by mutableStateOf("")
    var lastGreet by mutableLongStateOf(0L)
    var themeMode by mutableIntStateOf(0)      // 0 = pure black, 1 = current song cover, 2 = wallpapers
    var wpMode by mutableIntStateOf(0)         // 0 = auto every hour, 1 = one wallpaper
    var wpSingle by mutableStateOf("")
    var wpVersion by mutableIntStateOf(0)
    var asMusic by mutableStateOf(false)
    val favs = mutableStateListOf<String>()
    val hidden = mutableStateListOf<String>()
    val playlists = mutableStateMapOf<String, List<String>>()

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences("vm", 0); sp = p
        name = p.getString("name", "") ?: ""
        lastGreet = p.getLong("last_greet", 0L)
        themeMode = p.getInt("theme_mode", 0); wpMode = p.getInt("wp_mode", 0)
        wpSingle = p.getString("wp_single", "") ?: ""; asMusic = p.getBoolean("as_music", false)
        favs.addAll(p.getStringSet("favs", emptySet()) ?: emptySet())
        hidden.addAll(p.getStringSet("hidden", emptySet()) ?: emptySet())
        try {
            val o = JSONObject(p.getString("playlists", "{}") ?: "{}")
            o.keys().forEach { k -> val a = o.getJSONArray(k); playlists[k] = (0 until a.length()).map { a.getString(it) } }
        } catch (_: Exception) {}
    }

    private fun ed() = sp?.edit()
    fun updateName(n: String) { name = n.trim(); ed()?.putString("name", name)?.apply() }
    fun greeted() { lastGreet = System.currentTimeMillis(); ed()?.putLong("last_greet", lastGreet)?.apply() }
    fun setTheme(m: Int) { themeMode = m; ed()?.putInt("theme_mode", m)?.apply() }
    fun updateWpMode(m: Int) { wpMode = m; ed()?.putInt("wp_mode", m)?.apply() }
    fun updateWpSingle(id: String) { wpSingle = id; wpMode = 1; ed()?.putString("wp_single", id)?.putInt("wp_mode", 1)?.apply() }
    fun updateAsMusic(b: Boolean) { asMusic = b; ed()?.putBoolean("as_music", b)?.apply() }

    fun toggleFav(id: String) { if (id in favs) favs.remove(id) else favs.add(id); ed()?.putStringSet("favs", favs.toSet())?.apply() }
    fun addFavs(ids: List<String>) { ids.forEach { if (it !in favs) favs.add(it) }; ed()?.putStringSet("favs", favs.toSet())?.apply() }
    fun hide(ids: List<String>) { ids.forEach { if (it !in hidden) hidden.add(it) }; ed()?.putStringSet("hidden", hidden.toSet())?.apply() }
    fun unhideAll() { hidden.clear(); ed()?.putStringSet("hidden", emptySet())?.apply() }

    private fun savePl() {
        val o = JSONObject(); playlists.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        ed()?.putString("playlists", o.toString())?.apply()
    }
    fun addToPlaylist(name: String, ids: List<String>) {
        val n = name.trim(); if (n.isEmpty()) return
        val cur = playlists[n] ?: emptyList()
        playlists[n] = cur + ids.filter { it !in cur }; savePl()
    }
    fun removeFromPlaylist(name: String, ids: List<String>) { playlists[name] = (playlists[name] ?: emptyList()).filter { it !in ids }; savePl() }
    fun deletePlaylist(name: String) { playlists.remove(name); savePl() }
}
