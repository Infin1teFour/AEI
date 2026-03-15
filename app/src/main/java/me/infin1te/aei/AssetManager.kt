package me.infin1te.aei

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

object AssetManager {
    private val gson = Gson()

    suspend fun importAeiFile(context: Context, uri: Uri): RecipeAssets? = withContext(Dispatchers.IO) {
        var recipes: List<RecipeDump> = emptyList()
        var translations: Map<String, String> = emptyMap()
        val images = mutableMapOf<String, android.graphics.Bitmap>()

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipInputStream ->
                    var entry = zipInputStream.nextEntry
                    while (entry != null) {
                        when {
                            entry.name.endsWith("recipes.json") -> {
                                val content = zipInputStream.bufferedReader().readText()
                                val type = object : TypeToken<List<RecipeDump>>() {}.type
                                recipes = gson.fromJson(content, type)
                            }
                            entry.name.endsWith("translations.json") -> {
                                val content = zipInputStream.bufferedReader().readText()
                                val type = object : TypeToken<Map<String, String>>() {}.type
                                translations = gson.fromJson(content, type)
                            }
                            entry.name.contains("inventory_images/") && entry.name.endsWith(".png") -> {
                                val fileName = entry.name.substringAfterLast("/").substringBeforeLast(".png")
                                
                                // Decode the bitmap from the current zip entry
                                val bitmap = BitmapFactory.decodeStream(zipInputStream)
                                if (bitmap != null) {
                                    // Map minecraft_acacia_planks -> minecraft:acacia_planks
                                    val id = if (fileName.contains("_")) {
                                        fileName.replaceFirst("_", ":")
                                    } else {
                                        fileName
                                    }
                                    
                                    images[id] = bitmap
                                    // Fallback to raw filename
                                    images[fileName] = bitmap
                                }
                            }
                        }
                        // Do NOT call zipInputStream.closeEntry() here if decodeStream was used 
                        // as it might have already reached the end of the entry stream.
                        // However, nextEntry() handles it.
                        entry = zipInputStream.nextEntry
                    }
                }
            }
            if (recipes.isNotEmpty()) {
                RecipeAssets(recipes, translations, images)
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
