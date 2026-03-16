package me.infin1te.aei

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed class Screen(val title: String, val icon: ImageVector) {
    object Recipes : Screen("Recipes", Icons.AutoMirrored.Filled.List)
    object Calculator : Screen("Calculator", Icons.Default.Calculate)
    object WirelessTerminal : Screen("Wireless Terminal", Icons.Default.Router)
    object Settings : Screen("Settings", Icons.Default.Settings)
}

enum class RecipeSortOption {
    ModidAscii, NameAscii, NameReverse
}

enum class WirelessSortOption {
    CountDescending, CountAscending, NameAscii, NameReverse
}

class MainActivity : ComponentActivity() {
    data class CalculatorTarget(
        val itemId: String,
        val quantity: Long = 1L
    )

    data class RecipeTreeNode(
        val itemId: String,
        val quantity: Long,
        val recipeType: String?,
        val children: List<RecipeTreeNode>,
        val cycleDetected: Boolean = false
    )

    data class WirelessApiResponse(
        val grids: List<WirelessApiGrid> = emptyList()
    )

    data class WirelessApiGrid(
        val stacks: List<WirelessApiStack> = emptyList()
    )

    data class WirelessApiStack(
        val id: String = "",
        val type: String? = null,
        val name: String? = null,
        val amount: Long = 0L
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val isDark = isSystemInDarkTheme()
            val colors = if (isDark) {
                darkColors(primary = Color(0xFFBB86FC), background = Color(0xFF121212), surface = Color(0xFF1E1E1E))
            } else {
                lightColors(primary = Color(0xFF6200EE), background = Color(0xFFF5F5F5), surface = Color.White)
            }

            MaterialTheme(colors = colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
                    AppContent()
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AssetManager.trimBitmapMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AssetManager.clearBitmapMemory()
    }

    private fun formatLargeNumber(amount: Long): String {
        return when {
            amount >= 1_000_000_000 -> "${amount / 1_000_000_000}b"
            amount >= 1_000_000 -> "${amount / 1_000_000}m"
            amount >= 1_000 -> "${amount / 1_000}k"
            else -> amount.toString()
        }
    }

    private fun sortRecipeIds(
        ids: List<String>,
        assets: RecipeAssets,
        option: RecipeSortOption
    ): List<String> {
        return when (option) {
            RecipeSortOption.ModidAscii -> ids.sortedWith(compareBy<String> { id ->
                id.substringBefore(":")
            }.thenBy { id ->
                assets.getTranslation(id).lowercase()
            })
            RecipeSortOption.NameAscii -> ids.sortedBy { id ->
                assets.getTranslation(id).lowercase()
            }
            RecipeSortOption.NameReverse -> ids.sortedByDescending { id ->
                assets.getTranslation(id).lowercase()
            }
        }
    }

    private fun sortWirelessStacks(
        stacks: List<WirelessApiStack>,
        option: WirelessSortOption
    ): List<WirelessApiStack> {
        return when (option) {
            WirelessSortOption.CountDescending -> stacks.sortedByDescending { it.amount }
            WirelessSortOption.CountAscending -> stacks.sortedBy { it.amount }
            WirelessSortOption.NameAscii -> stacks.sortedBy { it.name?.lowercase() ?: it.id }
            WirelessSortOption.NameReverse -> stacks.sortedByDescending { it.name?.lowercase() ?: it.id }
        }
    }

    @Composable
    fun AppContent() {
        val scope = rememberCoroutineScope()
        var assets by remember { mutableStateOf<RecipeAssets?>(null) }
        var navigationStack by remember { mutableStateOf(listOf<String>()) }
        val currentItemId = navigationStack.lastOrNull()

        var debugItemId by remember { mutableStateOf<String?>(null) }
        var searchQuery by remember { mutableStateOf("") }
        var currentScreen by remember { mutableStateOf<Screen>(Screen.Recipes) }
        var importedFolders by remember { mutableStateOf<List<File>>(emptyList()) }
        var isIndexing by remember { mutableStateOf(false) }
        var indexingProgress by remember { mutableFloatStateOf(0f) }
        var indexingMessage by remember { mutableStateOf<String?>(null) }
        var isThumbnailPreloading by remember { mutableStateOf(false) }
        var thumbnailPreloadProgress by remember { mutableFloatStateOf(0f) }
        var thumbnailPreloadMessage by remember { mutableStateOf("Preloading thumbnails") }
        var calculatorTargets by remember { mutableStateOf<List<CalculatorTarget>>(emptyList()) }
        var calculatorRecipeSelections by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
        var calculatorShowTree by remember { mutableStateOf(false) }
        var pendingCalculatorSelectionItemId by remember { mutableStateOf<String?>(null) }
        var wirelessHost by remember { mutableStateOf("localhost") }
        var wirelessLoading by remember { mutableStateOf(false) }
        var wirelessError by remember { mutableStateOf<String?>(null) }
        var wirelessStacks by remember { mutableStateOf<List<WirelessApiStack>>(emptyList()) }
        var recipeSortOption by remember { mutableStateOf(RecipeSortOption.ModidAscii) }
        var wirelessSortOption by remember { mutableStateOf(WirelessSortOption.CountDescending) }
        
        // Debounced sort options to prevent rapid re-renders during fast clicking
        var debouncedRecipeSortOption by remember { mutableStateOf(RecipeSortOption.ModidAscii) }
        var debouncedWirelessSortOption by remember { mutableStateOf(WirelessSortOption.CountDescending) }
        
        LaunchedEffect(recipeSortOption) {
            delay(150)
            AssetManager.clearBitmapMemory()
            debouncedRecipeSortOption = recipeSortOption
        }
        
        LaunchedEffect(wirelessSortOption) {
            delay(150)
            debouncedWirelessSortOption = wirelessSortOption
        }
        
        var showTagRecipes by remember { mutableStateOf(false) }
        var isDebugMode by remember { mutableStateOf(false) }
        var showGridTextures by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            importedFolders = AssetManager.listImportedFolders(this@MainActivity)
            val lastFolder = AssetManager.getLastLoadedFolder(this@MainActivity)
            if (lastFolder != null) {
                scope.launch {
                    isIndexing = true
                    indexingMessage = "Loading library"
                    assets = AssetManager.loadFromUnpacked(
                        this@MainActivity,
                        lastFolder,
                        onProgress = { progress: Float ->
                            indexingProgress = progress
                        },
                        onStage = { stage ->
                            indexingMessage = stage
                        },
                        onThumbnailPreloadUpdate = { update ->
                            isThumbnailPreloading = update.active
                            thumbnailPreloadProgress = update.progress
                            thumbnailPreloadMessage = update.message
                        }
                    )
                    if (assets != null) {
                        calculatorTargets = emptyList()
                        calculatorRecipeSelections = emptyMap()
                    }
                    isIndexing = false
                    indexingMessage = null
                    isThumbnailPreloading = false
                }
            }
        }

        BackHandler(enabled = currentItemId != null) {
            navigationStack = navigationStack.dropLast(1)
        }

        val pickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let {
                scope.launch {
                    isIndexing = true
                    indexingProgress = 0f
                    indexingMessage = "Importing and indexing .aei"
                    val imported = AssetManager.importAeiFile(
                        this@MainActivity,
                        it,
                        onProgress = { progress: Float ->
                            indexingProgress = progress
                        },
                        onStage = { stage ->
                            indexingMessage = stage
                        },
                        onThumbnailPreloadUpdate = { update ->
                            isThumbnailPreloading = update.active
                            thumbnailPreloadProgress = update.progress
                            thumbnailPreloadMessage = update.message
                        }
                    )
                    if (imported != null) {
                        assets = imported
                        importedFolders = AssetManager.listImportedFolders(this@MainActivity)
                        calculatorTargets = emptyList()
                        calculatorRecipeSelections = emptyMap()
                    }
                    isIndexing = false
                    indexingMessage = null
                    isThumbnailPreloading = false
                }
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                Surface(elevation = 4.dp) {
                    Column(modifier = Modifier.statusBarsPadding()) {
                        TopAppBar(
                            title = { 
                                if (currentItemId != null && assets != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        SmallItemIcon(
                                            assets = assets,
                                            itemId = currentItemId,
                                            sizeDp = 18,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(assets!!.getTranslation(currentItemId), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                } else {
                                    Text(currentScreen.title) 
                                }
                            },
                            navigationIcon = if (currentItemId != null) {
                                {
                                    IconButton(onClick = { navigationStack = navigationStack.dropLast(1) }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                }
                            } else null,
                            elevation = 0.dp,
                            actions = {
                                if (currentScreen == Screen.Recipes) {
                                    IconButton(onClick = { 
                                        assets = null
                                        navigationStack = listOf()
                                        searchQuery = "" 
                                    }) {
                                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Library")
                                    }
                                    TextButton(onClick = { pickerLauncher.launch("*/*") }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                                        Text("IMPORT")
                                    }
                                }
                            }
                        )
                        if (currentScreen == Screen.Recipes && assets != null && currentItemId == null) {
                            SearchField(searchQuery) { searchQuery = it }
                        }
                    }
                }
            },
            bottomBar = {
                BottomNavigation(backgroundColor = MaterialTheme.colors.surface) {
                    val items = listOf(Screen.Recipes, Screen.Calculator, Screen.WirelessTerminal, Screen.Settings)
                    items.forEach { screen ->
                        BottomNavigationItem(
                            icon = { Icon(screen.icon, contentDescription = null) },
                            label = { Text(screen.title) },
                            selected = currentScreen == screen,
                            onClick = { currentScreen = screen },
                            selectedContentColor = MaterialTheme.colors.primary,
                            unselectedContentColor = Color.Gray
                        )
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (currentScreen) {
                    is Screen.Recipes -> {
                        if (assets == null) {
                            ImportLibrary(
                                importedFolders = importedFolders,
                                onFolderSelected = { folder ->
                                    scope.launch {
                                        isIndexing = true
                                        indexingProgress = 0f
                                        indexingMessage = "Loading ${folder.name}"
                                        assets = AssetManager.loadFromUnpacked(
                                            this@MainActivity,
                                            folder.name,
                                            onProgress = { progress: Float ->
                                                indexingProgress = progress
                                            },
                                            onStage = { stage ->
                                                indexingMessage = stage
                                            },
                                            onThumbnailPreloadUpdate = { update ->
                                                isThumbnailPreloading = update.active
                                                thumbnailPreloadProgress = update.progress
                                                thumbnailPreloadMessage = update.message
                                            }
                                        )
                                        if (assets != null) {
                                            calculatorTargets = emptyList()
                                            calculatorRecipeSelections = emptyMap()
                                        }
                                        isIndexing = false
                                        indexingMessage = null
                                        isThumbnailPreloading = false
                                    }
                                },
                                onFolderDelete = { folder ->
                                    AssetManager.deleteUnpackedFolder(this@MainActivity, folder)
                                    importedFolders = AssetManager.listImportedFolders(this@MainActivity)
                                },
                                onImportClick = { pickerLauncher.launch("*/*") }
                            )
                        } else {
                            val normalizedQuery = searchQuery.trim().lowercase()
                            val filteredIds = remember(normalizedQuery, assets, showTagRecipes) {
                                val allItems = assets!!.uniqueItems
                                if (normalizedQuery.isBlank()) {
                                    allItems
                                } else {
                                    allItems.filter { id ->
                                        id.contains(normalizedQuery) ||
                                            assets!!.getTranslation(id).lowercase().contains(normalizedQuery)
                                    }
                                }
                            }

                            val sortedIds = remember(filteredIds, debouncedRecipeSortOption, assets) {
                                sortRecipeIds(filteredIds, assets!!, debouncedRecipeSortOption)
                            }

                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Sort:", style = MaterialTheme.typography.caption)
                                    Spacer(Modifier.width(6.dp))
                                    Box {
                                        var showDropdown by remember { mutableStateOf(false) }
                                        Button(onClick = { showDropdown = true }, modifier = Modifier.height(32.dp)) {
                                            Text(when (recipeSortOption) {
                                                RecipeSortOption.ModidAscii -> "Modid + Name"
                                                RecipeSortOption.NameAscii -> "Name A-Z"
                                                RecipeSortOption.NameReverse -> "Name Z-A"
                                            }, style = MaterialTheme.typography.caption)
                                        }
                                        DropdownMenu(expanded = showDropdown, onDismissRequest = { showDropdown = false }) {
                                            DropdownMenuItem(onClick = { 
                                                recipeSortOption = RecipeSortOption.ModidAscii
                                                showDropdown = false
                                            }) {
                                                Text("Modid + Name")
                                            }
                                            DropdownMenuItem(onClick = { 
                                                recipeSortOption = RecipeSortOption.NameAscii
                                                showDropdown = false
                                            }) {
                                                Text("Name A-Z")
                                            }
                                            DropdownMenuItem(onClick = { 
                                                recipeSortOption = RecipeSortOption.NameReverse
                                                showDropdown = false
                                            }) {
                                                Text("Name Z-A")
                                            }
                                        }
                                    }
                                }

                                JeiPagedIconGrid(
                                    itemIds = sortedIds,
                                    assets = assets!!,
                                    showTextures = showGridTextures,
                                    isDebugMode = isDebugMode,
                                    onItemAction = { id, isLongClick ->
                                        if (isLongClick && isDebugMode) {
                                            debugItemId = id
                                        } else {
                                            navigationStack = navigationStack + id
                                        }
                                    }
                                )
                            }

                            if (currentItemId != null) {
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colors.background
                                ) {
                                RecipeView(
                                    itemId = currentItemId,
                                    assets = assets!!,
                                    isSelectingForCalculator = (pendingCalculatorSelectionItemId == currentItemId),
                                    onAddToCalculator = { outputId, recipe ->
                                        val recipeIndex = assets!!.recipesByOutput[outputId]
                                            ?.indexOfFirst { it == recipe }
                                            ?.takeIf { it >= 0 } ?: 0
                                        if (pendingCalculatorSelectionItemId == outputId) {
                                            calculatorRecipeSelections = calculatorRecipeSelections + (outputId to recipeIndex)
                                            pendingCalculatorSelectionItemId = null
                                            if (navigationStack.lastOrNull() == outputId) {
                                                navigationStack = navigationStack.dropLast(1)
                                            }
                                            currentScreen = Screen.Calculator
                                        } else {
                                            calculatorTargets = calculatorTargets
                                                .filterNot { it.itemId == outputId } + CalculatorTarget(outputId, 1L)
                                            calculatorRecipeSelections = calculatorRecipeSelections + (outputId to recipeIndex)
                                        }
                                    },
                                    onIngredientClick = { targetId ->
                                        val normalized = targetId.lowercase()
                                        if (normalized != "unknown" && assets!!.recipesByOutput.containsKey(targetId)) {
                                            navigationStack = navigationStack + targetId
                                        }
                                    }
                                )
                                }
                            }
                        }
                    }
                    is Screen.Calculator -> {
                        if (assets == null) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Load a library first to use calculator.", color = Color.Gray)
                            }
                        } else {
                            CalculatorScreen(
                                assets = assets!!,
                                targets = calculatorTargets,
                                selections = calculatorRecipeSelections,
                                showTree = calculatorShowTree,
                                onShowTreeChange = { calculatorShowTree = it },
                                onIngredientClick = { ingredientId ->
                                    pendingCalculatorSelectionItemId = ingredientId
                                    currentScreen = Screen.Recipes
                                    if (navigationStack.lastOrNull() != ingredientId) {
                                        navigationStack = navigationStack + ingredientId
                                    }
                                },
                                onAddCurrentItem = {
                                    val id = currentItemId
                                    if (id != null) {
                                        calculatorTargets = calculatorTargets
                                            .filterNot { it.itemId == id } + CalculatorTarget(id, 1L)
                                    }
                                },
                                onQuantityChange = { itemId, quantity ->
                                    calculatorTargets = calculatorTargets.map {
                                        if (it.itemId == itemId) it.copy(quantity = quantity.coerceAtLeast(1L)) else it
                                    }
                                },
                                onRemoveTarget = { itemId ->
                                    calculatorTargets = calculatorTargets.filterNot { it.itemId == itemId }
                                },
                                onClearPlan = {
                                    calculatorTargets = emptyList()
                                    calculatorRecipeSelections = emptyMap()
                                }
                            )
                        }
                    }
                    is Screen.Settings -> {
                        SettingsScreen(
                            showTagRecipes = showTagRecipes,
                            isDebugMode = isDebugMode,
                            showGridTextures = showGridTextures,
                            onShowTagRecipesChange = { showTagRecipes = it },
                            onIsDebugModeChange = { isDebugMode = it },
                            onShowGridTexturesChange = { enabled ->
                                showGridTextures = enabled
                                if (!enabled) {
                                    AssetManager.clearBitmapMemory()
                                }
                            }
                        )
                    }
                    is Screen.WirelessTerminal -> {
                        WirelessTerminalScreen(
                            assets = assets,
                            host = wirelessHost,
                            loading = wirelessLoading,
                            error = wirelessError,
                            stacks = wirelessStacks,
                            onHostChange = { wirelessHost = it },
                            onFetch = {
                                scope.launch {
                                    wirelessLoading = true
                                    wirelessError = null
                                    val result = fetchWirelessStacks(wirelessHost)
                                    result.onSuccess { stacks ->
                                        wirelessStacks = stacks
                                    }.onFailure { error ->
                                        wirelessError = error.message ?: "Failed to fetch wireless terminal data"
                                    }
                                    wirelessLoading = false
                                }
                            },
                            sortOption = debouncedWirelessSortOption,
                            onSortOptionChange = { wirelessSortOption = it }
                        )
                    }
                }

                if (isIndexing) {
                    IndexingBanner(
                        progress = indexingProgress,
                        message = indexingMessage ?: "Indexing library",
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp)
                    )
                }

                if (isThumbnailPreloading) {
                    ThumbnailPreloadBanner(
                        progress = thumbnailPreloadProgress,
                        message = thumbnailPreloadMessage,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp)
                            .padding(bottom = if (isIndexing) 94.dp else 12.dp)
                    )
                }

