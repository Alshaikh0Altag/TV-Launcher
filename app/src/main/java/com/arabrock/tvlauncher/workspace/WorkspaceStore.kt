package com.arabrock.tvlauncher.workspace

import android.content.Context
import com.arabrock.tvlauncher.window.LayoutType
import com.arabrock.tvlauncher.window.WorkspaceLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Window assignments: index = window number, value = package name or null. */
data class Workspace(
    val layout: WorkspaceLayout = WorkspaceLayout(),
    val slots: List<String?> = listOf(null, null, null, null),
) {
    fun slot(i: Int): String? = slots.getOrNull(i)
    fun withSlot(i: Int, pkg: String?): Workspace {
        val list = slots.toMutableList()
        while (list.size < 4) list.add(null)
        list[i] = pkg
        return copy(slots = list)
    }
    val activeSlots: List<Int> get() = (0 until layout.type.slots).filter { slot(it) != null }
}

data class Profile(val id: String, val name: String, val workspace: Workspace)

/** Current workspace + saved workspace profiles (JSON in SharedPreferences). */
class WorkspaceStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("workspace", Context.MODE_PRIVATE)

    private val _current = MutableStateFlow(loadCurrent())
    val current: StateFlow<Workspace> = _current
    private val _profiles = MutableStateFlow(loadProfiles())
    val profiles: StateFlow<List<Profile>> = _profiles

    fun setCurrent(ws: Workspace) {
        _current.value = ws
        prefs.edit().putString("current", wsToJson(ws).toString()).apply()
    }

    fun saveProfile(name: String, ws: Workspace) {
        val list = _profiles.value + Profile(UUID.randomUUID().toString(), name, ws)
        storeProfiles(list)
    }

    fun overwriteProfile(id: String, ws: Workspace) =
        storeProfiles(_profiles.value.map { if (it.id == id) it.copy(workspace = ws) else it })

    fun renameProfile(id: String, name: String) =
        storeProfiles(_profiles.value.map { if (it.id == id) it.copy(name = name) else it })

    fun deleteProfile(id: String) = storeProfiles(_profiles.value.filter { it.id != id })

    private fun storeProfiles(list: List<Profile>) {
        _profiles.value = list
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("ws", wsToJson(p.workspace)))
        }
        prefs.edit().putString("profiles", arr.toString()).apply()
    }

    private fun loadCurrent(): Workspace =
        prefs.getString("current", null)?.let { runCatching { wsFromJson(JSONObject(it)) }.getOrNull() } ?: Workspace()

    private fun loadProfiles(): List<Profile> {
        val raw = prefs.getString("profiles", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Profile(o.getString("id"), o.getString("name"), wsFromJson(o.getJSONObject("ws")))
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        @Volatile private var instance: WorkspaceStore? = null
        fun get(context: Context): WorkspaceStore =
            instance ?: synchronized(this) {
                instance ?: WorkspaceStore(context.applicationContext).also { instance = it }
            }

        fun wsToJson(ws: Workspace): JSONObject = JSONObject().apply {
            put("type", ws.layout.type.name)
            put("splitX", ws.layout.splitX.toDouble())
            put("splitY", ws.layout.splitY.toDouble())
            val arr = JSONArray()
            ws.slots.forEach { arr.put(it ?: "") }
            put("slots", arr)
        }

        fun wsFromJson(o: JSONObject): Workspace {
            val type = runCatching { LayoutType.valueOf(o.getString("type")) }.getOrDefault(LayoutType.QUAD)
            val arr = o.optJSONArray("slots") ?: JSONArray()
            val slots = (0 until 4).map { i -> arr.optString(i, "").takeIf { it.isNotEmpty() } }
            return Workspace(
                WorkspaceLayout(type, o.optDouble("splitX", 0.5).toFloat(), o.optDouble("splitY", 0.5).toFloat()),
                slots,
            )
        }
    }
}
