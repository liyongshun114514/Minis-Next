package com.openminis.app.offload

import android.content.SharedPreferences
import java.lang.reflect.Proxy

/**
 * In-memory [SharedPreferences] for JVM tests: no Android device, no su, no
 * disk. The same Proxy shape the standalone permission-store check uses.
 *
 * [apply]/[commit] both write through immediately, so a test can assert the
 * persisted value right after the call — the point of the "detected → 允许 is
 * stored once and survives a restart" behaviour.
 */
class FakePrefs(initial: Map<String, Any?> = emptyMap()) {
    val values: MutableMap<String, Any?> = LinkedHashMap(initial)

    /** How many times a Root level was written through [SharedPreferences.Editor.putString]. */
    var levelWrites: Int = 0
        private set

    /** How many times any Editor was created (a cheap "probe re-ran" signal). */
    var editCount: Int = 0
        private set

    private val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "putString", "putBoolean", "putInt", "putLong", "putFloat", "putStringSet" -> {
                val key = args!![0] as String
                if (method.name == "putString" && key.endsWith("level_root_cli")) levelWrites++
                values[key] = args[1]
                proxy
            }
            "remove" -> { values.remove(args!![0] as String); proxy }
            "clear" -> { values.clear(); proxy }
            "commit" -> true
            "apply" -> null
            else -> null
        }
    } as SharedPreferences.Editor

    val prefs: SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
        when (method.name) {
            "edit" -> { editCount++; editor }
            "getString" -> values[args!![0] as String] as String? ?: args[1] as String?
            "getBoolean" -> values[args!![0] as String] as Boolean? ?: args[1] as Boolean
            "getInt" -> values[args!![0] as String] as Int? ?: args[1] as Int
            "getLong" -> values[args!![0] as String] as Long? ?: args[1] as Long
            "getFloat" -> values[args!![0] as String] as Float? ?: args[1] as Float
            "contains" -> values.containsKey(args!![0] as String)
            "getAll" -> values.toMap()
            else -> null
        }
    } as SharedPreferences

    /** A fresh process would see exactly these persisted values. */
    fun restart(): FakePrefs = FakePrefs(values)
}

/**
 * Install [prefs] into the singleton [OffloadPermissionManager] without a
 * Context, and reset the process-lifetime state a "restart" would clear.
 */
private var installedStore: FakePrefs? = null

/**
 * A fresh process: same persisted values, empty process-lifetime state.
 * Reads the values from the store the manager is CURRENTLY using, so a write
 * made after the previous restart is not lost.
 */
fun restartPermissionPrefs(): FakePrefs =
    FakePrefs(installedStore?.values ?: emptyMap()).also { installPermissionPrefs(it) }

fun installPermissionPrefs(prefs: FakePrefs) {
    installedStore = prefs
    val field = OffloadPermissionManager::class.java.getDeclaredField("prefs")
    field.isAccessible = true
    field.set(OffloadPermissionManager, prefs.prefs)
    // The detection cache is process memory for real too; a restart forgets it.
    val cache = OffloadPermissionManager::class.java.getDeclaredField("rootDetectedCache")
    cache.isAccessible = true
    cache.set(OffloadPermissionManager, null)
    // `init(context)` is exactly "set prefs + migrateRootLevel()"; mirror the
    // migration so upgrade behaviour (an old stored level counts as the user's
    // choice) is exercised instead of skipped.
    runCatching {
        val migrate = OffloadPermissionManager::class.java
            .getDeclaredMethod("migrateRootLevel")
            .apply { isAccessible = true }
        migrate.invoke(OffloadPermissionManager)
    }
}
