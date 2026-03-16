package me.infin1te.aei

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.util.LruCache
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Collections
import kotlin.math.min
import kotlin.math.max
import java.util.LinkedHashSet
import java.util.zip.ZipFile
import java.util.concurrent.Semaphore

object AssetManager {
    data class ThumbnailPreloadUpdate(
        val active: Boolean,
        val progress: Float,
        val message: String
    )

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
    private val imageIndexType = object : TypeToken<Map<String, String>>() {}.type

    private const val UNPACKED_DIR = "unpacked_assets"
    private const val METADATA_FILE_NAME = "metadata_v8.bin"
    private const val LEGACY_METADATA_FILE_NAME = "metadata_v7.json"
    private const val METADATA_MAGIC = 0x4145494D
    private const val METADATA_VERSION = 1
    private const val IMAGE_INDEX_FILE_NAME = "image_index_v1.json"
    private const val IMAGE_CACHE_DIR_NAME = "image_cache"
    private const val THUMBNAIL_CACHE_DIR_NAME = "thumb_png"
    private const val THUMBNAIL_PREWARM_BUCKET = 32
    private const val THUMBNAIL_PREWARM_LIMIT = 400
    private const val SOURCE_ARCHIVE_NAME = "source.aei"
    private const val PREFS_NAME = "aei_prefs"
    private const val KEY_LAST_LOADED = "last_loaded_folder"
    private const val TRANSLATIONS_FILE_NAME = "translations.json"
    private const val RECIPES_FILE_NAME = "recipes.json"
    private const val RECIPES_MANIFEST_FILE_NAME = "recipes_manifest.json"
    private const val RECIPES_CHUNK_DIR = "recipes_by_type/"
    private val IMAGE_ROOTS = listOf("assets/inventory_images/", "inventory_images/")
    private const val LOAD_TRACE_TAG = "AEI_LOAD_TRACE"

