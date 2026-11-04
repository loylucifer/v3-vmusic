package com.vmusic.app

import android.media.audiofx.BassBoost
import android.media.audiofx.Virtualizer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.util.UnstableApi

object FxHolder {
    var bb: BassBoost? = null; var vz: Virtualizer? = null
    var bbStrength = 0; var vzStrength = 0
    var eqOn = true; var preamp = 0f; var boostPct = 100
    val levels = FloatArray(10)
    val builtin = linkedMapOf(
        "Flat" to floatArrayOf(0f,0f,0f,0f,0f,0f,0f,0f,0f,0f),
        "Bass" to floatArrayOf(6f,5f,4f,2f,0f,0f,0f,0f,0f,0f),
        "Rock" to floatArrayOf(5f,4f,3f,1f,-1f,-1f,1f,3f,4f,5f),
        "Pop" to floatArrayOf(-1f,1f,3f,4f,3f,0f,-1f,-1f,1f,2f),
        "Vocal" to floatArrayOf(-2f,-3f,-3f,1f,3f,3f,3f,1f,0f,-1f),
        "Classical" to floatArrayOf(0f,0f,0f,0f,0f,0f,-2f,-2f,-2f,-4f))
    val custom = linkedMapOf<String, FloatArray>()
    private var prefs: android.content.SharedPreferences? = null

    fun load(ctx: android.content.Context) {
        prefs = ctx.getSharedPreferences("vm", 0)
        val sp = prefs!!
        eqOn = sp.getBoolean("fx_eq", true); preamp = sp.getFloat("fx_pre", 0f); boostPct = sp.getInt("fx_boost", 100)
        bbStrength = sp.getInt("fx_bb", 0); vzStrength = sp.getInt("fx_vz", 0)
        try { sp.getString("fx_levels", null)?.split(",")?.map { it.toFloat() }?.forEachIndexed { i, v -> if (i < 10) levels[i] = v } } catch (_: Exception) {}
        PlayerHolder.bass.mode = sp.getInt("bass_mode", 0); PlayerHolder.bass.amount = sp.getFloat("bass_amt", 0f); PlayerHolder.bass.cutoff = sp.getFloat("bass_cut", 150f)
        prefs!!.getString("presets", "")!!.lines().forEach { l ->
            val p = l.split("|"); if (p.size == 2) try { custom[p[0]] = p[1].split(",").map { it.toFloat() }.toFloatArray() } catch (_: Exception) {}
        }
    }
    fun savePreset(name: String) {
        val n = name.replace("|", "").replace("\n", " ").trim(); if (n.isEmpty()) return
        custom[n] = levels.copyOf(); persist()
    }
    fun deletePreset(n: String) { custom.remove(n); persist() }
    private fun persist() { prefs?.edit()?.putString("presets", custom.entries.joinToString("\n") { it.key + "|" + it.value.joinToString(",") })?.apply() }

    fun attach(session: Int) {
        release()
        if (session == 0) return
        try { bb = BassBoost(0, session) } catch (_: Exception) {}
        try { vz = Virtualizer(0, session) } catch (_: Exception) {}
        apply()
    }

    fun save() {
        val b = PlayerHolder.bass
        prefs?.edit()?.putBoolean("fx_eq", eqOn)?.putFloat("fx_pre", preamp)?.putInt("fx_boost", boostPct)
            ?.putInt("fx_bb", bbStrength)?.putInt("fx_vz", vzStrength)?.putString("fx_levels", levels.joinToString(","))
            ?.putInt("bass_mode", b.mode)?.putFloat("bass_amt", b.amount)?.putFloat("bass_cut", b.cutoff)?.apply()
    }

    fun apply() {
        save()
        val e = PlayerHolder.eq
        e.enabled = eqOn; e.gains = levels.copyOf(); e.preampDb = preamp; e.volumePct = boostPct; e.changed()
        try { bb?.let { it.setStrength(bbStrength.toShort()); it.enabled = bbStrength > 0 } } catch (_: Exception) {}
        try { vz?.let { it.setStrength(vzStrength.toShort()); it.enabled = vzStrength > 0 } } catch (_: Exception) {}
    }

    fun usePreset(v: FloatArray) { v.copyInto(levels); apply() }

    fun release() {
        try { bb?.release(); vz?.release() } catch (_: Exception) {}
        bb = null; vz = null
    }
}

