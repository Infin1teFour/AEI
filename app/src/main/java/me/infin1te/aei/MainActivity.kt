package me.infin1te.aei

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class Screen(val title: String, val icon: ImageVector) {
    object Recipes : Screen("Recipes", Icons.AutoMirrored.Filled.List)
    object Settings : Screen("Settings", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

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
        
        var showTagRecipes by remember { mutableStateOf(false) }
        var isDebugMode by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            importedFolders = AssetManager.listImportedFolders(this@MainActivity)
            val lastFolder = AssetManager.getLastLoadedFolder(this@MainActivity)
            if (lastFolder != null) {
                scope.launch {
                    isIndexing = true
                    indexingMessage = "Loading library"
                    assets = AssetManager.loadFromUnpacked(this@MainActivity, lastFolder) { progress: Float ->
                        indexingProgress = progress
                    }
                    isIndexing = false
                    indexingMessage = null
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
                    val imported = AssetManager.importAeiFile(this@MainActivity, it) { progress: Float ->
                        indexingProgress = progress
                    }
                    if (imported != null) {
                        assets = imported
                        importedFolders = AssetManager.listImportedFolders(this@MainActivity)
                    }
                    isIndexing = false
                    indexingMessage = null
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
                                    Text(assets!!.getTranslation(currentItemId), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    val items = listOf(Screen.Recipes, Screen.Settings)
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
                                        assets = AssetManager.loadFromUnpacked(this@MainActivity, folder.name) { progress: Float ->
                                            indexingProgress = progress
                                        }
                                        isIndexing = false
                                        indexingMessage = null
                                    }
                                },
                                onFolderDelete = { folder ->
                                    AssetManager.deleteUnpackedFolder(this@MainActivity, folder)
                                    importedFolders = AssetManager.listImportedFolders(this@MainActivity)
                                },
                                onImportClick = { pickerLauncher.launch("*/*") }
                            )
                        } else {
                            if (currentItemId == null) {
                                val filteredIds = remember(searchQuery, assets, showTagRecipes) {
                                    assets!!.uniqueItems.filter { id ->
                                        val name = assets!!.getTranslation(id)
                                        name.contains(searchQuery, ignoreCase = true) || id.contains(searchQuery, ignoreCase = true)
                                    }
                                }

                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 100.dp),
                                    contentPadding = PaddingValues(8.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(filteredIds) { id ->
                                        ItemCard(id, assets!!, isDebugMode) { isLongClick ->
                                            if (isLongClick && isDebugMode) {
                                                debugItemId = id
                                            } else {
                                                navigationStack = navigationStack + id
                                            }
                                        }
                                    }
                                }
                            } else {
                                RecipeView(
                                    itemId = currentItemId,
                                    assets = assets!!,
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
                    is Screen.Settings -> {
                        SettingsScreen(
                            showTagRecipes = showTagRecipes,
                            isDebugMode = isDebugMode,
                            onShowTagRecipesChange = { showTagRecipes = it },
                            onIsDebugModeChange = { isDebugMode = it }
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

                debugItemId?.let { id ->
                    ItemDebugDialog(id, assets!!, onDismiss = { debugItemId = null })
                }
            }
        }
    }

    @Composable
    fun RecipeView(itemId: String, assets: RecipeAssets, onIngredientClick: (String) -> Unit) {
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

        var currentIndex by remember { mutableIntStateOf(0) }

        LaunchedEffect(ingredients) {
            if (ingredients.size > 1) {
                while (true) {
                    delay(2000)
                    currentIndex = (currentIndex + 1) % ingredients.size
                }
            }
        }

        val ingredient = ingredients[currentIndex]
        val id = ingredient.getResolvedId() ?: "Unknown"
        val count = ingredient.count?.toLong() ?: ingredient.amount ?: 1L
        val extraData = remember(ingredient) { ingredient.getExplicitDataSummary() }
        var bitmap by remember(id) { mutableStateOf<Bitmap?>(null) }

        LaunchedEffect(id) {
            withContext(Dispatchers.IO) {
                bitmap = AssetManager.loadBitmap(assets.imagePaths[id])
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
                    bitmap?.let {
                        Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(24.dp))
                    } ?: MissingTexturePlaceholder(Modifier.size(24.dp))
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
        
        var currentIndex by remember { mutableIntStateOf(0) }
        
        LaunchedEffect(ingredients) {
            if (ingredients.size > 1) {
                while (true) {
                    delay(1000)
                    currentIndex = (currentIndex + 1) % ingredients.size
                }
            }
        }

        val ingredient = ingredients[currentIndex]
        val id = ingredient.getResolvedId() ?: "Unknown"
        val count = ingredient.count?.toLong() ?: ingredient.amount ?: 1L
        val extraData = remember(ingredient) { ingredient.getExplicitDataSummary() }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(MaterialTheme.colors.surface, RoundedCornerShape(8.dp))
                .clickable { onIngredientClick(id) }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var bitmap by remember(id) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(id) {
                withContext(Dispatchers.IO) {
                    bitmap = AssetManager.loadBitmap(assets.imagePaths[id])
                }
            }

            Box(modifier = Modifier.size(40.dp)) {
                bitmap?.let {
                    Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp))
                } ?: MissingTexturePlaceholder(Modifier.size(40.dp))
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
                    
                    val internalPath = assets.imagePaths[id]?.absolutePath ?: "NOT FOUND IN CACHE"
                    val zipPath = assets.originalImagePaths[id] ?: "NO MAPPING FOUND"
                    
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
                    items(importedFolders) { folder ->
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
    fun SettingsScreen(showTagRecipes: Boolean, isDebugMode: Boolean, onShowTagRecipesChange: (Boolean) -> Unit, onIsDebugModeChange: (Boolean) -> Unit) {
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

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun ItemCard(id: String, assets: RecipeAssets, isDebugMode: Boolean, onClick: (Boolean) -> Unit) {
        val displayName = assets.getTranslation(id)
        var bitmap by remember(id, assets) { mutableStateOf<Bitmap?>(null) }

        LaunchedEffect(id, assets) {
            withContext(Dispatchers.IO) {
                bitmap = AssetManager.loadBitmap(assets.imagePaths[id])
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
                bitmap?.let {
                    Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(48.dp))
                } ?: MissingTexturePlaceholder(Modifier.size(48.dp))
                
                Spacer(Modifier.height(4.dp))
                Text(text = displayName, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            }
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