    private val bitmapCacheMaxBytes: Int = run {
        val maxMemory = Runtime.getRuntime().maxMemory().coerceAtLeast(16L * 1024L * 1024L)
        val oneSixteenth = (maxMemory / 16L).coerceAtLeast(4L * 1024L * 1024L)
        min(oneSixteenth, 24L * 1024L * 1024L).toInt()
    }
    private val bitmapCache = object : LruCache<String, Bitmap>(bitmapCacheMaxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.allocationByteCount.coerceAtLeast(1)
        }
    }
    private val bitmapDecodeGate = Semaphore(2, true)
    private val inFlightDecodeLocks = Collections.synchronizedMap(mutableMapOf<String, Any>())
    private var lastLoadedAssets: Pair<String, RecipeAssets>? = null
    private val imageIndexCache = Collections.synchronizedMap(mutableMapOf<String, MutableMap<String, String>>())
    private const val MISSING_IMAGE_SENTINEL = "__missing__"
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private fun logLoad(folderName: String, message: String) {
        Log.i(LOAD_TRACE_TAG, "[$folderName] $message")
    }

    private fun emitProgress(
        folderName: String,
        onProgress: (Float) -> Unit,
        onStage: ((String) -> Unit)?,
        progress: Float,
        stage: String? = null
    ) {
        val clamped = progress.coerceIn(0f, 1f)
        onProgress(clamped)
        if (!stage.isNullOrBlank()) {
            onStage?.invoke(stage)
            logLoad(folderName, "progress stage=\"$stage\" pct=${(clamped * 100f).toInt()}")
        }
    }

    private fun getUnpackedFolder(context: Context): File = File(context.filesDir, UNPACKED_DIR).apply { if (!exists()) mkdirs() }

    private fun shouldExcludeFluidStack(id: String): Boolean {
        val lower = id.lowercase()
        if (!lower.contains("flowing")) return false

        val prefix = lower.substringBefore('/', missingDelimiterValue = "")
        return lower.startsWith("fluid/") || lower.startsWith("fluid_stack/") || prefix.contains("fluid")
    }

    suspend fun importAeiFile(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit,
        onStage: ((String) -> Unit)? = null,
        onThumbnailPreloadUpdate: ((ThumbnailPreloadUpdate) -> Unit)? = null
    ): RecipeAssets? = withContext(Dispatchers.IO) {
        val importStartMs = nowMs()
        val fileName = getFileName(context, uri) ?: "imported_${System.currentTimeMillis()}.aei"
        val folderName = fileName.substringBeforeLast(".")
        val tempFile = File(context.cacheDir, "temp_import.aei")
        val unpackedFolder = File(getUnpackedFolder(context), folderName)
        val metadataFile = File(unpackedFolder, METADATA_FILE_NAME)
        val sourceArchiveFile = File(unpackedFolder, SOURCE_ARCHIVE_NAME)
        logLoad(folderName, "import start file=$fileName")
        
        try {
            // Fast path: already unpacked and indexed, reuse cached folder only.
            if (metadataFile.exists()) {
                emitProgress(folderName, onProgress, onStage, 0.08f, "Checking existing cache")
                val cached = loadFromUnpacked(context, folderName, onProgress, onStage, onThumbnailPreloadUpdate)
                if (cached != null) {
                    setLastLoadedFolder(context, folderName)
                    logLoad(folderName, "import reused existing unpacked cache totalMs=${nowMs() - importStartMs}")
                    return@withContext cached
                }
            }

            if (unpackedFolder.exists()) {
                unpackedFolder.deleteRecursively()
            }
            unpackedFolder.mkdirs()

            emitProgress(folderName, onProgress, onStage, 0.02f, "Copying selected archive")
            val copyStartMs = nowMs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }
            logLoad(folderName, "copied selected archive to temp bytes=${tempFile.length()} copyMs=${nowMs() - copyStartMs}")

            // Keep an internal archive copy so we can rebuild metadata if cache is missing/corrupt.
            val persistStartMs = nowMs()
            tempFile.copyTo(sourceArchiveFile, overwrite = true)
            logLoad(folderName, "persisted source archive bytes=${sourceArchiveFile.length()} persistMs=${nowMs() - persistStartMs}")
            
            val assets = unpackAndLoad(context, sourceArchiveFile, folderName, onProgress, onStage, onThumbnailPreloadUpdate)
            tempFile.delete()
            
            if (assets != null) setLastLoadedFolder(context, folderName)
            logLoad(folderName, "import complete success=${assets != null} totalMs=${nowMs() - importStartMs}")
            assets
        } catch (e: Exception) {
            Log.e("AEI_ERROR", "Import failed", e)
            logLoad(folderName, "import failed afterMs=${nowMs() - importStartMs} error=${e.javaClass.simpleName}")
            tempFile.delete()
            null
        }
    }

    fun listImportedFolders(context: Context): List<File> = 
        getUnpackedFolder(context).listFiles { f -> f.isDirectory }?.toList() ?: emptyList()

    suspend fun loadFromUnpacked(
        context: Context,
        folderName: String,
        onProgress: (Float) -> Unit,
        onStage: ((String) -> Unit)? = null,
        onThumbnailPreloadUpdate: ((ThumbnailPreloadUpdate) -> Unit)? = null
    ): RecipeAssets? = withContext(Dispatchers.IO) {
        val loadStartMs = nowMs()
        logLoad(folderName, "loadFromUnpacked start")
        if (lastLoadedAssets?.first == folderName) {
            emitProgress(folderName, onProgress, onStage, 1.0f, "Library ready")
            logLoad(folderName, "loadFromUnpacked memory-cache hit totalMs=${nowMs() - loadStartMs}")
            return@withContext lastLoadedAssets?.second
        }

        val unpackedFolder = File(getUnpackedFolder(context), folderName)
        val metadataFile = File(unpackedFolder, METADATA_FILE_NAME)
        val metadataTmpFile = File(unpackedFolder, "$METADATA_FILE_NAME.tmp")
        val legacyMetadataFile = File(unpackedFolder, LEGACY_METADATA_FILE_NAME)
        val sourceArchiveFile = File(unpackedFolder, SOURCE_ARCHIVE_NAME)
        val imageIndexFile = File(unpackedFolder, IMAGE_INDEX_FILE_NAME)
        val imageCacheDir = File(unpackedFolder, IMAGE_CACHE_DIR_NAME)

        if (metadataFile.exists() && metadataTmpFile.exists()) {
            metadataTmpFile.delete()
            logLoad(folderName, "deleted stale metadata tmp file")
        }

        fun tryReadMetadata(file: File, isLegacyFile: Boolean): RecipeAssets? {
            if (!file.exists()) return null
            val readStartMs = nowMs()
            return try {
                val compact = readCompactMetadata(file, folderName, sourceArchiveFile, imageIndexFile, imageCacheDir)
                logLoad(folderName, "metadata read compact file=${file.name} ms=${nowMs() - readStartMs}")
                compact
            } catch (e: Exception) {
                if (!isLegacyFile) {
                    Log.w("AEI_CACHE", "Binary metadata read failed for ${file.name}; falling back to legacy metadata file", e)
                    logLoad(folderName, "metadata read failed file=${file.name} ms=${nowMs() - readStartMs} error=${e.javaClass.simpleName}")
                    null
                } else {
                    try {
                        val legacy = readLegacyMetadata(file, folderName, sourceArchiveFile, imageIndexFile, imageCacheDir)
                        logLoad(folderName, "metadata read legacy file=${file.name} ms=${nowMs() - readStartMs}")
                        legacy
                    } catch (legacyError: Exception) {
                        Log.e("AEI_CACHE", "Failed to load metadata cache from ${file.name}", legacyError)
                        logLoad(folderName, "metadata read failed file=${file.name} ms=${nowMs() - readStartMs} error=${legacyError.javaClass.simpleName}")
                        null
                    }
                }
            }
        }

        emitProgress(folderName, onProgress, onStage, 0.04f, "Preparing cached library")
        emitProgress(folderName, onProgress, onStage, 0.08f, "Reading binary metadata")
        val binaryCached = tryReadMetadata(metadataFile, isLegacyFile = false)
        val cached = binaryCached ?: run {
            emitProgress(folderName, onProgress, onStage, 0.18f, "Reading legacy metadata")
            tryReadMetadata(legacyMetadataFile, isLegacyFile = true)
        }

        if (cached != null) {
            emitProgress(folderName, onProgress, onStage, 0.82f, "Finalizing library cache")

            if (binaryCached == null && legacyMetadataFile.exists()) {
                val migrateStartMs = nowMs()
                backgroundScope.launch {
                    val migrateResult = writeMetadataBlocking(metadataFile, cached)
                    logLoad(
                        folderName,
                        "metadata migrate-to-binary success=${migrateResult.success} ms=${nowMs() - migrateStartMs} error=${migrateResult.error ?: "none"}"
                    )
                }
            }

            emitProgress(folderName, onProgress, onStage, 0.88f, "Preloading thumbnails")
            onThumbnailPreloadUpdate?.invoke(
                ThumbnailPreloadUpdate(
                    active = true,
                    progress = 0f,
                    message = "Preloading thumbnails"
                )
            )
            prewarmThumbnailsBlocking(cached, THUMBNAIL_PREWARM_BUCKET, THUMBNAIL_PREWARM_LIMIT) { ratio ->
                onProgress((0.88f + (ratio * 0.11f)).coerceAtMost(0.99f))
                onThumbnailPreloadUpdate?.invoke(
                    ThumbnailPreloadUpdate(
                        active = true,
                        progress = ratio,
                        message = "Preloading thumbnails (first ${THUMBNAIL_PREWARM_LIMIT})"
                    )
                )
            }
            onThumbnailPreloadUpdate?.invoke(
                ThumbnailPreloadUpdate(
                    active = false,
                    progress = 1f,
                    message = "Thumbnail preload complete"
                )
            )

            lastLoadedAssets = folderName to cached
            emitProgress(folderName, onProgress, onStage, 1.0f, "Library ready")
            logLoad(folderName, "loadFromUnpacked metadata-cache hit totalMs=${nowMs() - loadStartMs}")
            return@withContext cached
        }

        if (sourceArchiveFile.exists()) {
            val rebuilt = RecipeAssets(
                libraryFolderName = folderName,
                sourceArchiveFile = sourceArchiveFile,
                imageIndexFile = imageIndexFile,
                imageCacheDir = imageCacheDir,
                translations = emptyMap(),
                uniqueItems = emptyList(),
                recipesByOutput = emptyMap()
            )
            if (metadataFile.exists() || imageIndexFile.exists()) {
                imageIndexCache.remove(rebuilt.libraryFolderName)
                logLoad(folderName, "cleared stale in-memory image index before rebuild")
            }
        }

        // Fallback: rebuild metadata from the retained internal source archive.
        if (sourceArchiveFile.exists()) {
            Log.w("AEI_CACHE", "Metadata missing/corrupt for $folderName, rebuilding from source archive")
            logLoad(folderName, "metadata cache miss; rebuilding from source archive")
            emitProgress(folderName, onProgress, onStage, 0.2f, "Rebuilding metadata from archive")
            return@withContext unpackAndLoad(context, sourceArchiveFile, folderName, onProgress, onStage, onThumbnailPreloadUpdate)
        }

        if (unpackedFolder.exists()) {
            val sourceFromName = File(getUnpackedFolder(context), "$folderName.aei")
            if (sourceFromName.exists()) {
                logLoad(folderName, "fallback source archive found by legacy name")
                emitProgress(folderName, onProgress, onStage, 0.2f, "Rebuilding metadata from legacy archive")
                return@withContext unpackAndLoad(context, sourceFromName, folderName, onProgress, onStage, onThumbnailPreloadUpdate)
            }
        }

        logLoad(folderName, "loadFromUnpacked failed: no metadata and no source archive totalMs=${nowMs() - loadStartMs}")
        null
    }

    private suspend fun unpackAndLoad(
        context: Context,
        zipFile: File,
        folderName: String,
        onProgress: (Float) -> Unit,
        onStage: ((String) -> Unit)? = null,
        onThumbnailPreloadUpdate: ((ThumbnailPreloadUpdate) -> Unit)? = null
    ): RecipeAssets? {
        val unpackStartMs = nowMs()
        logLoad(folderName, "unpackAndLoad start archive=${zipFile.name} bytes=${zipFile.length()}")
        val unpackedFolder = File(getUnpackedFolder(context), folderName).apply { if (!exists()) mkdirs() }
        val metadataFile = File(unpackedFolder, METADATA_FILE_NAME)
        val imageIndexFile = File(unpackedFolder, IMAGE_INDEX_FILE_NAME)
        val imageCacheDir = File(unpackedFolder, IMAGE_CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }

        val translations = mutableMapOf<String, String>()
        val recipesByOutput = mutableMapOf<String, MutableList<RecipeDump>>()
        val uniqueItemsSet = mutableSetOf<String>()
        val imageSourcePaths = mutableMapOf<String, String>()

        try {
            ZipFile(zipFile).use { zip ->
                val entryScanStartMs = nowMs()
                val entriesByName = linkedMapOf<String, java.util.zip.ZipEntry>()

                zip.entries().asSequence().forEach { entry ->
                    val entryName = normalizeZipPath(entry.name)
                    entriesByName[entryName] = entry
                }
                logLoad(folderName, "indexed zip entries count=${entriesByName.size} ms=${nowMs() - entryScanStartMs}")
                val imageLookupStartMs = nowMs()
                val imageSourceLookup = buildImageSourceLookup(entriesByName)
                logLoad(folderName, "built image source lookup entries=${imageSourceLookup.size} ms=${nowMs() - imageLookupStartMs}")

                emitProgress(folderName, onProgress, onStage, 0.08f, "Reading translations")
                val translationStartMs = nowMs()
                loadTranslations(zip, entriesByName, translations)
                logLoad(folderName, "loaded translations count=${translations.size} ms=${nowMs() - translationStartMs}")
                emitProgress(folderName, onProgress, onStage, 0.14f, "Reading recipes")
                val recipesStartMs = nowMs()
                loadRecipes(zip, entriesByName, recipesByOutput, uniqueItemsSet, onProgress)
                val recipeCount = LinkedHashSet<RecipeDump>().apply {
                    recipesByOutput.values.forEach { addAll(it) }
                }.size
                logLoad(folderName, "loaded recipes uniqueRecipes=$recipeCount outputs=${recipesByOutput.size} uniqueItems=${uniqueItemsSet.size} ms=${nowMs() - recipesStartMs}")
                val imageCandidateKeys = LinkedHashSet<String>().apply {
                    addAll(translations.keys)
                    addAll(uniqueItemsSet)
                }
                logLoad(folderName, "image mapping candidates=${imageCandidateKeys.size}")

                val imageMapStartMs = nowMs()
                val totalCandidates = imageCandidateKeys.size.coerceAtLeast(1)
                var processedCandidates = 0
                emitProgress(folderName, onProgress, onStage, 0.62f, "Indexing image keys")
                imageCandidateKeys.forEach { key ->
                    resolveImageSourcePath(imageSourceLookup, key)?.let { sourcePath ->
                        imageSourcePaths.putIfAbsent(key, sourcePath)
                    }
                    processedCandidates++
                    if (processedCandidates % 200 == 0 || processedCandidates == totalCandidates) {
                        val ratio = processedCandidates.toFloat() / totalCandidates.toFloat()
                        onProgress((0.62f + (ratio * 0.14f)).coerceAtMost(0.76f))
                    }
                }
                logLoad(folderName, "mapped image sources resolved=${imageSourcePaths.size} ms=${nowMs() - imageMapStartMs}")

                val aliasStartMs = nowMs()
                emitProgress(folderName, onProgress, onStage, 0.78f, "Expanding aliases")
                addDerivedAliases(translations, imageSourcePaths)
                logLoad(folderName, "applied alias expansion translations=${translations.size} imageKeys=${imageSourcePaths.size} ms=${nowMs() - aliasStartMs}")
            }

            emitProgress(folderName, onProgress, onStage, 0.82f, "Preparing final index")
            val resolver = RecipeAssets(
                libraryFolderName = folderName,
                sourceArchiveFile = zipFile,
                imageIndexFile = imageIndexFile,
                imageCacheDir = imageCacheDir,
                translations = translations,
                uniqueItems = emptyList(),
                recipesByOutput = emptyMap()
            )
            val uniqueItems = uniqueItemsSet.toList().sortedBy { id -> resolver.getTranslation(id) }
            logLoad(folderName, "sorted unique items count=${uniqueItems.size}")
            val assets = RecipeAssets(
                libraryFolderName = folderName,
                sourceArchiveFile = zipFile,
                imageIndexFile = imageIndexFile,
                imageCacheDir = imageCacheDir,
                translations = translations,
                uniqueItems = uniqueItems,
                recipesByOutput = recipesByOutput
            )

            lastLoadedAssets = folderName to assets
            val metadataWriteStartMs = nowMs()
            emitProgress(folderName, onProgress, onStage, 0.84f, "Writing metadata cache")
            val metadataSizeHint = max(metadataFile.length(), 256L * 1024L * 1024L)
            val metadataWriteResult = writeMetadataBlocking(metadataFile, assets) { bytesWritten ->
                val ratio = (bytesWritten.toDouble() / metadataSizeHint.toDouble()).coerceIn(0.0, 1.0)
                onProgress((0.84f + (ratio.toFloat() * 0.13f)).coerceAtMost(0.97f))
            }
            logLoad(folderName, "metadata write success=${metadataWriteResult.success} bytes=${metadataFile.length()} ms=${nowMs() - metadataWriteStartMs} error=${metadataWriteResult.error ?: "none"}")

            val imageIndexWriteStartMs = nowMs()
            emitProgress(folderName, onProgress, onStage, 0.97f, "Writing image index")
            val imageIndexWriteResult = writeImageIndexBlocking(imageIndexFile, folderName, imageSourcePaths)
            logLoad(folderName, "image index write success=${imageIndexWriteResult.success} bytes=${imageIndexFile.length()} ms=${nowMs() - imageIndexWriteStartMs} error=${imageIndexWriteResult.error ?: "none"}")

            emitProgress(folderName, onProgress, onStage, 0.98f, "Preloading thumbnails")
            onThumbnailPreloadUpdate?.invoke(
                ThumbnailPreloadUpdate(
                    active = true,
                    progress = 0f,
                    message = "Preloading thumbnails"
                )
            )
            prewarmThumbnailsBlocking(assets, THUMBNAIL_PREWARM_BUCKET, THUMBNAIL_PREWARM_LIMIT) { ratio ->
                onProgress((0.98f + (ratio * 0.019f)).coerceAtMost(0.999f))
                onThumbnailPreloadUpdate?.invoke(
                    ThumbnailPreloadUpdate(
                        active = true,
                        progress = ratio,
                        message = "Preloading thumbnails (first ${THUMBNAIL_PREWARM_LIMIT})"
                    )
                )
            }
            onThumbnailPreloadUpdate?.invoke(
                ThumbnailPreloadUpdate(
                    active = false,
                    progress = 1f,
                    message = "Thumbnail preload complete"
                )
            )

            imageIndexCache[folderName] = imageSourcePaths.toMutableMap()
            emitProgress(folderName, onProgress, onStage, 1.0f, "Library ready")
            logLoad(folderName, "unpackAndLoad complete totalMs=${nowMs() - unpackStartMs}")
            return assets
        } catch (e: Exception) {
            Log.e("AEI_ERROR", "Unpack failed", e)
            logLoad(folderName, "unpackAndLoad failed afterMs=${nowMs() - unpackStartMs} error=${e.javaClass.simpleName}")
            return null
        }
    }

    private fun writeMetadataBlocking(
        metadataFile: File,
        assets: RecipeAssets,
        onBytesWritten: ((Long) -> Unit)? = null
    ): WriteResult {
        return try {
            val tmpFile = File(metadataFile.parentFile, "${metadataFile.name}.tmp")
            val compact = toCompactMetadata(assets)
            tmpFile.outputStream().use { output ->
                val reportingOutput = CountingOutputStream(
                    output,
                    onBytesWritten = { bytesWritten: Long -> onBytesWritten?.invoke(bytesWritten) }
                )
                DataOutputStream(BufferedOutputStream(reportingOutput, 1 shl 20)).use { dataOut ->
                    writeCompactMetadataBinary(dataOut, compact)
                    dataOut.flush()
                }
                try {
                    output.fd.sync()
                } catch (syncError: Exception) {
                    Log.w("AEI_CACHE", "Metadata fsync failed; continuing with rename", syncError)
                }
            }

            if (metadataFile.exists() && !metadataFile.delete()) {
                Log.w("AEI_CACHE", "Could not delete old metadata file before replace")
            }

            if (!tmpFile.renameTo(metadataFile)) {
                tmpFile.copyTo(metadataFile, overwrite = true)
                tmpFile.delete()
            }
            WriteResult(success = true, error = null)
        } catch (e: Exception) {
            Log.e("AEI_CACHE", "Failed to save metadata", e)
            WriteResult(success = false, error = "${e.javaClass.simpleName}: ${e.message ?: "unknown"}")
        }
    }

    private fun writeImageIndexBlocking(imageIndexFile: File, folderName: String, imageSourcePaths: Map<String, String>): WriteResult {
        return try {
            val tmpFile = File(imageIndexFile.parentFile, "${imageIndexFile.name}.tmp")
            tmpFile.outputStream().use { output ->
                OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                    internedGson.toJson(imageSourcePaths, imageIndexType, writer)
                    writer.flush()
                }
                try {
                    output.fd.sync()
                } catch (syncError: Exception) {
                    Log.w("AEI_CACHE", "Image index fsync failed; continuing with rename", syncError)
                }
            }

            if (imageIndexFile.exists() && !imageIndexFile.delete()) {
                Log.w("AEI_CACHE", "Could not delete old image index before replace")
            }

            if (!tmpFile.renameTo(imageIndexFile)) {
                tmpFile.copyTo(imageIndexFile, overwrite = true)
                tmpFile.delete()
            }

            imageIndexCache[folderName] = imageSourcePaths.toMutableMap()
            WriteResult(success = true, error = null)
        } catch (e: Exception) {
            Log.e("AEI_CACHE", "Failed to save image index", e)
            WriteResult(success = false, error = "${e.javaClass.simpleName}: ${e.message ?: "unknown"}")
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

    private class CountingOutputStream(
        output: java.io.OutputStream,
        private val onBytesWritten: ((Long) -> Unit)? = null,
        private val reportStepBytes: Long = 1L shl 20
    ) : java.io.FilterOutputStream(output) {
        private var bytesWritten: Long = 0
        private var nextReportAt: Long = reportStepBytes

        override fun write(b: Int) {
            out.write(b)
            bytesWritten += 1
            maybeReport()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            if (len > 0) {
                bytesWritten += len.toLong()
                maybeReport()
            }
        }

        private fun maybeReport() {
            if (onBytesWritten == null) return
            if (bytesWritten >= nextReportAt) {
                onBytesWritten.invoke(bytesWritten)
                while (nextReportAt <= bytesWritten) {
                    nextReportAt += reportStepBytes
                }
            }
        }
    }

    fun loadBitmap(assets: RecipeAssets, id: String, requestedSizePx: Int = 64): Bitmap? {
        val normalizedId = id.lowercase()
        val safeRequestedSize = requestedSizePx.coerceAtLeast(8)
        val sizeBucket = thumbnailSizeBucket(safeRequestedSize)
        val cacheKey = "${assets.libraryFolderName}:${normalizedId}#${sizeBucket}"
        bitmapCache.get(cacheKey)?.let { cached -> return cached }

        val decodeLock = synchronized(inFlightDecodeLocks) {
            inFlightDecodeLocks.getOrPut(cacheKey) { Any() }
        }

        synchronized(decodeLock) {
            bitmapCache.get(cacheKey)?.let { cached -> return cached }
            val cachedFile = getOrCreateDisplayImageFile(assets, normalizedId, sizeBucket)
            if (cachedFile == null || !cachedFile.exists()) return null

            var acquired = false
            try {
                bitmapDecodeGate.acquire()
                acquired = true
                val bitmap = decodeSampledBitmap(cachedFile, sizeBucket)
                if (bitmap != null) bitmapCache.put(cacheKey, bitmap)
                return bitmap
            } catch (e: Exception) {
                Log.e("AEI_ERROR", "Bitmap decode failed: ${cachedFile.absolutePath}", e)
                return null
            } finally {
                if (acquired) bitmapDecodeGate.release()
                synchronized(inFlightDecodeLocks) {
                    if (inFlightDecodeLocks[cacheKey] === decodeLock) {
                        inFlightDecodeLocks.remove(cacheKey)
                    }
                }
            }
        }
    }

    fun getOrCreateDisplayImageFile(assets: RecipeAssets, id: String, requestedSizePx: Int): File? {
        val normalizedId = id.lowercase()
        val bucket = thumbnailSizeBucket(requestedSizePx.coerceAtLeast(8))
        val sourcePath = getImageSourcePath(assets, normalizedId)
        val preferredThumb = sourcePath?.let { thumbnailFileFor(assets, it, bucket) }
        if (preferredThumb != null && preferredThumb.exists()) {
            return preferredThumb
        }

        return getOrCreateCachedImageFile(assets, normalizedId)
    }

    private fun thumbnailFileFor(assets: RecipeAssets, sourcePath: String, bucket: Int): File {
        val relative = cacheRelativeImagePath(sourcePath)
        val relativeNoExt = relative.substringBeforeLast('.', relative)
        return File(assets.imageCacheDir, "$THUMBNAIL_CACHE_DIR_NAME/$bucket/$relativeNoExt.png")
    }

    private fun getOrCreateThumbnailImageFile(assets: RecipeAssets, id: String, requestedSizePx: Int): File? {
        val sourcePath = getImageSourcePath(assets, id) ?: return null
        val bucket = thumbnailSizeBucket(requestedSizePx)
        val thumbFile = thumbnailFileFor(assets, sourcePath, bucket)
        if (thumbFile.exists()) return thumbFile

        return try {
            thumbFile.parentFile?.mkdirs()
            ZipFile(assets.sourceArchiveFile).use { zip ->
                val entry = zip.getEntry(sourcePath) ?: zip.entries().asSequence().firstOrNull {
                    normalizeZipPath(it.name).equals(sourcePath, ignoreCase = true)
                } ?: return null

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                zip.getInputStream(entry).use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                }

                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inDither = true
                    inSampleSize = calculateInSampleSize(
                        sourceWidth = bounds.outWidth,
                        sourceHeight = bounds.outHeight,
                        requestedSize = bucket
                    )
                }

                val bitmap = zip.getInputStream(entry).use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                } ?: return null

                val tmp = File(thumbFile.parentFile, "${thumbFile.name}.tmp")
                tmp.outputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    output.flush()
                }
                bitmap.recycle()

                if (thumbFile.exists() && !thumbFile.delete()) {
                    Log.w("AEI_CACHE", "Could not replace existing thumbnail ${thumbFile.name}")
                }
                if (!tmp.renameTo(thumbFile)) {
                    tmp.copyTo(thumbFile, overwrite = true)
                    tmp.delete()
                }
            }

            thumbFile.takeIf { it.exists() }
        } catch (e: Exception) {
            Log.w("AEI_CACHE", "Failed to generate PNG thumbnail for $id", e)
            null
        }
    }

    private fun thumbnailSizeBucket(requestedSizePx: Int): Int {
        return when {
            requestedSizePx <= 24 -> 24
            requestedSizePx <= 32 -> 32
            requestedSizePx <= 48 -> 48
            requestedSizePx <= 64 -> 64
            else -> 96
        }
    }

    private fun prewarmMarkerFile(assets: RecipeAssets, bucket: Int, limit: Int): File {
        return File(assets.imageCacheDir, "$THUMBNAIL_CACHE_DIR_NAME/$bucket/.ready_$limit")
    }

    private fun isThumbnailPrewarmComplete(assets: RecipeAssets, bucket: Int, limit: Int): Boolean {
        return prewarmMarkerFile(assets, bucket, limit).exists()
    }

    private fun markThumbnailPrewarmComplete(assets: RecipeAssets, bucket: Int, limit: Int) {
        val marker = prewarmMarkerFile(assets, bucket, limit)
        marker.parentFile?.mkdirs()
        if (!marker.exists()) {
            marker.writeText("ok")
        }
    }

    private fun prewarmThumbnailsBlocking(
        assets: RecipeAssets,
        bucket: Int,
        limit: Int,
        onProgress: ((Float) -> Unit)? = null
    ) {
        val start = nowMs()
        val preloadIds = assets.uniqueItems.take(limit)
        val total = preloadIds.size.coerceAtLeast(1)
        var processed = 0
        var generated = 0

        preloadIds.forEach { itemId ->
            val before = getOrCreateDisplayImageFile(assets, itemId, bucket)
            val thumb = if (before != null && before.path.contains("/$THUMBNAIL_CACHE_DIR_NAME/")) {
                before
            } else {
                getOrCreateThumbnailImageFile(assets, itemId, bucket)
            }
            if (thumb != null && thumb.exists()) {
                generated++
            }

            processed++
            if (processed % 64 == 0 || processed == total) {
                onProgress?.invoke(processed.toFloat() / total.toFloat())
            }
        }

        markThumbnailPrewarmComplete(assets, bucket, limit)
        logLoad(
            assets.libraryFolderName,
            "thumbnail prewarm complete bucket=$bucket generated=$generated total=$total limit=$limit ms=${nowMs() - start}"
        )
    }

    private fun decodeSampledBitmap(file: File, requestedSizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        val sampleSize = calculateInSampleSize(
            sourceWidth = bounds.outWidth,
            sourceHeight = bounds.outHeight,
            requestedSize = requestedSizePx
        )

        val options = BitmapFactory.Options().apply {
            // RGB_565 halves memory usage for icon-like textures and avoids heap spikes while scrolling.
            inPreferredConfig = Bitmap.Config.RGB_565
            inDither = true
            inSampleSize = sampleSize
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    fun trimBitmapMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                bitmapCache.evictAll()
            }

            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                bitmapCache.trimToSize((bitmapCacheMaxBytes / 4).coerceAtLeast(1))
            }

            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                bitmapCache.trimToSize((bitmapCacheMaxBytes / 2).coerceAtLeast(1))
            }
        }
    }

    fun clearBitmapMemory() {
        bitmapCache.evictAll()
    }

    private fun calculateInSampleSize(sourceWidth: Int, sourceHeight: Int, requestedSize: Int): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1
        var inSampleSize = 1
        var halfWidth = sourceWidth / 2
        var halfHeight = sourceHeight / 2

        while (halfWidth / inSampleSize >= requestedSize && halfHeight / inSampleSize >= requestedSize) {
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    fun getCachedImageFile(assets: RecipeAssets, id: String): File? {
        val sourcePath = getImageSourcePath(assets, id) ?: return null
        return File(assets.imageCacheDir, cacheRelativeImagePath(sourcePath))
    }

    fun getImageSourcePath(assets: RecipeAssets, id: String): String? {
        val normalizedId = id.lowercase()
        val imageIndex = getMutableImageIndex(assets)

        imageIndex[normalizedId]?.let { cached ->
            return if (cached == MISSING_IMAGE_SENTINEL) null else cached
        }

        legacyImageLookupCandidates(normalizedId).forEach { candidate ->
            imageIndex[candidate]?.let { cached ->
                return if (cached == MISSING_IMAGE_SENTINEL) null else cached
            }
        }

        if (!assets.sourceArchiveFile.exists()) {
            imageIndex[normalizedId] = MISSING_IMAGE_SENTINEL
            return null
        }

        val resolved = resolveImageSourcePathFromArchive(assets.sourceArchiveFile, normalizedId)
        if (resolved != null) {
            imageIndex[normalizedId] = resolved
        } else {
            imageIndex[normalizedId] = MISSING_IMAGE_SENTINEL
        }
        return resolved
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
        imageIndexCache.remove(folder.name)
        bitmapCache.evictAll()
    }

    fun getLastLoadedFolder(context: Context): String? = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LAST_LOADED, null)
    fun setLastLoadedFolder(context: Context, folderName: String?) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_LAST_LOADED, folderName).apply()

    private fun normalizeZipPath(path: String): String = path.replace("\\", "/")

    private fun loadTranslations(
        zip: ZipFile,
        entriesByName: Map<String, java.util.zip.ZipEntry>,
        translations: MutableMap<String, String>
    ) {
        val entry = findEntry(entriesByName, TRANSLATIONS_FILE_NAME) ?: return

        try {
            zip.getInputStream(entry).use { input ->
                JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName().lowercase().intern()
                        val value = reader.nextString().intern()
                        translations[key] = value
                    }
                    reader.endObject()
                }
            }
        } catch (e: Exception) {
            Log.e("AEI_LOAD", "Failed to load translations", e)
        }
    }

    private fun loadRecipes(
        zip: ZipFile,
        entriesByName: Map<String, java.util.zip.ZipEntry>,
        recipesByOutput: MutableMap<String, MutableList<RecipeDump>>,
        uniqueItemsSet: MutableSet<String>,
        onProgress: (Float) -> Unit
    ) {
        val recipeLoadStartMs = nowMs()
        val manifestEntry = findEntry(entriesByName, RECIPES_MANIFEST_FILE_NAME)
        val recipeChunkEntries = resolveChunkEntries(zip, entriesByName, manifestEntry)

        if (recipeChunkEntries.isNotEmpty()) {
            var parsedRecipes = 0
            val totalWork = recipeChunkEntries.sumOf { max(if (it.size > 0L) it.size else 1L, 1L) }.toDouble().coerceAtLeast(1.0)
            var completedWork = 0.0

            recipeChunkEntries.forEach { entry ->
                val entryWork = max(if (entry.size > 0L) entry.size else 1L, 1L).toDouble()
                try {
                    val countingInput = CountingInputStream(zip.getInputStream(entry))
                    countingInput.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        var lineCount = 0
                        lines.forEach { line ->
                            if (line.isBlank()) return@forEach
                            val recipe = internedGson.fromJson(line, RecipeDump::class.java)
                            indexRecipe(recipe, recipesByOutput, uniqueItemsSet)
                            parsedRecipes++
                            lineCount++
                            if (lineCount % 100 == 0 && entry.size > 0L) {
                                val progress = (completedWork + (entryWork * countingInput.progressFraction(entry.size))) / totalWork
                                onProgress((0.16f + (progress.toFloat() * 0.56f)).coerceAtMost(0.72f))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AEI_LOAD", "Failed to load recipe chunk ${entry.name}", e)
                }
                completedWork += entryWork
                onProgress((0.16f + ((completedWork / totalWork).toFloat() * 0.56f)).coerceAtMost(0.72f))
            }
            Log.i(LOAD_TRACE_TAG, "[recipe-load] source=ndjson chunks=${recipeChunkEntries.size} recipes=$parsedRecipes ms=${nowMs() - recipeLoadStartMs}")
            return
        }

        val recipesEntry = findEntry(entriesByName, RECIPES_FILE_NAME) ?: return
        try {
            var parsedRecipes = 0
            val countingInput = CountingInputStream(zip.getInputStream(recipesEntry))
            JsonReader(InputStreamReader(countingInput, Charsets.UTF_8)).use { reader ->
                reader.beginArray()
                var recipeCount = 0
                while (reader.hasNext()) {
                    val recipe: RecipeDump = internedGson.fromJson(reader, RecipeDump::class.java)
                    indexRecipe(recipe, recipesByOutput, uniqueItemsSet)
                    parsedRecipes++
                    recipeCount++
                    if (recipeCount % 150 == 0 && recipesEntry.size > 0L) {
                        val progress = countingInput.progressFraction(recipesEntry.size)
                        onProgress((0.16f + (progress.toFloat() * 0.56f)).coerceAtMost(0.72f))
                    }
                }
                reader.endArray()
            }
            Log.i(LOAD_TRACE_TAG, "[recipe-load] source=recipes.json recipes=$parsedRecipes ms=${nowMs() - recipeLoadStartMs}")
        } catch (e: Exception) {
            Log.e("AEI_LOAD", "Failed to load recipes", e)
        }
    }

    private fun indexRecipe(
        recipe: RecipeDump,
        recipesByOutput: MutableMap<String, MutableList<RecipeDump>>,
        uniqueItemsSet: MutableSet<String>
    ) {
        val outputIds = recipe.slots
            .asSequence()
            .filter { it.role == "OUTPUT" }
            .flatMap { it.ingredients.asSequence() }
            .mapNotNull { it.getResolvedId() }
            .distinct()
            .toList()

        outputIds.forEach { id ->
            if (shouldExcludeFluidStack(id)) return@forEach
            recipesByOutput.getOrPut(id) { mutableListOf() }.add(recipe)
            uniqueItemsSet.add(id)
        }

        recipe.slots
            .asSequence()
            .filter { it.role == "INPUT" }
            .flatMap { it.ingredients.asSequence() }
            .mapNotNull { it.getResolvedId() }
            .filterNot { shouldExcludeFluidStack(it) }
            .forEach(uniqueItemsSet::add)
    }

    private fun resolveChunkEntries(
        zip: ZipFile,
        entriesByName: Map<String, java.util.zip.ZipEntry>,
        manifestEntry: java.util.zip.ZipEntry?
    ): List<java.util.zip.ZipEntry> {
        val manifestFiles = manifestEntry?.let { readManifestChunkFiles(zip, it) }.orEmpty()
        val manifestMatches = manifestFiles.mapNotNull { findEntry(entriesByName, it) }.distinct()
        if (manifestMatches.isNotEmpty()) {
            return manifestMatches
        }

        return entriesByName
            .filterKeys { path ->
                path.lowercase().contains(RECIPES_CHUNK_DIR) && path.endsWith(".ndjson", ignoreCase = true)
            }
            .toSortedMap()
            .values
            .toList()
    }

    private fun readManifestChunkFiles(zip: ZipFile, manifestEntry: java.util.zip.ZipEntry): List<String> {
        return try {
            zip.getInputStream(manifestEntry).use { input ->
                val reader = JsonReader(InputStreamReader(input, Charsets.UTF_8))
                val manifest = reader.use {
                    internedGson.fromJson(it, RecipeManifest::class.java) ?: RecipeManifest()
                }
                val declaredFiles = manifest.types
                    .mapNotNull { manifestType -> manifestType.file?.trim() }
                    .filter { it.endsWith(".ndjson", ignoreCase = true) }

                if (declaredFiles.isNotEmpty()) {
                    declaredFiles
                } else {
                    val chunkDirectory = manifest.chunkDirectory?.trim()?.trim('/').orEmpty()
                    if (chunkDirectory.isBlank()) emptyList() else listOf("$chunkDirectory/")
                }
            }
        } catch (e: Exception) {
            Log.e("AEI_LOAD", "Failed to load recipes manifest", e)
            emptyList()
        }
    }

    private fun addDerivedAliases(
        translations: MutableMap<String, String>,
        imageSourcePaths: MutableMap<String, String>
    ) {
        val exactKeys = LinkedHashSet<String>().apply {
            addAll(translations.keys)
            addAll(imageSourcePaths.keys)
        }

        exactKeys.forEach { key ->
            val aliases = derivedAliasesForKey(key)
            aliases.forEach { alias ->
                translations[key]?.let { translations.putIfAbsent(alias, it) }
                imageSourcePaths[key]?.let { imageSourcePaths.putIfAbsent(alias, it) }
            }
        }
    }

    private fun derivedAliasesForKey(key: String): List<String> {
        val parsed = splitTypedExportKey(key) ?: return emptyList()
        val typeUid = parsed.first
        val ingredientId = parsed.second
        if (!ingredientId.contains(':')) return emptyList()

        return buildList {
            if (typeUid.contains("fluid")) {
                add("fluid/$ingredientId")
                add("fluid_stack/$ingredientId")
            }
        }
    }

    private fun expectedImageRelativePath(exportKey: String): String? {
        val lower = exportKey.lowercase()
        val parsed = splitTypedExportKey(lower)

        return if (parsed != null) {
            val typeUid = parsed.first
            val ingredientId = parsed.second
            val fileName = namespacedIdToImageFileName(ingredientId) ?: return null
            "${sanitizePathComponent(typeUid)}/$fileName"
        } else {
            namespacedIdToImageFileName(lower)
        }
    }

    private fun namespacedIdToImageFileName(id: String): String? {
        val separatorIndex = id.indexOf(':')
        if (separatorIndex <= 0 || separatorIndex >= id.length - 1) return null

        val namespace = id.substring(0, separatorIndex)
        val path = id.substring(separatorIndex + 1)
        return "${namespace}_$path.png"
    }

    private fun sanitizePathComponent(value: String): String = value.map { char ->
        when (char) {
            in 'a'..'z', in 'A'..'Z', in '0'..'9', '_', '-' -> char
            else -> '_'
        }
    }.joinToString(separator = "")

    private fun findEntry(
        entriesByName: Map<String, java.util.zip.ZipEntry>,
        path: String
    ): java.util.zip.ZipEntry? {
        val normalized = normalizeZipPath(path).trimStart('/').lowercase()
        return entriesByName.entries.firstOrNull { (entryName, _) ->
            val lowerEntryName = entryName.lowercase()
            lowerEntryName == normalized || lowerEntryName.endsWith("/$normalized")
        }?.value
    }

    private fun getMutableImageIndex(assets: RecipeAssets): MutableMap<String, String> {
        val cached = imageIndexCache[assets.libraryFolderName]
        if (cached != null) {
            return cached
        }

        val loaded = loadImageIndexFromDisk(assets.imageIndexFile)
        val mutableLoaded = loaded.toMutableMap()
        imageIndexCache[assets.libraryFolderName] = mutableLoaded
        return mutableLoaded
    }

    private fun getImageIndex(assets: RecipeAssets): Map<String, String> {
        return getMutableImageIndex(assets)
    }

    private fun loadImageIndexFromDisk(imageIndexFile: File): Map<String, String> {
        if (!imageIndexFile.exists()) return emptyMap()

        return try {
            imageIndexFile.inputStream().use { input ->
                JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    @Suppress("UNCHECKED_CAST")
                    (internedGson.fromJson<Map<String, String>>(reader, imageIndexType) ?: emptyMap()).mapKeys { it.key.lowercase() }
                }
            }
        } catch (e: Exception) {
            Log.e("AEI_CACHE", "Failed to load image index", e)
            emptyMap()
        }
    }

    private fun legacyImageLookupCandidates(id: String): Sequence<String> = sequence {
        yield(id)
        if (id.startsWith("fluid/")) {
            yield("fluid_stack/${id.substringAfter('/')}" )
        }
        if (id.startsWith("fluid_stack/")) {
            yield("fluid/${id.substringAfter('/')}" )
        }
    }

    private fun resolveImageSourcePathFromArchive(sourceArchiveFile: File, id: String): String? {
        return try {
            ZipFile(sourceArchiveFile).use { zip ->
                val entriesByName = linkedMapOf<String, java.util.zip.ZipEntry>()
                zip.entries().asSequence().forEach { entry ->
                    entriesByName[normalizeZipPath(entry.name)] = entry
                }
                resolveImageSourcePath(buildImageSourceLookup(entriesByName), id)
            }
        } catch (e: Exception) {
            Log.e("AEI_LOAD", "Failed to resolve image source path from archive", e)
            null
        }
    }

    private fun getOrCreateCachedImageFile(assets: RecipeAssets, id: String): File? {
        val sourcePath = getImageSourcePath(assets, id) ?: return null
        val cacheFile = File(assets.imageCacheDir, cacheRelativeImagePath(sourcePath))
        if (cacheFile.exists()) {
            return cacheFile
        }

        return try {
            cacheFile.parentFile?.mkdirs()
            ZipFile(assets.sourceArchiveFile).use { zip ->
                val entry = zip.getEntry(sourcePath) ?: zip.entries().asSequence().firstOrNull {
                    normalizeZipPath(it.name).equals(sourcePath, ignoreCase = true)
                } ?: return null

                zip.getInputStream(entry).use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            cacheFile
        } catch (e: Exception) {
            Log.e("AEI_LOAD", "Failed to cache image $sourcePath", e)
            null
        }
    }

    private fun cacheRelativeImagePath(sourcePath: String): String {
        val normalized = normalizeZipPath(sourcePath)
        val relative = IMAGE_ROOTS.firstNotNullOfOrNull { root ->
            normalized.substringAfter(root, missingDelimiterValue = "").takeIf { it.isNotEmpty() && it != normalized }
        } ?: normalized.substringAfterLast('/')
        return relative.lowercase()
    }

    private fun buildImageSourceLookup(
        entriesByName: Map<String, java.util.zip.ZipEntry>
    ): Map<String, String> {
        val lookup = mutableMapOf<String, String>()
        entriesByName.keys.forEach { entryName ->
            extractImageRelativePath(entryName)?.let { relativePath ->
                lookup.putIfAbsent(relativePath, entryName)
            }
        }
        return lookup
    }

    private fun extractImageRelativePath(entryName: String): String? {
        val normalized = normalizeZipPath(entryName)
        if (!normalized.endsWith(".png", ignoreCase = true)) return null

        val lower = normalized.lowercase()
        IMAGE_ROOTS.forEach { root ->
            val index = lower.indexOf(root)
            if (index >= 0) {
                return lower.substring(index + root.length)
            }
        }
        return null
    }

    private fun resolveImageSourcePath(
        imageSourceLookup: Map<String, String>,
        exportKey: String
    ): String? {
        legacyImageLookupCandidates(exportKey.lowercase()).forEach { candidate ->
            val relativePath = expectedImageRelativePath(candidate) ?: return@forEach
            imageSourceLookup[relativePath.lowercase()]?.let { sourcePath ->
                return sourcePath
            }

            // Fallback for exports that used the first '/' split for typed keys.
            val legacyRelativePath = expectedLegacyTypedImageRelativePath(candidate)
            if (legacyRelativePath != null) {
                imageSourceLookup[legacyRelativePath.lowercase()]?.let { sourcePath ->
                    return sourcePath
                }
            }
        }

        return null
    }

    private fun expectedLegacyTypedImageRelativePath(exportKey: String): String? {
        val lower = exportKey.lowercase()
        val slashIndex = lower.indexOf('/')
        if (slashIndex <= 0 || slashIndex >= lower.length - 1) return null

        val typeUid = lower.substring(0, slashIndex)
        val ingredientId = lower.substring(slashIndex + 1)
        val fileName = namespacedIdToImageFileName(ingredientId) ?: return null
        return "${sanitizePathComponent(typeUid)}/$fileName"
    }

    private fun splitTypedExportKey(key: String): Pair<String, String>? {
        var start = 0
        var lastMatchIndex = -1

        while (true) {
            val slashIndex = key.indexOf('/', start)
            if (slashIndex < 0 || slashIndex >= key.length - 1) break

            val right = key.substring(slashIndex + 1)
            val colonIndex = right.indexOf(':')
            if (colonIndex > 0) {
                val namespace = right.substring(0, colonIndex)
                if (namespace.matches(Regex("[a-z0-9_.-]+"))) {
                    lastMatchIndex = slashIndex
                }
            }

            start = slashIndex + 1
        }

        if (lastMatchIndex <= 0 || lastMatchIndex >= key.length - 1) return null
        return key.substring(0, lastMatchIndex) to key.substring(lastMatchIndex + 1)
    }

    private fun toCompactMetadata(assets: RecipeAssets): RecipeAssetsCache {
        val recipeList = mutableListOf<RecipeDump>()
        val recipeIndicesByIdentity = java.util.IdentityHashMap<RecipeDump, Int>()
        val outputs = LinkedHashMap<String, List<Int>>(assets.recipesByOutput.size)

        assets.recipesByOutput.forEach { (outputId, recipes) ->
            val indices = ArrayList<Int>(recipes.size)
            recipes.forEach { recipe ->
                val existing = recipeIndicesByIdentity[recipe]
                if (existing != null) {
                    indices.add(existing)
                } else {
                    val newIndex = recipeList.size
                    recipeList.add(recipe)
                    recipeIndicesByIdentity[recipe] = newIndex
                    indices.add(newIndex)
                }
            }
            outputs[outputId] = indices
        }

        return RecipeAssetsCache(
            formatVersion = 1,
            translations = assets.translations,
            uniqueItems = assets.uniqueItems,
            recipes = recipeList,
            recipesByOutput = outputs
        )
    }

    private fun readCompactMetadata(
        file: File,
        folderName: String,
        sourceArchiveFile: File,
        imageIndexFile: File,
        imageCacheDir: File
    ): RecipeAssets {
        val cache: RecipeAssetsCache = if (file.name.endsWith(".bin", ignoreCase = true)) {
            file.inputStream().use { input ->
                DataInputStream(BufferedInputStream(input, 1 shl 20)).use { dataIn ->
                    readCompactMetadataBinary(dataIn)
                }
            }
        } else {
            file.inputStream().use { input ->
                JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    internedGson.fromJson(reader, RecipeAssetsCache::class.java)
                }
            }
        } ?: throw JsonParseException("Metadata cache is empty")

        if (cache.formatVersion <= 0) {
            throw JsonParseException("Unsupported metadata cache version")
        }

        val recipes = cache.recipes
        val recipesByOutput: Map<String, List<RecipeDump>> = cache.recipesByOutput.mapValues { entry ->
            entry.value.mapNotNull { index -> recipes.getOrNull(index) }
        }

        return RecipeAssets(
            libraryFolderName = folderName,
            sourceArchiveFile = sourceArchiveFile,
            imageIndexFile = imageIndexFile,
            imageCacheDir = imageCacheDir,
            translations = cache.translations,
            uniqueItems = cache.uniqueItems,
            recipesByOutput = recipesByOutput
        )
    }

    private fun writeCompactMetadataBinary(out: DataOutputStream, cache: RecipeAssetsCache) {
        out.writeInt(METADATA_MAGIC)
        out.writeInt(METADATA_VERSION)

        out.writeInt(cache.translations.size)
        cache.translations.forEach { (key, value) ->
            writeSizedString(out, key)
            writeSizedString(out, value)
        }

        out.writeInt(cache.uniqueItems.size)
        cache.uniqueItems.forEach { itemId ->
            writeSizedString(out, itemId)
        }

        out.writeInt(cache.recipes.size)
        cache.recipes.forEach { recipe ->
            writeSizedString(out, recipe.recipeType)
            writeSizedString(out, recipe.recipeClass)
            out.writeInt(recipe.slots.size)
            recipe.slots.forEach { slot ->
                writeSizedString(out, slot.role)
                out.writeInt(slot.ingredients.size)
                slot.ingredients.forEach { ing ->
                    var flags = 0
                    if (ing.type != null) flags = flags or 1
                    if (ing.id != null) flags = flags or 2
                    if (ing.item != null) flags = flags or 4
                    if (ing.count != null) flags = flags or 8
                    if (ing.fluid != null) flags = flags or 16
                    if (ing.amount != null) flags = flags or 32
                    if (ing.nbt != null) flags = flags or 64
                    if (ing.raw != null) flags = flags or 128
                    if (ing.unknown != null) flags = flags or 256

                    out.writeInt(flags)
                    if ((flags and 1) != 0) writeSizedString(out, ing.type!!)
                    if ((flags and 2) != 0) writeSizedString(out, ing.id!!)
                    if ((flags and 4) != 0) writeSizedString(out, ing.item!!)
                    if ((flags and 8) != 0) out.writeInt(ing.count!!)
                    if ((flags and 16) != 0) writeSizedString(out, ing.fluid!!)
                    if ((flags and 32) != 0) out.writeLong(ing.amount!!)
                    if ((flags and 64) != 0) writeSizedString(out, ing.nbt!!)
                    if ((flags and 128) != 0) writeSizedString(out, ing.raw!!)
                    if ((flags and 256) != 0) writeSizedString(out, ing.unknown!!)
                }
            }
        }

        out.writeInt(cache.recipesByOutput.size)
        cache.recipesByOutput.forEach { (outputId, indices) ->
            writeSizedString(out, outputId)
            out.writeInt(indices.size)
            indices.forEach { index -> out.writeInt(index) }
        }
    }

    private fun readCompactMetadataBinary(input: DataInputStream): RecipeAssetsCache {
        val magic = input.readInt()
        if (magic != METADATA_MAGIC) {
            throw JsonParseException("Invalid metadata magic")
        }

        val version = input.readInt()
        if (version != METADATA_VERSION) {
            throw JsonParseException("Unsupported metadata version $version")
        }

        val translationsSize = input.readInt()
        val translations = LinkedHashMap<String, String>(translationsSize.coerceAtLeast(0))
        repeat(translationsSize) {
            val key = readSizedString(input)
            val value = readSizedString(input)
            translations[key] = value
        }

        val uniqueItemsSize = input.readInt()
        val uniqueItems = ArrayList<String>(uniqueItemsSize.coerceAtLeast(0))
        repeat(uniqueItemsSize) {
            uniqueItems += readSizedString(input)
        }

        val recipeCount = input.readInt()
        val recipes = ArrayList<RecipeDump>(recipeCount.coerceAtLeast(0))
        repeat(recipeCount) {
            val recipeType = readSizedString(input)
            val recipeClass = readSizedString(input)
            val slotCount = input.readInt()
            val slots = ArrayList<RecipeSlot>(slotCount.coerceAtLeast(0))

            repeat(slotCount) {
                val role = readSizedString(input)
                val ingredientCount = input.readInt()
                val ingredients = ArrayList<Ingredient>(ingredientCount.coerceAtLeast(0))

                repeat(ingredientCount) {
                    val flags = input.readInt()
                    val type = if ((flags and 1) != 0) readSizedString(input) else null
                    val id = if ((flags and 2) != 0) readSizedString(input) else null
                    val item = if ((flags and 4) != 0) readSizedString(input) else null
                    val count = if ((flags and 8) != 0) input.readInt() else null
                    val fluid = if ((flags and 16) != 0) readSizedString(input) else null
                    val amount = if ((flags and 32) != 0) input.readLong() else null
                    val nbt = if ((flags and 64) != 0) readSizedString(input) else null
                    val raw = if ((flags and 128) != 0) readSizedString(input) else null
                    val unknown = if ((flags and 256) != 0) readSizedString(input) else null
                    ingredients += Ingredient(type, id, item, count, fluid, amount, nbt, raw, unknown)
                }

                slots += RecipeSlot(role, ingredients)
            }

            recipes += RecipeDump(recipeType, recipeClass, slots)
        }

        val outputsSize = input.readInt()
        val recipesByOutput = LinkedHashMap<String, List<Int>>(outputsSize.coerceAtLeast(0))
        repeat(outputsSize) {
            val outputId = readSizedString(input)
            val indicesCount = input.readInt()
            val indices = ArrayList<Int>(indicesCount.coerceAtLeast(0))
            repeat(indicesCount) {
                indices += input.readInt()
            }
            recipesByOutput[outputId] = indices
        }

        return RecipeAssetsCache(
            formatVersion = METADATA_VERSION,
            translations = translations,
            uniqueItems = uniqueItems,
            recipes = recipes,
            recipesByOutput = recipesByOutput
        )
    }

    private fun writeSizedString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readSizedString(input: DataInputStream): String {
        val size = input.readInt()
        if (size < 0) throw JsonParseException("Negative string size in metadata")
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun readLegacyMetadata(
        file: File,
        folderName: String,
        sourceArchiveFile: File,
        imageIndexFile: File,
        imageCacheDir: File
    ): RecipeAssets {
        val legacy: RecipeAssets = file.inputStream().use { input ->
            JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                internedGson.fromJson(reader, RecipeAssets::class.java)
            }
        } ?: throw JsonParseException("Legacy metadata is empty")

        return RecipeAssets(
            libraryFolderName = folderName,
            sourceArchiveFile = sourceArchiveFile,
            imageIndexFile = imageIndexFile,
            imageCacheDir = imageCacheDir,
            translations = legacy.translations,
            uniqueItems = legacy.uniqueItems,
            recipesByOutput = legacy.recipesByOutput
        )
    }

    private data class RecipeManifest(
        val chunkDirectory: String? = null,
        val types: List<RecipeManifestType> = emptyList()
    )

    private data class RecipeManifestType(
        val file: String? = null
    )

    private data class RecipeAssetsCache(
        val formatVersion: Int,
        val translations: Map<String, String>,
        val uniqueItems: List<String>,
        val recipes: List<RecipeDump>,
        val recipesByOutput: Map<String, List<Int>>
    )

    private data class WriteResult(
        val success: Boolean,
        val error: String?
    )
}