@Composable
private fun Head(t: String) = Text(t, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF9D8CFF), modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))

@UnstableApi
@Composable
fun FxDialog(onDismiss: () -> Unit) {
    val bass = PlayerHolder.bass
    var mode by remember { mutableIntStateOf(bass.mode) }
    var amount by remember { mutableFloatStateOf(bass.amount) }
    var cutoff by remember { mutableFloatStateOf(bass.cutoff) }
    var boost by remember { mutableFloatStateOf(FxHolder.boostPct.toFloat()) }
    var pre by remember { mutableFloatStateOf(FxHolder.preamp) }
    var newName by remember { mutableStateOf("") }
    val custom = remember { mutableStateListOf<String>().apply { addAll(FxHolder.custom.keys) } }
    var eqOn by remember { mutableStateOf(FxHolder.eqOn) }
    var bbS by remember { mutableFloatStateOf(FxHolder.bbStrength.toFloat()) }
    var vzS by remember { mutableFloatStateOf(FxHolder.vzStrength.toFloat()) }
    val lv = remember { mutableStateListOf<Float>().apply { addAll(FxHolder.levels.toList()) } }

    DisposableEffect(Unit) { onDispose { FxHolder.save() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF10101C)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 32.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sound", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onDismiss) { Text("Done") }
                }
                Head("Volume: ${boost.toInt()}%")
                Slider(boost, { boost = it; FxHolder.boostPct = it.toInt(); FxHolder.apply() }, valueRange = 0f..300f)
                if (boost > 200) Text("\u26A0 Above 200% the sound can distort and may harm your speaker or hearing.", color = Color(0xFFFFB74D), fontSize = 12.sp)

                Head("Equalizer")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", Modifier.weight(1f))
                    Switch(eqOn, { eqOn = it; FxHolder.eqOn = it; FxHolder.apply() })
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FxHolder.builtin.forEach { (n, v) ->
                        FilterChip(false, { FxHolder.usePreset(v); for (b in 0 until 10) lv[b] = v[b] }, { Text(n, fontSize = 12.sp) })
                    }
                }
                if (custom.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    custom.forEach { n ->
                        FilterChip(false, { FxHolder.custom[n]?.let { v -> FxHolder.usePreset(v); for (b in 0 until 10) lv[b] = v[b] } }, { Text(n, fontSize = 12.sp) })
                        TextButton({ FxHolder.deletePreset(n); custom.remove(n) }) { Text("\u2715") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newName, { newName = it }, label = { Text("Preset name") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button({ if (newName.isNotBlank()) { FxHolder.savePreset(newName); custom.remove(newName.trim()); custom.add(newName.trim()); newName = "" } }) { Text("Save") }
                }
                Text("Preamp   ${"%.1f".format(pre)} dB", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                Slider(pre, { pre = it; FxHolder.preamp = it; FxHolder.apply() }, valueRange = -12f..12f)
                lv.indices.forEach { i ->
                    val f = EQ_FREQS[i]
                    Text("${if (f >= 1000) "${f / 1000}kHz" else "${f}Hz"}   ${"%.1f".format(lv[i])} dB", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    Slider(lv[i], { lv[i] = it; FxHolder.levels[i] = it; FxHolder.apply() }, valueRange = -12f..12f)
                }

                Head("Bass boost: ${(bbS / 10).toInt()}%")
                Slider(bbS, { bbS = it; FxHolder.bbStrength = it.toInt(); FxHolder.apply() }, valueRange = 0f..1000f)
                Head("Virtualizer (surround): ${(vzS / 10).toInt()}%")
                Slider(vzS, { vzS = it; FxHolder.vzStrength = it.toInt(); FxHolder.apply() }, valueRange = 0f..1000f)

                Head("Bass remover / isolator")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Off", "Block bass", "Only bass").forEachIndexed { i, n ->
                        FilterChip(mode == i, { mode = i; bass.mode = i }, { Text(n, fontSize = 12.sp) })
                    }
                }
                Text("Amount: ${(amount * 100).toInt()}%", Modifier.padding(top = 8.dp))
                Slider(amount, { amount = it; bass.amount = it })
                Text("Bass cutoff: ${cutoff.toInt()} Hz")
                Slider(cutoff, { cutoff = it; bass.cutoff = it }, valueRange = 60f..250f)
            }
        }
    }
}