                debugItemId?.let { id ->
                    ItemDebugDialog(id, assets!!, onDismiss = { debugItemId = null })
                }
            }
        }
    }

    @Composable
    fun RecipeView(
        itemId: String,
        assets: RecipeAssets,
        isSelectingForCalculator: Boolean,
        onAddToCalculator: (String, RecipeDump) -> Unit,
        onIngredientClick: (String) -> Unit
    ) {
        val recipes = remember(itemId, assets) { assets.recipesByOutput[itemId] ?: emptyList() }
        val recipesByType = remember(recipes) { recipes.groupBy { it.recipeType } }
        val types = remember(recipesByType) { recipesByType.keys.toList() }
        
        var selectedTypeIndex by remember(itemId) { mutableIntStateOf(0) }
        
        if (recipes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No recipes found for this item.", color = Color.Gray)
            }
            return
        }

        Column(modifier = Modifier.fillMaxSize()) {
            if (types.size > 1) {
                ScrollableTabRow(
                    selectedTabIndex = selectedTypeIndex,
                    backgroundColor = MaterialTheme.colors.surface,
                    contentColor = MaterialTheme.colors.primary,
                    edgePadding = 16.dp
                ) {
                    types.forEachIndexed { index, type ->
                        Tab(
                            selected = selectedTypeIndex == index,
                            onClick = { selectedTypeIndex = index },
                            text = { Text(type.substringAfterLast(":").replace("_", " ").uppercase(), fontSize = 12.sp) }
                        )
                    }
                }
            } else if (types.isNotEmpty()) {
                Surface(elevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = types[0].substringAfterLast(":").replace("_", " ").uppercase(),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.subtitle2,
                        color = MaterialTheme.colors.primary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            val selectedType = types.getOrNull(selectedTypeIndex) ?: types.firstOrNull()
            if (selectedType == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No recipes found for this item.", color = Color.Gray)
                }
                return
            }

            val currentTypeRecipes = recipesByType[selectedType] ?: emptyList()
            var currentRecipeIndex by remember(itemId, selectedType) { mutableIntStateOf(0) }

            Column(modifier = Modifier.weight(1f).padding(16.dp).verticalScroll(rememberScrollState())) {
                if (currentTypeRecipes.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { if (currentRecipeIndex > 0) currentRecipeIndex-- }, enabled = currentRecipeIndex > 0) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                        Text("Recipe ${currentRecipeIndex + 1} of ${currentTypeRecipes.size}", style = MaterialTheme.typography.caption)
                        IconButton(onClick = { if (currentRecipeIndex < currentTypeRecipes.size - 1) currentRecipeIndex++ }, enabled = currentRecipeIndex < currentTypeRecipes.size - 1) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                val recipe = currentTypeRecipes.getOrNull(currentRecipeIndex)
                if (recipe == null) {
                    Text("No recipes found for this type.", color = Color.Gray)
                    return@Column
                }

                Button(
                    onClick = { onAddToCalculator(itemId, recipe) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Calculate, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isSelectingForCalculator) "Use This Recipe In Calculator" else "Add This Recipe To Calculator")
                }
                Spacer(Modifier.height(12.dp))

                val isStandardCraftingRecipe = remember(recipe) {
                    val type = recipe.recipeType.lowercase()
                    val recipeClass = recipe.recipeClass.lowercase()
                    (type.contains("crafting") || recipeClass.contains("craft"))
                }

                if (isStandardCraftingRecipe) {
                    CraftingRecipeLayout(recipe, assets, onIngredientClick)
                } else {
                    Text("Output", style = MaterialTheme.typography.h6)
                    recipe.slots.filter { it.role == "OUTPUT" }.forEach { slot ->
                        CyclingIngredientRow(slot.ingredients, assets, onIngredientClick)
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("Input", style = MaterialTheme.typography.h6)

                    val inputSlots = recipe.slots.filter { it.role == "INPUT" }
                    if (inputSlots.isEmpty()) {
                        Text("No inputs required", color = Color.Gray, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    } else {
                        inputSlots.forEach { slot ->
                            CyclingIngredientRow(slot.ingredients, assets, onIngredientClick)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun CraftingRecipeLayout(recipe: RecipeDump, assets: RecipeAssets, onIngredientClick: (String) -> Unit) {
        val outputIngredients = recipe.slots.filter { it.role == "OUTPUT" }.flatMap { it.ingredients }
        val inputSlots = recipe.slots.filter { it.role == "INPUT" }
        val gridColumns = if (inputSlots.size <= 4) 2 else 3

        Text("Output", style = MaterialTheme.typography.h6)
        if (outputIngredients.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                CraftingIngredientCell(
                    ingredients = outputIngredients,
                    assets = assets,
                    onIngredientClick = onIngredientClick,
                    modifier = Modifier.size(120.dp)
                )
            }
        } else {
            Text("No output found", color = Color.Gray, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        }

        Spacer(Modifier.height(10.dp))
        Text("Grid", style = MaterialTheme.typography.h6)

        if (inputSlots.isEmpty()) {
            Text("No inputs required", color = Color.Gray, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            return
        }

        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            inputSlots.chunked(gridColumns).forEach { rowSlots ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    rowSlots.forEach { slot ->
                        CraftingIngredientCell(
                            ingredients = slot.ingredients,
                            assets = assets,
                            onIngredientClick = onIngredientClick,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    repeat(gridColumns - rowSlots.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    @Composable
    fun CraftingIngredientCell(
        ingredients: List<Ingredient>,
        assets: RecipeAssets,
        onIngredientClick: (String) -> Unit,
        modifier: Modifier = Modifier
    ) {
        if (ingredients.isEmpty()) {
            Card(modifier = modifier.aspectRatio(1f), shape = RoundedCornerShape(8.dp), elevation = 1.dp) {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.surface.copy(alpha = 0.35f)))
            }
            return
        }

        val ingredient = ingredients.first()
        val id = ingredient.getResolvedId() ?: "Unknown"
        val count = ingredient.count?.toLong() ?: ingredient.amount ?: 1L
        val extraData = remember(ingredient) { ingredient.getExplicitDataSummary() }
        var imageFile by remember(id, assets) { mutableStateOf<File?>(null) }
        var textureState by remember(id) { mutableStateOf(TextureState.Loading) }

        LaunchedEffect(id, assets) {
            imageFile = null
            textureState = TextureState.Loading
            val resolvedFile = withContext(Dispatchers.IO) {
                AssetManager.getOrCreateDisplayImageFile(assets, id, requestedSizePx = 24)
            }
            imageFile = resolvedFile
            textureState = if (resolvedFile != null) TextureState.Ready else TextureState.Missing
        }
        DisposableEffect(id, assets) {
            onDispose {
                imageFile = null
            }
        }

        Card(
            modifier = modifier
                .aspectRatio(1f)
                .clickable { onIngredientClick(id) },
            shape = RoundedCornerShape(8.dp),
            elevation = 2.dp,
            backgroundColor = MaterialTheme.colors.surface
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(3.dp)) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    imageFile?.let { file ->
                        AsyncImage(
                            model = file,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    } ?: TexturePlaceholder(textureState, Modifier.size(24.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = assets.getTranslation(id),
                        fontSize = 9.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (extraData != null) {
                        Text(
                            text = extraData,
                            fontSize = 8.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.Gray
                        )
                    }
                }
                Text(
                    text = "x$count",
                    modifier = Modifier.align(Alignment.BottomEnd),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.primary
                )
            }
        }
    }

    @Composable
    fun CyclingIngredientRow(ingredients: List<Ingredient>, assets: RecipeAssets, onIngredientClick: (String) -> Unit) {
        if (ingredients.isEmpty()) return

        val ingredient = ingredients.first()
        val id = ingredient.getResolvedId() ?: "Unknown"
        val count = ingredient.count?.toLong() ?: ingredient.amount ?: 1L
        val extraData = remember(ingredient) { ingredient.getExplicitDataSummary() }
        var imageFile by remember(id, assets) { mutableStateOf<File?>(null) }
        var textureState by remember(id) { mutableStateOf(TextureState.Loading) }
        LaunchedEffect(id, assets) {
            imageFile = null
            textureState = TextureState.Loading
            val resolvedFile = withContext(Dispatchers.IO) {
                AssetManager.getOrCreateDisplayImageFile(assets, id, requestedSizePx = 28)
            }
            imageFile = resolvedFile
            textureState = if (resolvedFile != null) TextureState.Ready else TextureState.Missing
        }
        DisposableEffect(id, assets) {
            onDispose {
                imageFile = null
            }
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(MaterialTheme.colors.surface, RoundedCornerShape(8.dp))
                .clickable { onIngredientClick(id) }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(40.dp)) {
                imageFile?.let { file ->
                    AsyncImage(
                        model = file,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp)
                    )
                } ?: TexturePlaceholder(textureState, Modifier.size(40.dp))
            }

            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(assets.getTranslation(id), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (extraData != null) {
                    Text(extraData, style = MaterialTheme.typography.caption, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (ingredients.size > 1) {
                    Text("OR ${ingredients.size - 1} other variants", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.primary)
                }
            }
            Text("x$count", fontWeight = FontWeight.Black, color = MaterialTheme.colors.primary)
        }
    }

    private fun Ingredient.getExplicitDataSummary(): String? {
        fun compact(value: String): String {
            return value
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(90)
        }

        if (!nbt.isNullOrBlank()) {
            return "NBT: ${compact(nbt)}"
        }

        if (!unknown.isNullOrBlank()) {
            return "Data: ${compact(unknown)}"
        }

        val typeValue = type?.trim()?.lowercase()
        if (!typeValue.isNullOrBlank() && typeValue !in setOf("item", "block", "fluid")) {
            return "Type: $typeValue"
        }

        return null
    }

    @Composable
    fun ItemDebugDialog(id: String, assets: RecipeAssets, onDismiss: () -> Unit) {
        Dialog(onDismissRequest = onDismiss) {
            Card(shape = RoundedCornerShape(12.dp), elevation = 8.dp, modifier = Modifier.fillMaxWidth(0.95f)) {
                Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text("Item Debug Info", style = MaterialTheme.typography.h6, color = MaterialTheme.colors.primary)
                    Divider(Modifier.padding(vertical = 8.dp))
                    
                    DebugRow("Resolved ID", id)
                    DebugRow("Display Name", assets.getTranslation(id))
                    
                    val internalPath = AssetManager.getCachedImageFile(assets, id)?.absolutePath ?: "NOT CACHED YET"
                    val zipPath = AssetManager.getImageSourcePath(assets, id) ?: "NO MAPPING FOUND"
                    
                    DebugRow("Internal Storage Path", internalPath)
                    DebugRow("Source ZIP Path", zipPath)
                    
                    val type = if (id.contains("/")) id.substringBefore("/") else "item/block"
                    DebugRow("Inferred Type", type)

                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("CLOSE") }
                    }
                }
            }
        }
    }

    @Composable
    fun CalculatorScreen(
        assets: RecipeAssets,
        targets: List<CalculatorTarget>,
        selections: Map<String, Int>,
        showTree: Boolean,
        onShowTreeChange: (Boolean) -> Unit,
        onIngredientClick: (String) -> Unit,
        onAddCurrentItem: () -> Unit,
        onQuantityChange: (String, Long) -> Unit,
        onRemoveTarget: (String) -> Unit,
        onClearPlan: () -> Unit
    ) {
        val (totals, tree) = remember(assets, targets, selections) {
            calculatePlan(assets, targets, selections)
        }

        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Recipe Calculator", style = MaterialTheme.typography.h6, color = MaterialTheme.colors.primary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tree", style = MaterialTheme.typography.caption)
                    Switch(checked = showTree, onCheckedChange = onShowTreeChange)
                }
            }

            if (targets.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    elevation = 2.dp
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("No targets yet", style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Open any recipe and tap 'Add This Recipe To Calculator'. Then choose recipes for ingredients here.",
                            color = Color.Gray,
                            style = MaterialTheme.typography.body2
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onAddCurrentItem, enabled = false) {
                            Text("Add current item")
                        }
                    }
                }
                return
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onClearPlan,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Clear Plan")
                }
            }

            Spacer(Modifier.height(10.dp))
            Text("Targets", style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(targets, key = { it.itemId }) { target ->
                    val recipeCount = assets.recipesByOutput[target.itemId]?.size ?: 0
                    Card(shape = RoundedCornerShape(10.dp), elevation = 1.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(assets.getTranslation(target.itemId), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(target.itemId, style = MaterialTheme.typography.caption, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (recipeCount > 0) {
                                    Text("$recipeCount recipe options", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.primary)
                                }
                            }
                            IconButton(onClick = {
                                val newValue = (target.quantity - 1L).coerceAtLeast(1L)
                                onQuantityChange(target.itemId, newValue)
                            }) {
                                Icon(Icons.Default.Remove, contentDescription = "Decrease")
                            }
                            Text(target.quantity.toString(), fontWeight = FontWeight.Black)
                            IconButton(onClick = {
                                onQuantityChange(target.itemId, target.quantity + 1L)
                            }) {
                                Icon(Icons.Default.Add, contentDescription = "Increase")
                            }
                            IconButton(onClick = { onRemoveTarget(target.itemId) }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Total Ingredients", style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(
                    totals.entries.sortedBy { assets.getTranslation(it.key) },
                    key = { it.key }
                ) { (ingredientId, amount) ->
                    val selectedIndex = selections[ingredientId]
                    val selectionLabel = when {
                        selectedIndex == -1 -> "Using raw material"
                        selectedIndex != null -> {
                            val recipe = assets.recipesByOutput[ingredientId]?.getOrNull(selectedIndex)
                            recipe?.recipeType?.substringAfterLast(':')?.replace('_', ' ')?.uppercase() ?: "Recipe selected"
                        }
                        (assets.recipesByOutput[ingredientId]?.isNotEmpty() == true) -> "Tap to choose in Recipe View"
                        else -> "No recipe available"
                    }

                    Card(
                        shape = RoundedCornerShape(10.dp),
                        elevation = 1.dp,
                        modifier = Modifier.clickable { onIngredientClick(ingredientId) }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SmallItemIcon(
                                assets = assets,
                                itemId = ingredientId,
                                sizeDp = 22,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(assets.getTranslation(ingredientId), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(selectionLabel, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.primary)
                            }
                            Text("x$amount", fontWeight = FontWeight.Black, color = MaterialTheme.colors.primary)
                        }
                    }
                }

                if (showTree) {
                    item {
                        Spacer(Modifier.height(10.dp))
                        Text("Dependency Tree", style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                    }
                    items(tree) { node ->
                        InteractiveTreeDiagram(node = node, assets = assets)
                    }
                }
            }
        }
    }

    @Composable
    fun InteractiveTreeDiagram(node: RecipeTreeNode, assets: RecipeAssets) {
        var scale by remember(node) { mutableStateOf(1f) }
        var pan by remember(node) { mutableStateOf(Offset.Zero) }

        Card(
            shape = RoundedCornerShape(10.dp),
            elevation = 1.dp,
            backgroundColor = MaterialTheme.colors.surface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp, max = 520.dp)
                .clipToBounds()
                .pointerInput(node) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        val nextScale = (scale * zoomChange).coerceIn(0.4f, 3.5f)
                        scale = nextScale
                        pan = pan + panChange
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = pan.x
                        translationY = pan.y
                    }.wrapContentSize(unbounded = true),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    RecipeTreeDiagram(node = node, assets = assets)
                }
            }
        }
    }

    @Composable
    fun RecipeTreeDiagram(node: RecipeTreeNode, assets: RecipeAssets, depth: Int = 0) {
        val branchColor = MaterialTheme.colors.onSurface.copy(alpha = 0.35f)
        val children = node.children

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.wrapContentWidth()) {
            TreeIconNode(node = node, assets = assets)

            if (children.isNotEmpty()) {
                Canvas(modifier = Modifier.width(2.dp).height(16.dp)) {
                    drawLine(
                        color = branchColor,
                        start = Offset(size.width / 2f, 0f),
                        end = Offset(size.width / 2f, size.height)
                    )
                }

                Box(modifier = Modifier.width((children.size * 54).dp.coerceAtLeast(54.dp))) {
                    Canvas(modifier = Modifier.fillMaxWidth().height(12.dp)) {
                        drawLine(
                            color = branchColor,
                            start = Offset(0f, size.height / 2f),
                            end = Offset(size.width, size.height / 2f)
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    children.forEach { child ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Canvas(modifier = Modifier.width(2.dp).height(12.dp)) {
                                drawLine(
                                    color = branchColor,
                                    start = Offset(size.width / 2f, 0f),
                                    end = Offset(size.width / 2f, size.height)
                                )
                            }
                            RecipeTreeDiagram(node = child, assets = assets, depth = depth + 1)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun TreeIconNode(node: RecipeTreeNode, assets: RecipeAssets) {
        var imageFile by remember(node.itemId, assets) { mutableStateOf<File?>(null) }
        var textureState by remember(node.itemId, assets) { mutableStateOf(TextureState.Loading) }

        LaunchedEffect(node.itemId, assets) {
            imageFile = null
            textureState = TextureState.Loading
            val resolvedFile = withContext(Dispatchers.IO) {
                AssetManager.getOrCreateDisplayImageFile(assets, node.itemId, requestedSizePx = 28)
            }
            imageFile = resolvedFile
            textureState = if (resolvedFile != null) TextureState.Ready else TextureState.Missing
        }

        Card(
            shape = RoundedCornerShape(6.dp),
            elevation = 1.dp,
            backgroundColor = if (node.cycleDetected) Color.Red.copy(alpha = 0.15f) else MaterialTheme.colors.surface,
            modifier = Modifier.size(34.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                imageFile?.let { file ->
                    AsyncImage(
                        model = file,
                        contentDescription = assets.getTranslation(node.itemId),
                        modifier = Modifier.fillMaxSize(0.8f)
                    )
                } ?: TexturePlaceholder(textureState, Modifier.fillMaxSize(0.8f))
            }
        }
    }

    @Composable
    fun SmallItemIcon(
        assets: RecipeAssets?,
        itemId: String,
        sizeDp: Int,
        modifier: Modifier = Modifier
    ) {
        if (assets == null) {
            GridTextureStub(modifier = modifier)
            return
        }

        var imageFile by remember(itemId, assets) { mutableStateOf<File?>(null) }
        var textureState by remember(itemId, assets) { mutableStateOf(TextureState.Loading) }

        LaunchedEffect(itemId, assets) {
            imageFile = null
            textureState = TextureState.Loading
            val resolvedFile = withContext(Dispatchers.IO) {
                AssetManager.getOrCreateDisplayImageFile(assets, itemId, requestedSizePx = sizeDp)
            }
            imageFile = resolvedFile
            textureState = if (resolvedFile != null) TextureState.Ready else TextureState.Missing
        }

        imageFile?.let { file ->
            AsyncImage(
                model = file,
                contentDescription = assets.getTranslation(itemId),
                modifier = modifier
            )
        } ?: TexturePlaceholder(textureState, modifier)
    }

    @Composable
    fun WirelessTerminalScreen(
        assets: RecipeAssets?,
        host: String,
        loading: Boolean,
        error: String?,
        stacks: List<WirelessApiStack>,
        onHostChange: (String) -> Unit,
        onFetch: () -> Unit,
        sortOption: WirelessSortOption = WirelessSortOption.CountDescending,
        onSortOptionChange: (WirelessSortOption) -> Unit = {}
    ) {
        val sortedStacks = remember(stacks, sortOption) {
            sortWirelessStacks(stacks, sortOption)
        }

        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text("Wireless Terminal", style = MaterialTheme.typography.h6, color = MaterialTheme.colors.primary)
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = host,
                    onValueChange = onHostChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Host/IP (e.g. localhost or 192.168.1.10)") }
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onFetch, enabled = !loading) {
                    Text(if (loading) "Loading" else "Connect")
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Endpoint: http://$host:8787/api/ae2/grid",
                style = MaterialTheme.typography.caption,
                color = Color.Gray
            )

            if (loading) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (!error.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(error, color = Color.Red, style = MaterialTheme.typography.caption)
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Items: ${sortedStacks.size}", style = MaterialTheme.typography.subtitle2)
                Box {
                    var showDropdown by remember { mutableStateOf(false) }
                    Button(onClick = { showDropdown = true }, modifier = Modifier.height(32.dp)) {
                        Text(when (sortOption) {
                            WirelessSortOption.CountDescending -> "Count ↓"
                            WirelessSortOption.CountAscending -> "Count ↑"
                            WirelessSortOption.NameAscii -> "Name A-Z"
                            WirelessSortOption.NameReverse -> "Name Z-A"
                        }, style = MaterialTheme.typography.caption)
                    }
                    DropdownMenu(expanded = showDropdown, onDismissRequest = { showDropdown = false }) {
                        DropdownMenuItem(onClick = { 
                            onSortOptionChange(WirelessSortOption.CountDescending)
                            showDropdown = false
                        }) {
                            Text("Count ↓")
                        }
                        DropdownMenuItem(onClick = { 
                            onSortOptionChange(WirelessSortOption.CountAscending)
                            showDropdown = false
                        }) {
                            Text("Count ↑")
                        }
                        DropdownMenuItem(onClick = { 
                            onSortOptionChange(WirelessSortOption.NameAscii)
                            showDropdown = false
                        }) {
                            Text("Name A-Z")
                        }
                        DropdownMenuItem(onClick = { 
                            onSortOptionChange(WirelessSortOption.NameReverse)
                            showDropdown = false
                        }) {
                            Text("Name Z-A")
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 170.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(sortedStacks.size) { index ->
                    val stack = sortedStacks[index]
                    Card(shape = RoundedCornerShape(10.dp), elevation = 2.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SmallItemIcon(
                                assets = assets,
                                itemId = stack.id,
                                sizeDp = 24,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stack.name ?: assets?.getTranslation(stack.id) ?: stack.id,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(stack.id, style = MaterialTheme.typography.caption, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("x${formatLargeNumber(stack.amount)}", fontWeight = FontWeight.Black, color = MaterialTheme.colors.primary)
                        }
                    }
                }
            }
        }
    }

    private suspend fun fetchWirelessStacks(host: String): Result<List<WirelessApiStack>> = withContext(Dispatchers.IO) {
        return@withContext runCatching {
            val endpoint = "http://${host.trim()}:8787/api/ae2/grid"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 10000
            }

            connection.inputStream.use { input ->
                val text = input.bufferedReader(Charsets.UTF_8).readText()
                val response = com.google.gson.Gson().fromJson(text, WirelessApiResponse::class.java)
                response.grids.flatMap { it.stacks }
            }
        }
    }

    private fun calculatePlan(
        assets: RecipeAssets,
        targets: List<CalculatorTarget>,
        selections: Map<String, Int>
    ): Pair<Map<String, Long>, List<RecipeTreeNode>> {
        val totals = linkedMapOf<String, Long>()
        val trees = mutableListOf<RecipeTreeNode>()

        targets.forEach { target ->
            val node = expandIngredient(
                assets = assets,
                ingredientId = target.itemId,
                requiredAmount = target.quantity,
                selections = selections,
                totals = totals,
                path = emptySet(),
                depth = 0
            )
            trees += node
        }

        return totals to trees
    }

    private fun expandIngredient(
        assets: RecipeAssets,
        ingredientId: String,
        requiredAmount: Long,
        selections: Map<String, Int>,
        totals: MutableMap<String, Long>,
        path: Set<String>,
        depth: Int
    ): RecipeTreeNode {
        if (requiredAmount <= 0L) {
            return RecipeTreeNode(ingredientId, 0L, null, emptyList())
        }

        if (depth > 30 || path.contains(ingredientId)) {
            totals[ingredientId] = totals.getOrDefault(ingredientId, 0L) + requiredAmount
            return RecipeTreeNode(
                itemId = ingredientId,
                quantity = requiredAmount,
                recipeType = null,
                children = emptyList(),
                cycleDetected = true
            )
        }

        val candidates = assets.recipesByOutput[ingredientId].orEmpty()
        val selectedIndex = selections[ingredientId]
        // Keep calculator stable on huge packs: only expand an ingredient if the user
        // explicitly selected a recipe for it. Otherwise treat it as a raw requirement.
        if (selectedIndex == null || selectedIndex == -1 || candidates.isEmpty()) {
            totals[ingredientId] = totals.getOrDefault(ingredientId, 0L) + requiredAmount
            return RecipeTreeNode(ingredientId, requiredAmount, null, emptyList())
        }

        val recipe = candidates.getOrNull(selectedIndex)
        if (recipe == null) {
            totals[ingredientId] = totals.getOrDefault(ingredientId, 0L) + requiredAmount
            return RecipeTreeNode(ingredientId, requiredAmount, null, emptyList())
        }

        val outputPerCraft = outputCountFor(recipe, ingredientId).coerceAtLeast(1L)
        val craftsNeeded = ceilDiv(requiredAmount, outputPerCraft)
        val nextPath = path + ingredientId

        val children = recipe.slots
            .asSequence()
            .filter { it.role == "INPUT" }
            .mapNotNull { slot ->
                val chosen = slot.ingredients.firstOrNull()
                val childId = chosen?.getResolvedId() ?: return@mapNotNull null
                val perCraft = chosen.count?.toLong() ?: chosen.amount ?: 1L
                expandIngredient(
                    assets = assets,
                    ingredientId = childId,
                    requiredAmount = perCraft * craftsNeeded,
                    selections = selections,
                    totals = totals,
                    path = nextPath,
                    depth = depth + 1
                )
            }
            .toList()

        return RecipeTreeNode(
            itemId = ingredientId,
            quantity = requiredAmount,
            recipeType = recipe.recipeType,
            children = children
        )
    }

    private fun outputCountFor(recipe: RecipeDump, outputId: String): Long {
        val matchingOutput = recipe.slots
            .asSequence()
            .filter { it.role == "OUTPUT" }
            .flatMap { it.ingredients.asSequence() }
            .firstOrNull { it.getResolvedId() == outputId }

        return matchingOutput?.count?.toLong()
            ?: matchingOutput?.amount
            ?: 1L
    }

    private fun ceilDiv(value: Long, divisor: Long): Long {
        if (divisor <= 0L) return value
        return (value + divisor - 1L) / divisor
    }

    @Composable
    fun DebugRow(label: String, value: String) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Gray)
            Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
    }

    @Composable
    fun IndexingBanner(progress: Float, message: String, modifier: Modifier = Modifier) {
        Card(
            shape = RoundedCornerShape(12.dp),
            elevation = 8.dp,
            modifier = modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(message, style = MaterialTheme.typography.subtitle2, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = progress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colors.primary,
                    backgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }

    @Composable
    fun ThumbnailPreloadBanner(progress: Float, message: String, modifier: Modifier = Modifier) {
        Card(
            shape = RoundedCornerShape(12.dp),
            elevation = 8.dp,
            modifier = modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(message, style = MaterialTheme.typography.subtitle2, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = progress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colors.secondary,
                    backgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }

    @Composable
    fun ImportLibrary(
        importedFolders: List<File>,
        onFolderSelected: (File) -> Unit,
        onFolderDelete: (File) -> Unit,
        onImportClick: () -> Unit
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Asset Library", style = MaterialTheme.typography.h6, color = MaterialTheme.colors.primary)
            Spacer(Modifier.height(8.dp))
            
            if (importedFolders.isEmpty()) {
                WelcomeScreen(onImportClick)
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(importedFolders, key = { it.name }) { folder ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onFolderSelected(folder) },
                            elevation = 2.dp,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Inventory, contentDescription = null, tint = MaterialTheme.colors.primary.copy(alpha = 0.7f))
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(folder.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { onFolderDelete(folder) }) {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red.copy(alpha = 0.6f))
                                }
                            }
                        }
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onImportClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("IMPORT NEW .AEI")
                }
            }
        }
    }

    @Composable
    fun SettingsScreen(
        showTagRecipes: Boolean,
        isDebugMode: Boolean,
        showGridTextures: Boolean,
        onShowTagRecipesChange: (Boolean) -> Unit,
        onIsDebugModeChange: (Boolean) -> Unit,
        onShowGridTexturesChange: (Boolean) -> Unit
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("General Settings", style = MaterialTheme.typography.h6, color = MaterialTheme.colors.primary)
            Spacer(Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onShowTagRecipesChange(!showTagRecipes) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show Tag Recipes", style = MaterialTheme.typography.subtitle1)
                    Text("Include recipes that show all items for a tag", style = MaterialTheme.typography.caption, color = Color.Gray)
                }
                Switch(checked = showTagRecipes, onCheckedChange = onShowTagRecipesChange)
            }

            Divider(Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().clickable { onIsDebugModeChange(!isDebugMode) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Debug Mode", style = MaterialTheme.typography.subtitle1)
                    Text("Long press items to see internal mapping info", style = MaterialTheme.typography.caption, color = Color.Gray)
                }
                Switch(checked = isDebugMode, onCheckedChange = onIsDebugModeChange)
            }
            
            Divider(Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().clickable { onShowGridTexturesChange(!showGridTextures) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Grid Textures (High Memory)", style = MaterialTheme.typography.subtitle1)
                    Text("Disabled by default to prevent scrolling crashes on very large libraries", style = MaterialTheme.typography.caption, color = Color.Gray)
                }
                Switch(checked = showGridTextures, onCheckedChange = onShowGridTexturesChange)
            }

            Divider(Modifier.padding(vertical = 8.dp))
            Text("App Version: 1.2", style = MaterialTheme.typography.caption, color = Color.Gray)
        }
    }

    @Composable
    fun SearchField(query: String, onQueryChange: (String) -> Unit) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search items...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }
            },
            singleLine = true,
            colors = TextFieldDefaults.textFieldColors(backgroundColor = MaterialTheme.colors.surface, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
        )
    }

    enum class TextureState {
        Loading,
        Ready,
        Missing
    }

    @Composable
    fun TexturePlaceholder(state: TextureState, modifier: Modifier = Modifier) {
        when (state) {
            TextureState.Loading -> LoadingTexturePlaceholder(modifier)
            TextureState.Ready -> Box(modifier = modifier)
            TextureState.Missing -> MissingTexturePlaceholder(modifier)
        }
    }

    @Composable
    fun LoadingTexturePlaceholder(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.fillMaxSize(0.45f),
                strokeWidth = 2.dp,
                color = MaterialTheme.colors.primary.copy(alpha = 0.65f)
            )
        }
    }

    @Composable
    fun MissingTexturePlaceholder(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .background(Color.Gray.copy(alpha = 0.1f), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = Color.Red.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxSize(0.6f)
            )
        }
    }

    @Composable
    fun JeiPagedIconGrid(
        itemIds: List<String>,
        assets: RecipeAssets,
        showTextures: Boolean,
        isDebugMode: Boolean,
        onItemAction: (String, Boolean) -> Unit
    ) {
        if (itemIds.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No matching items.", color = Color.Gray)
            }
            return
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp)) {
            val columns = (maxWidth / 42.dp).toInt().coerceIn(6, 10)
            val horizontalSpacing = 4.dp
            val verticalSpacing = 4.dp
            val pagerHeight = 52.dp
            val cellSize = ((maxWidth - (horizontalSpacing * (columns - 1))) / columns).coerceAtLeast(20.dp)
            val rows = ((maxHeight - pagerHeight + verticalSpacing) / (cellSize + verticalSpacing)).toInt().coerceIn(4, 12)
            val pageSize = (columns * rows).coerceAtLeast(1)
            val pageCount = ((itemIds.size + pageSize - 1) / pageSize).coerceAtLeast(1)
            var currentPage by remember(itemIds.size, columns) { mutableIntStateOf(0) }

            if (currentPage >= pageCount) currentPage = 0

            val startIndex = currentPage * pageSize
            val pageItems = remember(itemIds, startIndex, pageSize) {
                itemIds.drop(startIndex).take(pageSize)
            }
            var loadBudget by remember(currentPage, pageItems.size, showTextures) {
                mutableIntStateOf(if (showTextures && pageItems.isNotEmpty()) 1 else 0)
            }

            LaunchedEffect(currentPage, pageItems.size, showTextures) {
                if (!showTextures) {
                    loadBudget = 0
                    return@LaunchedEffect
                }
                loadBudget = if (pageItems.isNotEmpty()) 1 else 0
                while (loadBudget < pageItems.size) {
                    delay(35)
                    loadBudget += 1
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(verticalSpacing)
                ) {
                    repeat(rows) { rowIndex ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing)
                        ) {
                            repeat(columns) { colIndex ->
                                val index = (rowIndex * columns) + colIndex
                                val itemId = pageItems.getOrNull(index)
                                if (itemId != null) {
                                    JeiIconCell(
                                        id = itemId,
                                        assets = assets,
                                        showTextures = showTextures,
                                        deferImageLoading = index >= loadBudget,
                                        isDebugMode = isDebugMode,
                                        modifier = Modifier.weight(1f),
                                        onAction = onItemAction
                                    )
                                } else {
                                    Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { if (currentPage > 0) currentPage -= 1 },
                        enabled = currentPage > 0
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous page")
                    }
                    Text(
                        text = "Page ${currentPage + 1}/$pageCount • ${itemIds.size} items",
                        style = MaterialTheme.typography.caption,
                        color = Color.Gray
                    )
                    IconButton(
                        onClick = { if (currentPage < pageCount - 1) currentPage += 1 },
                        enabled = currentPage < pageCount - 1
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next page")
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun JeiIconCell(
        id: String,
        assets: RecipeAssets,
        showTextures: Boolean,
        deferImageLoading: Boolean,
        isDebugMode: Boolean,
        modifier: Modifier = Modifier,
        onAction: (String, Boolean) -> Unit
    ) {
        var imageFile by remember(id, assets) { mutableStateOf<File?>(null) }
        var textureState by remember(id, assets) { mutableStateOf(TextureState.Loading) }

        LaunchedEffect(id, assets, showTextures, deferImageLoading) {
            if (!showTextures) {
                imageFile = null
                textureState = TextureState.Loading
                return@LaunchedEffect
            }
            if (deferImageLoading) {
                return@LaunchedEffect
            }
            imageFile = null
            textureState = TextureState.Loading
            val resolvedFile = withContext(Dispatchers.IO) {
                AssetManager.getOrCreateDisplayImageFile(assets, id, requestedSizePx = 28)
            }
            imageFile = resolvedFile
            textureState = if (resolvedFile != null) TextureState.Ready else TextureState.Missing
        }

        Card(
            modifier = modifier
                .aspectRatio(1f)
                .combinedClickable(
                    onClick = { onAction(id, false) },
                    onLongClick = { if (isDebugMode) onAction(id, true) }
                ),
            shape = RoundedCornerShape(4.dp),
            elevation = 1.dp,
            backgroundColor = MaterialTheme.colors.surface
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (showTextures) {
                    imageFile?.let { file ->
                        AsyncImage(
                            model = file,
                            contentDescription = assets.getTranslation(id),
                            modifier = Modifier.fillMaxSize(0.82f)
                        )
                    } ?: TexturePlaceholder(textureState, Modifier.fillMaxSize(0.82f))
                } else {
                    GridTextureStub(modifier = Modifier.fillMaxSize(0.82f))
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun ItemCard(
        id: String,
        assets: RecipeAssets,
        isDebugMode: Boolean,
        showTextures: Boolean,
        deferImageLoading: Boolean,
        onClick: (Boolean) -> Unit
    ) {
        val displayName = assets.getTranslation(id)
        var imageFile by remember(id, assets) { mutableStateOf<File?>(null) }
        var textureState by remember(id, assets) { mutableStateOf(TextureState.Loading) }

        LaunchedEffect(id, assets, showTextures, deferImageLoading) {
            if (!showTextures) {
                imageFile = null
                textureState = TextureState.Loading
                return@LaunchedEffect
            }

            // Keep scrolling smooth: skip decoding while fling/scroll is active.
            if (deferImageLoading) {
                imageFile = null
                textureState = TextureState.Loading
                return@LaunchedEffect
            }

            imageFile = null
            textureState = TextureState.Loading
            val resolvedFile = withContext(Dispatchers.IO) {
                AssetManager.getOrCreateDisplayImageFile(assets, id, requestedSizePx = 32)
            }
            imageFile = resolvedFile
            textureState = if (resolvedFile != null) TextureState.Ready else TextureState.Missing
        }
        DisposableEffect(id, assets, showTextures) {
            onDispose {
                imageFile = null
            }
        }

        Card(
            modifier = Modifier
                .padding(4.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .combinedClickable(
                    onClick = { onClick(false) },
                    onLongClick = { if (isDebugMode) onClick(true) }
                ),
            elevation = 2.dp,
            shape = RoundedCornerShape(8.dp),
            backgroundColor = MaterialTheme.colors.surface
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(8.dp)
            ) {
                if (showTextures) {
                    imageFile?.let { file ->
                        AsyncImage(
                            model = file,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp)
                        )
                    } ?: TexturePlaceholder(textureState, Modifier.size(48.dp))
                } else {
                    GridTextureStub(modifier = Modifier.size(48.dp))
                }
                
                Spacer(Modifier.height(4.dp))
                Text(text = displayName, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            }
        }
    }

    @Composable
    fun GridTextureStub(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Inventory,
                contentDescription = null,
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxSize(0.6f)
            )
        }
    }

    @Composable
    fun WelcomeScreen(onImport: () -> Unit) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Inventory, contentDescription = null, modifier = Modifier.size(100.dp), tint = MaterialTheme.colors.primary.copy(alpha = 0.2f))
            Spacer(Modifier.height(16.dp))
            Text("AEI Viewer", style = MaterialTheme.typography.h4, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Browse Minecraft recipes with ease.", textAlign = TextAlign.Center, color = Color.Gray)
            Spacer(Modifier.height(32.dp))
            Button(onClick = onImport, modifier = Modifier.fillMaxWidth(0.7f), shape = RoundedCornerShape(12.dp)) {
                Text("IMPORT .AEI FILE", modifier = Modifier.padding(8.dp))
            }
        }
    }
}
