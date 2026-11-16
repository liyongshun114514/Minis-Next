package com.openminis.app.groups

import android.content.Context
import java.io.FileNotFoundException

/**
 * [T-model-group-avatar] Loads the bundled model-icon catalog once per
 * process.
 *
 * `assets/model-icons/catalog.json` is shipped by the assets agent and may be
 * absent in a build that has not pulled it in yet; a missing or unreadable
 * catalog yields [ModelIconCatalog.EMPTY], which makes every group render the
 * generic fallback instead of crashing the list.
 */
object ModelGroupIconAssets {

    const val ASSET_DIR = "model-icons"
    const val CATALOG_ASSET = "$ASSET_DIR/catalog.json"

    @Volatile
    private var cached: ModelIconCatalog? = null

    fun catalog(context: Context): ModelIconCatalog {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: load(context.applicationContext).also { cached = it }
        }
    }

    private fun load(context: Context): ModelIconCatalog {
        val text = try {
            context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            return ModelIconCatalog.EMPTY
        } catch (e: Exception) {
            return ModelIconCatalog.EMPTY
        }
        return ModelIconCatalog.parse(text)
    }
}