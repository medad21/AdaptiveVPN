package com.adaptivevpn.app.profile

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Stores imported profiles in app-private storage; profile names are display-only metadata. */
object ProfileStore {
    private const val DIR_NAME = "profiles"
    private const val PREFS = "profiles_store"
    private const val KEY_ACTIVE = "active_id"
    private const val PREFIX_NAME = "name_"
    private const val PREFIX_HASH = "hash_"

    data class StoredProfile(
        val id: String,
        val name: String,
        val file: File,
        val active: Boolean
    )

    @Synchronized
    fun save(context: Context, rawJson: String, displayName: String = "sing-box profile.json"): StoredProfile {
        require(rawJson.isNotBlank() && rawJson.toByteArray(Charsets.UTF_8).size <= SingBoxProfileValidator.MAX_PROFILE_BYTES) {
            "پروفایل خالی یا بزرگ‌تر از حد مجاز است."
        }
        // Keep validation inside the storage boundary too: future callers must not be able
        // to bypass the UI's import-time checks and persist an incompatible profile.
        val validation = SingBoxProfileValidator.validate(rawJson)
        require(validation.valid) { validation.summary }
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256").digest(rawJson.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Re-importing identical content updates the existing profile instead of creating duplicates.
        val existing = prefs.all.keys.firstOrNull { it.startsWith(PREFIX_HASH) && prefs.getString(it, null) == digest }
        val id = existing?.removePrefix(PREFIX_HASH) ?: UUID.randomUUID().toString()
        val target = File(dir, "$id.json")
        val temp = File(dir, "$id.tmp")
        temp.writeText(rawJson, Charsets.UTF_8)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            temp.delete()
            throw IllegalStateException("ذخیره امن پروفایل ناموفق بود.", e)
        }
        val safeName = displayName.substringAfterLast('/').substringAfterLast('\\').trim().take(80)
            .ifBlank { "sing-box profile.json" }
        prefs.edit()
            .putString(PREFIX_NAME + id, safeName)
            .putString(PREFIX_HASH + id, digest)
            .putString(KEY_ACTIVE, id)
            .apply()
        return StoredProfile(id, safeName, target, true)
    }

    @Synchronized
    fun list(context: Context): List<StoredProfile> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val activeId = prefs.getString(KEY_ACTIVE, null)
        val dir = File(context.filesDir, DIR_NAME)
        return prefs.all.keys.filter { it.startsWith(PREFIX_NAME) }.mapNotNull { key ->
            val id = key.removePrefix(PREFIX_NAME)
            val file = File(dir, "$id.json")
            if (!file.isFile || file.length() !in 1L..SingBoxProfileValidator.MAX_PROFILE_BYTES.toLong()) {
                prefs.edit().remove(PREFIX_NAME + id).remove(PREFIX_HASH + id).apply()
                return@mapNotNull null
            }
            StoredProfile(id, prefs.getString(key, "profile.json") ?: "profile.json", file, id == activeId)
        }.sortedWith(compareByDescending<StoredProfile> { it.active }.thenBy { it.name.lowercase() })
    }

    @Synchronized
    fun select(context: Context, id: String): Boolean {
        val profile = list(context).firstOrNull { it.id == id } ?: return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ACTIVE, profile.id).apply()
        return true
    }

    @Synchronized
    fun delete(context: Context, id: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val profile = list(context).firstOrNull { it.id == id } ?: return false
        profile.file.delete()
        val editor = prefs.edit().remove(PREFIX_NAME + id).remove(PREFIX_HASH + id)
        if (prefs.getString(KEY_ACTIVE, null) == id) {
            val replacement = list(context).firstOrNull { it.id != id }
            if (replacement != null) editor.putString(KEY_ACTIVE, replacement.id) else editor.remove(KEY_ACTIVE)
        }
        editor.apply()
        return true
    }

    fun active(context: Context): File? = list(context).firstOrNull { it.active }?.file
    fun activeProfile(context: Context): StoredProfile? = list(context).firstOrNull { it.active }
}
