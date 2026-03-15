package me.infin1te.aei

import android.graphics.Bitmap

data class RecipeAssets(
    val recipes: List<RecipeDump>,
    val translations: Map<String, String>,
    val images: Map<String, Bitmap>
)

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
    val item: String? = null,
    val count: Int? = null,
    val fluid: String? = null,
    val amount: Int? = null,
    val nbt: String? = null,
    val unknown: String? = null
)
