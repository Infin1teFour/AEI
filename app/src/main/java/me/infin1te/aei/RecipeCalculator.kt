package me.infin1te.aei

class RecipeCalculator(recipes: List<RecipeDump>) {

    private val recipesByOutput: Map<String, List<RecipeDump>> = recipes.flatMap { r ->
        r.slots
            .filter { it.role == "OUTPUT" }
            .flatMap { it.ingredients.mapNotNull { ing -> 
                val id = ing.getResolvedId()
                if (id != null) id to r else null
            } }
    }.groupBy({ it.first }, { it.second })

    fun totalCost(itemId: String, quantity: Long = 1L, depth: Int = 0, visited: Set<String> = emptySet()): Map<String, Long> {
        // Prevent recursion loops and stack overflows
        if (depth > 20 || visited.contains(itemId)) {
            return mapOf(itemId to quantity)
        }

        val recipeList = recipesByOutput[itemId] ?: return mapOf(itemId to quantity)
        val recipe = recipeList.first()

        val costMap = mutableMapOf<String, Long>()
        val newVisited = visited + itemId

        for (slot in recipe.slots.filter { it.role == "INPUT" }) {
            for (ing in slot.ingredients) {
                val ingId = ing.getResolvedId() ?: continue
                val count = ing.count?.toLong() ?: ing.amount ?: 1L
                
                val subCost = totalCost(ingId, count * quantity, depth + 1, newVisited)
                subCost.forEach { (key, value) ->
                    costMap[key] = costMap.getOrDefault(key, 0L) + value
                }
            }
        }
        return if (costMap.isEmpty()) mapOf(itemId to quantity) else costMap
    }
}
