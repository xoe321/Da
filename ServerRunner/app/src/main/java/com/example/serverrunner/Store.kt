package com.example.serverrunner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Srv(val id: String, var name: String, var port: Int, var token: String)

// File-based store: the UI (main process), the manager (:server) and workers (:pyN) are separate
// processes, so SharedPreferences is not reliable between them.
object Store {
    fun dir(c: Context, id: String) = File(c.filesDir, "servers/$id").apply { mkdirs() }
    fun logFile(c: Context, id: String) = File(dir(c, id), "log.txt")

    fun dataDir(c: Context, id: String) = File(dir(c, id), "data").apply { mkdirs() }
    // .sqlite3 files in data/ and in the server's own folder (relative paths resolve there).
    fun dbFiles(c: Context, id: String): List<File> =
        listOf(dataDir(c, id), dir(c, id)).flatMap { d ->
            d.listFiles { f -> f.isFile && f.name.lowercase().let {
                it.endsWith(".sqlite3") || it.endsWith(".sqlite") || it.endsWith(".db") } }?.toList() ?: emptyList()
        }.sortedBy { it.name }

    fun load(c: Context): MutableList<Srv> {
        val f = File(c.filesDir, "servers.json")
        if (!f.exists()) return mutableListOf()
        return try {
            val a = JSONArray(f.readText())
            MutableList(a.length()) {
                val o = a.getJSONObject(it)
                Srv(o.getString("id"), o.optString("name"), o.optInt("port", 8080), o.optString("token"))
            }
        } catch (e: Exception) { mutableListOf() }
    }

    fun save(c: Context, l: List<Srv>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("port", it.port).put("token", it.token)) }
        val tmp = File(c.filesDir, "servers.json.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(File(c.filesDir, "servers.json"))
    }

    // Servers the user wants running (removed only by Stop). Used for restart and boot autostart.
    fun running(c: Context): Set<String> = try {
        File(c.filesDir, "running.txt").readLines().filter { it.isNotBlank() }.toSet()
    } catch (e: Exception) { emptySet() }

    fun setRunning(c: Context, s: Set<String>) {
        File(c.filesDir, "running.txt").writeText(s.joinToString("\n"))
    }
}
