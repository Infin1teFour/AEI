package me.infin1te.aei

import java.io.File

data class RecipeAssets(
    val translations: Map<String, String>,
    val imagePaths: Map<String, File>,
    val originalImagePaths: Map<String, String>,
    val uniqueItems: List<String>,
    val recipesByOutput: Map<String, List<RecipeDump>>
) {
    // Translation keys are uniform; stack/type prefixes should not affect lookup.
    fun getTranslation(id: String): String {
        val lower = id.lowercase()
        val normalized = normalizeTranslationId(lower)

        translations[id]?.let { return it }
        translations[lower]?.let { return it }
        translations[normalized]?.let { return it }

        return id
    }

    private fun normalizeTranslationId(id: String): String {
        var current = id

        while (true) {
            val slashIndex = current.indexOf('/')
            if (slashIndex <= 0) return current

            val prefix = current.substring(0, slashIndex)
            // Translation keys are namespaced IDs (modid:path).
            // Strip any leading non-namespaced type segment like fluid_stack/, mod_stack/, etc.
            if (':' in prefix) {
                return current
            }

            current = current.substring(slashIndex + 1)
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
    val unknown: String? = null
) {
    fun getResolvedId(): String? {
        val baseId = id ?: item ?: fluid ?: return null
        val result = when {
            type == "fluid" -> "fluid_stack/$baseId"
            type != null && type != "item" && type != "block" -> "$type/$baseId"
            else -> baseId
        }
        return result.lowercase()
    }
}
