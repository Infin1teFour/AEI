package me.infin1te.aei

import java.io.File

data class RecipeAssets(
    val translations: Map<String, String>,
    val imagePaths: Map<String, File>,
    val originalImagePaths: Map<String, String>,
    val uniqueItems: List<String>,
    val recipesByOutput: Map<String, List<RecipeDump>>
) {
    // Helper to get translation regardless of case or type prefix
    fun getTranslation(id: String): String {
        return translations[id] 
            ?: translations[id.lowercase()] 
            ?: translations[id.substringAfter("/")] 
            ?: translations[id.substringAfter("/").lowercase()]
            ?: id
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
