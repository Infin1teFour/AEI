package me.infin1te.aei

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.util.LruCache
import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import kotlin.math.max
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
        .registerTypeAdapter(File::class.java, object : TypeAdapter<File>() {
            override fun write(out: com.google.gson.stream.JsonWriter?, value: File?) {
                out?.value(value?.absolutePath)
            }
            override fun read(`in`: JsonReader?): File? {
                val path = `in`?.nextString()
                return if (path != null) File(path) else null
            }
        })
        .create()

    private const val UNPACKED_DIR = "unpacked_assets"
    private const val METADATA_FILE_NAME = "metadata_v5.json"
    private const val SOURCE_ARCHIVE_NAME = "source.aei"
    private const val PREFS_NAME = "aei_prefs"
    private const val KEY_LAST_LOADED = "last_loaded_folder"

    private val bitmapCache = LruCache<String, Bitmap>(200)
    private var lastLoadedAssets: Pair<String, RecipeAssets>? = null
    private val cacheWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun getUnpackedFolder(context: Context): File = File(context.filesDir, UNPACKED_DIR).apply { if (!exists()) mkdirs() }

    private fun shouldExcludeFluidStack(id: String): Boolean {
        val lower = id.lowercase()
        return lower.startsWith("fluid_stack/") && lower.contains("flowing")
    }

    suspend fun importAeiFile(context: Context, uri: Uri, onProgress: (Float) -> Unit): RecipeAssets? = withContext(Dispatchers.IO) {
        val fileName = getFileName(context, uri) ?: "imported_${System.currentTimeMillis()}.aei"
        val folderName = fileName.substringBeforeLast(".")
        val tempFile = File(context.cacheDir, "temp_import.aei")
        val unpackedFolder = File(getUnpackedFolder(context), folderName)
        val metadataFile = File(unpackedFolder, METADATA_FILE_NAME)
        val sourceArchiveFile = File(unpackedFolder, SOURCE_ARCHIVE_NAME)
        
        try {
            // Fast path: already unpacked and indexed, reuse cached folder only.
            if (metadataFile.exists()) {
                onProgress(0.1f)
                val cached = loadFromUnpacked(context, folderName, onProgress)
                if (cached != null) {
                    setLastLoadedFolder(context, folderName)
                    return@withContext cached
                }
            }

            if (unpackedFolder.exists()) {
                unpackedFolder.deleteRecursively()
            }
            unpackedFolder.mkdirs()

            onProgress(0.01f)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }

            // Keep an internal archive copy so we can rebuild metadata if cache is missing/corrupt.
            tempFile.copyTo(sourceArchiveFile, overwrite = true)
            
            val assets = unpackAndLoad(context, sourceArchiveFile, folderName, onProgress)
            tempFile.delete()
            
            if (assets != null) setLastLoadedFolder(context, folderName)
            assets
        } catch (e: Exception) {
            Log.e("AEI_ERROR", "Import failed", e)
            tempFile.delete()
            null
        }
    }

    fun listImportedFolders(context: Context): List<File> = 
        getUnpackedFolder(context).listFiles { f -> f.isDirectory }?.toList() ?: emptyList()

    suspend fun loadFromUnpacked(context: Context, folderName: String, onProgress: (Float) -> Unit): RecipeAssets? = withContext(Dispatchers.IO) {
        if (lastLoadedAssets?.first == folderName) {
            onProgress(1.0f)
            return@withContext lastLoadedAssets?.second
        }

        val unpackedFolder = File(getUnpackedFolder(context), folderName)
        val metadataFile = File(unpackedFolder, METADATA_FILE_NAME)
        val metadataTmpFile = File(unpackedFolder, "$METADATA_FILE_NAME.tmp")
        val sourceArchiveFile = File(unpackedFolder, SOURCE_ARCHIVE_NAME)

        fun tryReadMetadata(file: File): RecipeAssets? {
            if (!file.exists()) return null
            return try {
                file.inputStream().use { input ->
                    JsonReader(InputStreamReader(input)).use { reader ->
                        internedGson.fromJson(reader, RecipeAssets::class.java)
                    }
                }
            } catch (e: Exception) {
                Log.e("AEI_CACHE", "Failed to load metadata cache from ${file.name}", e)
                null
            }
        }

        onProgress(0.1f)
        val cached = tryReadMetadata(metadataFile) ?: tryReadMetadata(metadataTmpFile)
        if (cached != null) {
            lastLoadedAssets = folderName to cached
            onProgress(1.0f)
            return@withContext cached
        }

        // Fallback: rebuild metadata from the retained internal source archive.
        if (sourceArchiveFile.exists()) {
            Log.w("AEI_CACHE", "Metadata missing/corrupt for $folderName, rebuilding from source archive")
            return@withContext unpackAndLoad(context, sourceArchiveFile, folderName, onProgress)
        }

        if (unpackedFolder.exists()) {
            val sourceFromName = File(getUnpackedFolder(context), "$folderName.aei")
            if (sourceFromName.exists()) {
                return@withContext unpackAndLoad(context, sourceFromName, folderName, onProgress)
            }
        }

        null
    }

    private suspend fun unpackAndLoad(context: Context, zipFile: File, folderName: String, onProgress: (Float) -> Unit): RecipeAssets? {
        val unpackedFolder = File(getUnpackedFolder(context), folderName).apply { if (!exists()) mkdirs() }
        val metadataFile = File(unpackedFolder, METADATA_FILE_NAME)

        val translations = mutableMapOf<String, String>()
        val imagePaths = mutableMapOf<String, File>()
        val originalImagePaths = mutableMapOf<String, String>()
        val recipesByOutput = mutableMapOf<String, MutableList<RecipeDump>>()
        val uniqueItemsSet = mutableSetOf<String>()

        try {
            ZipFile(zipFile).use { zip ->
                val entriesList = zip.entries().asSequence().toList()
                val stageStart = 0.02f
                val stageSpan = 0.88f
                val totalWork = entriesList.sumOf { entry ->
                    val size = if (entry.size > 0L) entry.size else 1L
                    max(size, 1L)
                }.toDouble()
                var completedWork = 0.0
                var lastEmittedProgress = 0f

                fun emitProgress(currentEntryWork: Double = 0.0, partialInEntry: Double = 1.0) {
                    if (totalWork <= 0.0) return
                    val boundedPartial = partialInEntry.coerceIn(0.0, 1.0)
                    val raw = ((completedWork + (currentEntryWork * boundedPartial)) / totalWork).coerceIn(0.0, 1.0)
                    val mapped = (stageStart + (raw.toFloat() * stageSpan)).coerceAtMost(0.90f)
                    // Throttle tiny updates while still keeping the bar visibly moving.
                    if (mapped - lastEmittedProgress >= 0.0025f || mapped >= 0.899f) {
                        lastEmittedProgress = mapped
                        onProgress(mapped)
                    }
                }

                entriesList.forEach { entry ->
                    val entryWork = max(if (entry.size > 0L) entry.size else 1L, 1L).toDouble()
                    
                    val entryName = entry.name.replace("\\", "/")
                    
                    when {
                        entryName.endsWith("translations.json", ignoreCase = true) -> {
                            try {
                                val countingInput = CountingInputStream(zip.getInputStream(entry))
                                JsonReader(InputStreamReader(countingInput)).use { reader ->
                                    reader.beginObject()
                                    var keyCount = 0
                                    while (reader.hasNext()) {
                                        val key = reader.nextName().lowercase().intern()
                                        val value = reader.nextString().intern()
                                        translations[key] = value
                                        keyCount++
                                        if (keyCount % 200 == 0 && entry.size > 0L) {
                                            emitProgress(entryWork, countingInput.progressFraction(entry.size))
                                        }
                                    }
                                    reader.endObject()
                                }
                            } catch (e: Exception) {
                                Log.e("AEI_LOAD", "Failed to load translations", e)
                            }
                        }
                        entryName.endsWith("recipes.json", ignoreCase = true) -> {
                            try {
                                val countingInput = CountingInputStream(zip.getInputStream(entry))
                                JsonReader(InputStreamReader(countingInput)).use { reader ->
                                    reader.beginArray()
                                    var recipeCount = 0
                                    while (reader.hasNext()) {
                                        val recipe: RecipeDump = internedGson.fromJson(reader, RecipeDump::class.java)
                                        val outputs = recipe.slots.filter { it.role == "OUTPUT" }
                                        
                                        // Map all possible outputs for this recipe
                                        val outputIds = outputs.flatMap { it.ingredients }.mapNotNull { it.getResolvedId() }.distinct()
                                        outputIds.forEach { id ->
                                            if (shouldExcludeFluidStack(id)) return@forEach
                                            recipesByOutput.getOrPut(id) { mutableListOf() }.add(recipe)
                                            uniqueItemsSet.add(id)
                                        }
                                        
                                        // Also track inputs in uniqueItemsSet for discovery
                                        recipe.slots.filter { it.role == "INPUT" }.forEach { slot ->
                                            slot.ingredients.forEach { ing ->
                                                ing.getResolvedId()?.let { resolvedId ->
                                                    if (!shouldExcludeFluidStack(resolvedId)) {
                                                        uniqueItemsSet.add(resolvedId)
                                                    }
                                                }
                                            }
                                        }

                                        recipeCount++
                                        if (recipeCount % 150 == 0 && entry.size > 0L) {
                                            emitProgress(entryWork, countingInput.progressFraction(entry.size))
                                        }
                                    }
                                    reader.endArray()
                                }
                            } catch (e: Exception) {
                                Log.e("AEI_LOAD", "Failed to load recipes", e)
                            }
                        }
                        entryName.contains("inventory_images/", ignoreCase = true) && entryName.endsWith(".png", ignoreCase = true) -> {
                            val segments = entryName.split(Regex("inventory_images/", RegexOption.IGNORE_CASE), 2)
                            val pathAfterImages = segments.last()
                            
                            val relPath = pathAfterImages.substringBeforeLast(".png").lowercase()
                            val fileName = relPath.substringAfterLast("/")
                            val typePath = if (relPath.contains("/")) relPath.substringBeforeLast("/") + "/" else ""

                            val safeName = entryName.replace("/", "_").replace(":", "_").replace(" ", "_")
                            val outFile = File(unpackedFolder, safeName)
                            
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
                            if (typePath.isEmpty()) mapKey(fileName)
                        }
                    }

                    completedWork += entryWork
                    emitProgress()
                }
            }

            onProgress(0.92f)
            val resolver = RecipeAssets(translations, emptyMap(), emptyMap(), emptyList(), emptyMap())
            val uniqueItems = uniqueItemsSet.toList().sortedBy { id -> resolver.getTranslation(id) }
            val assets = RecipeAssets(translations, imagePaths, originalImagePaths, uniqueItems, recipesByOutput)

            lastLoadedAssets = folderName to assets
            scheduleMetadataWrite(metadataFile, assets)
            onProgress(1.0f)
            return assets
        } catch (e: Exception) {
            Log.e("AEI_ERROR", "Unpack failed", e)
            return null
        }
    }

    private fun scheduleMetadataWrite(metadataFile: File, assets: RecipeAssets) {
        cacheWriteScope.launch {
            try {
                val tmpFile = File(metadataFile.parentFile, "${metadataFile.name}.tmp")
                tmpFile.outputStream().use { output ->
                    OutputStreamWriter(output).use { writer ->
                        internedGson.toJson(assets, writer)
                    }
                }

                if (metadataFile.exists() && !metadataFile.delete()) {
                    Log.w("AEI_CACHE", "Could not delete old metadata file before replace")
                }

                if (!tmpFile.renameTo(metadataFile)) {
                    tmpFile.copyTo(metadataFile, overwrite = true)
                    tmpFile.delete()
                }
            } catch (e: Exception) {
                Log.e("AEI_CACHE", "Failed to save metadata", e)
            }
        }
    }

    private class CountingInputStream(input: java.io.InputStream) : FilterInputStream(input) {
        var bytesRead: Long = 0
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) bytesRead++
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val readCount = super.read(b, off, len)
            if (readCount > 0) bytesRead += readCount.toLong()
            return readCount
        }

        fun progressFraction(totalSize: Long): Double {
            if (totalSize <= 0L) return 0.0
            return (bytesRead.toDouble() / totalSize.toDouble()).coerceIn(0.0, 1.0)
        }
    }

    fun loadBitmap(file: File?): Bitmap? = file?.let { 
        val cacheKey = it.absolutePath
        bitmapCache.get(cacheKey)?.let { cached -> return cached }

        if (!it.exists()) return@let null
        try {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val bitmap = BitmapFactory.decodeFile(it.absolutePath, options)
            if (bitmap != null) bitmapCache.put(cacheKey, bitmap)
            bitmap
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

    fun deleteUnpackedFolder(context: Context, folder: File) {
        if (getLastLoadedFolder(context) == folder.name) setLastLoadedFolder(context, null)
        folder.deleteRecursively()
        if (lastLoadedAssets?.first == folder.name) lastLoadedAssets = null
        bitmapCache.evictAll()
    }

    fun getLastLoadedFolder(context: Context): String? = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_LOADED, null)
    fun setLastLoadedFolder(context: Context, folderName: String?) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_LAST_LOADED, folderName).apply()
}
