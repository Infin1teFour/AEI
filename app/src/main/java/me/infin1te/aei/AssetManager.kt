package me.infin1te.aei

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.zip.ZipFile

object AssetManager {
    private val internedGson = GsonBuilder()
        .registerTypeAdapter(String::class.java, object : TypeAdapter<String>() {
            override fun write(out: com.google.gson.stream.JsonWriter?, value: String?) {
                out?.value(value)
            }
            override fun read(`in`: JsonReader?): String? {
                if (`in`?.peek() == JsonToken.NULL) {
                    `in`.nextNull()
                    return null
                }
                return `in`?.nextString()?.intern()
            }
        })
        .create()

    private const val IMPORTS_DIR = "imported_assets"
    private const val CACHE_DIR = "image_cache"
    private const val PREFS_NAME = "aei_prefs"
    private const val KEY_LAST_LOADED = "last_loaded_file"

    private fun getImportsFolder(context: Context): File = File(context.filesDir, IMPORTS_DIR).apply { if (!exists()) mkdirs() }
    private fun getCacheFolder(context: Context): File = File(context.cacheDir, CACHE_DIR).apply { if (!exists()) mkdirs() }

    suspend fun importAeiFile(context: Context, uri: Uri, onProgress: (Float) -> Unit): RecipeAssets? = withContext(Dispatchers.IO) {
        val fileName = getFileName(context, uri) ?: "imported_${System.currentTimeMillis()}.aei"
        val destinationFile = File(getImportsFolder(context), fileName)
        
        try {
            onProgress(0.02f)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destinationFile).use { output -> input.copyTo(output) }
            }
            onProgress(0.05f)
            val assets = loadAssetsFromFile(context, destinationFile, onProgress)
            if (assets != null) setLastLoadedFile(context, destinationFile.name)
            assets
        } catch (e: Exception) {
            Log.e("AEI_ERROR", "Import failed", e)
            null
        }
    }

    fun listImportedFiles(context: Context): List<File> = 
        getImportsFolder(context).listFiles { f -> f.extension == "aei" || f.extension == "zip" }?.toList() ?: emptyList()

    suspend fun loadAssetsFromFile(context: Context, file: File, onProgress: (Float) -> Unit): RecipeAssets? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        
        val translations = mutableMapOf<String, String>()
        val imagePaths = mutableMapOf<String, File>()
        val originalImagePaths = mutableMapOf<String, String>()
        val recipesByOutput = mutableMapOf<String, MutableList<RecipeDump>>()
        val uniqueItemsSet = mutableSetOf<String>()
        val cacheFolder = File(getCacheFolder(context), file.nameWithoutExtension).apply { if (!exists()) mkdirs() }

        try {
            ZipFile(file).use { zip ->
                val allEntries = zip.entries().toList()
                val totalEntries = allEntries.size.toFloat()

                // 1. Load Translations & Recipes
                allEntries.forEach { entry ->
                    val name = entry.name.replace("\\", "/")
                    when {
                        name.endsWith("translations.json", ignoreCase = true) -> {
                            try {
                                JsonReader(InputStreamReader(zip.getInputStream(entry))).use { reader ->
                                    reader.beginObject()
                                    while (reader.hasNext()) {
                                        // Lowercase keys for consistent lookup from recipes
                                        val key = reader.nextName().lowercase().intern()
                                        val value = reader.nextString().intern()
                                        translations[key] = value
                                    }
                                    reader.endObject()
                                }
                            } catch (e: Exception) {
                                Log.e("AEI_LOAD", "Failed to load translations from ${entry.name}", e)
                            }
                        }
                        name.endsWith("recipes.json", ignoreCase = true) -> {
                            try {
                                JsonReader(InputStreamReader(zip.getInputStream(entry))).use { reader ->
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        val recipe: RecipeDump = internedGson.fromJson(reader, RecipeDump::class.java)
                                        val outputs = recipe.slots.filter { it.role == "OUTPUT" }
                                        val firstOutput = outputs.firstOrNull()?.ingredients?.firstOrNull()?.getResolvedId() ?: "Unknown"
                                        recipesByOutput.getOrPut(firstOutput) { mutableListOf() }.add(recipe)
                                        for (slot in outputs) {
                                            for (ing in slot.ingredients) {
                                                ing.getResolvedId()?.let { uniqueItemsSet.add(it) }
                                            }
                                        }
                                    }
                                    reader.endArray()
                                }
                            } catch (e: Exception) {
                                Log.e("AEI_LOAD", "Failed to load recipes from ${entry.name}", e)
                            }
                        }
                    }
                }
                onProgress(0.50f)

                // 2. Extract and map images
                allEntries.forEachIndexed { index, entry ->
                    if (index % 500 == 0) onProgress(0.50f + (index / totalEntries) * 0.45f)
                    
                    val entryName = entry.name.replace("\\", "/")
                    if (entryName.contains("inventory_images/", ignoreCase = true) && entryName.endsWith(".png", ignoreCase = true)) {
                        val segments = entryName.split(Regex("inventory_images/", RegexOption.IGNORE_CASE), 2)
                        val pathAfterImages = segments.last()
                        
                        val relPath = pathAfterImages.substringBeforeLast(".png").lowercase()
                        val fileName = relPath.substringAfterLast("/")
                        val typePath = if (relPath.contains("/")) relPath.substringBeforeLast("/") + "/" else ""

                        val safeName = entryName.replace("/", "_").replace(":", "_").replace(" ", "_")
                        val outFile = File(cacheFolder, safeName)
                        if (!outFile.exists()) {
                            zip.getInputStream(entry).use { input -> FileOutputStream(outFile).use { output -> input.copyTo(output) } }
                        }
                        
                        fun mapKey(key: String) {
                            imagePaths[key] = outFile
                            originalImagePaths[key] = entryName
                        }

                        var underscoreIndex = fileName.indexOf('_')
                        while (underscoreIndex != -1) {
                            val namespace = fileName.substring(0, underscoreIndex)
                            val id = fileName.substring(underscoreIndex + 1)
                            mapKey("$typePath$namespace:$id")
                            underscoreIndex = fileName.indexOf('_', underscoreIndex + 1)
                        }
                        
                        mapKey("${typePath}minecraft:$fileName")
                        mapKey("$typePath$fileName")
                        
                        if (typePath.isEmpty()) {
                            mapKey(fileName)
                        }
                    }
                }
            }
            
            onProgress(0.95f)
            val uniqueItems = uniqueItemsSet.toList().sortedBy { translations[it] ?: it }
            RecipeAssets(translations, imagePaths, originalImagePaths, uniqueItems, recipesByOutput)
        } catch (e: Exception) {
            Log.e("AEI_ERROR", "Load failed", e)
            null
        }
    }

    fun loadBitmap(file: File?): Bitmap? = file?.let { 
        if (!it.exists()) return@let null
        try {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            BitmapFactory.decodeFile(it.absolutePath, options)
        } catch (e: Exception) {
            Log.e("AEI_ERROR", "Bitmap decode failed: ${it.absolutePath}", e)
            null
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) name = it.getString(index)
                }
            }
        }
        return name ?: uri.path?.substringAfterLast("/")
    }

    fun deleteImportedFile(context: Context, file: File) {
        if (getLastLoadedFile(context) == file.name) setLastLoadedFile(context, null)
        File(getCacheFolder(context), file.nameWithoutExtension).deleteRecursively()
        file.delete()
    }

    fun getLastLoadedFile(context: Context): String? = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_LOADED, null)
    fun setLastLoadedFile(context: Context, fileName: String?) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_LAST_LOADED, fileName).apply()
}
