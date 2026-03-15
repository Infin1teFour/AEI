package me.infin1te.aei

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RecipeLoader {

    suspend fun loadRecipes(context: Context): List<RecipeDump> = withContext(Dispatchers.IO) {
        val jsonString = context.assets.open("jei_recipe_dump.json")
            .bufferedReader()
            .use { it.readText() }

        val type = object : TypeToken<List<RecipeDump>>() {}.type
        Gson().fromJson<List<RecipeDump>>(jsonString, type)
    }
}
