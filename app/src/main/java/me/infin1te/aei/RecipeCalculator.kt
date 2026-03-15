package me.infin1te.aei

class RecipeCalculator(recipes: List<RecipeDump>) {

    private val recipesByOutput: Map<String, List<RecipeDump>> = recipes.flatMap { r ->
        r.slots
            .filter { it.role == "OUTPUT" }
            .flatMap { it.ingredients.mapNotNull { ing -> 
                val id = ing.item ?: ing.fluid
                if (id != null) id to r else null
            } }
    }.groupBy({ it.first }, { it.second })

    fun totalCost(itemId: String, quantity: Int = 1, depth: Int = 0, visited: Set<String> = emptySet()): Map<String, Int> {
        // Prevent recursion loops and stack overflows
        if (depth > 20 || visited.contains(itemId)) {
            return mapOf(itemId to quantity)
        }

        val recipeList = recipesByOutput[itemId] ?: return mapOf(itemId to quantity)
        val recipe = recipeList.first()

        val costMap = mutableMapOf<String, Int>()
        val newVisited = visited + itemId

        for (slot in recipe.slots.filter { it.role == "INPUT" }) {
            for (ing in slot.ingredients) {
                val ingId = ing.item ?: ing.fluid ?: continue
                val count = ing.count ?: ing.amount ?: 1
                
                val subCost = totalCost(ingId, count * quantity, depth + 1, newVisited)
                subCost.forEach { (key, value) ->
                    costMap[key] = costMap.getOrDefault(key, 0) + value
                }
            }
        }
        return if (costMap.isEmpty()) mapOf(itemId to quantity) else costMap
    }
}
