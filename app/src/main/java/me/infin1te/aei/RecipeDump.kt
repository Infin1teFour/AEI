package me.infin1te.aei

import java.io.File

data class RecipeAssets(
    val libraryFolderName: String,
    val sourceArchiveFile: File,
    val imageIndexFile: File,
    val imageCacheDir: File,
    val translations: Map<String, String>,
    val uniqueItems: List<String>,
    val recipesByOutput: Map<String, List<RecipeDump>>
) {
    fun getTranslation(id: String): String {
        val lower = id.lowercase()
        translations[lower]?.let { return it }

        legacyAliasCandidates(lower).forEach { alias ->
            translations[alias]?.let { return it }
        }

        return id
    }

    private fun legacyAliasCandidates(id: String): Sequence<String> = sequence {
        if (id.startsWith("fluid/")) {
            yield("fluid_stack/${id.substringAfter('/')}")
        }
        if (id.startsWith("fluid_stack/")) {
            yield("fluid/${id.substringAfter('/')}")
        }
    }
}

data class RecipeDump(
    val recipeType: String,
    val recipeClass: String,
    val slots: List<RecipeSlot>
)

data class RecipeSlot(
    val role: String, // INPUT or OUTPUT
    val ingredients: List<Ingredient>
)

data class Ingredient(
    val type: String? = null,
    val id: String? = null,
    val item: String? = null,
    val count: Int? = null,
    val fluid: String? = null,
    val amount: Long? = null,
    val nbt: String? = null,
    val raw: String? = null,
    val unknown: String? = null
) {
    fun getResolvedId(): String? {
        val baseId = (id ?: item ?: fluid)?.lowercase() ?: return null
        val normalizedType = type?.trim()?.lowercase()
        val result = when {
            normalizedType == "fluid" -> "fluid/$baseId"
            normalizedType != null && normalizedType != "item" && normalizedType != "block" -> "$normalizedType/$baseId"
            else -> baseId
        }
        return result
    }
}
