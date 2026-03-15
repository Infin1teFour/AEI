package me.infin1te.aei

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
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
        var selectedItemRecipes by remember { mutableStateOf<List<RecipeDump>?>(null) }
        var debugItemId by remember { mutableStateOf<String?>(null) }
        var searchQuery by remember { mutableStateOf("") }
        var currentScreen by remember { mutableStateOf<Screen>(Screen.Recipes) }
        var importedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
        var isLoading by remember { mutableStateOf(false) }
        var loadingProgress by remember { mutableFloatStateOf(0f) }
        
        var showTagRecipes by remember { mutableStateOf(false) }
        var isDebugMode by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            importedFiles = AssetManager.listImportedFiles(this@MainActivity)
            val lastFile = AssetManager.getLastLoadedFile(this@MainActivity)
            if (lastFile != null) {
                val file = File(File(filesDir, "imported_assets"), lastFile)
                if (file.exists()) {
                    scope.launch {
                        isLoading = true
                        assets = AssetManager.loadAssetsFromFile(this@MainActivity, file) { progress: Float ->
                            loadingProgress = progress
                        }
                        isLoading = false
                    }
                }
            }
        }

        val pickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let {
                scope.launch {
                    isLoading = true
                    loadingProgress = 0f
                    val imported = AssetManager.importAeiFile(this@MainActivity, it) { progress: Float ->
                        loadingProgress = progress
                    }
                    if (imported != null) {
                        assets = imported
                        importedFiles = AssetManager.listImportedFiles(this@MainActivity)
                    }
                    isLoading = false
                }
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                Surface(elevation = 4.dp) {
                    Column(modifier = Modifier.statusBarsPadding()) {
                        TopAppBar(
                            title = { Text(currentScreen.title) },
                            elevation = 0.dp,
                            actions = {
                                if (currentScreen == Screen.Recipes) {
                                    TextButton(onClick = { pickerLauncher.launch("*/*") }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                                        Text("IMPORT")
                                    }
                                }
                            }
                        )
                        if (currentScreen == Screen.Recipes && assets != null) {
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
                                importedFiles = importedFiles,
                                onFileSelected = { file ->
                                    scope.launch {
                                        isLoading = true
                                        loadingProgress = 0f
                                        assets = AssetManager.loadAssetsFromFile(this@MainActivity, file) { progress: Float ->
                                            loadingProgress = progress
                                        }
                                        isLoading = false
                                    }
                                },
                                onFileDelete = { file ->
                                    AssetManager.deleteImportedFile(this@MainActivity, file)
                                    importedFiles = AssetManager.listImportedFiles(this@MainActivity)
                                },
                                onImportClick = { pickerLauncher.launch("*/*") }
                            )
                        } else {
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
                                            selectedItemRecipes = assets!!.recipesByOutput[id]
                                        }
                                    }
                                }
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

                if (isLoading) {
                    LoadingOverlay(loadingProgress)
                }

                debugItemId?.let { id ->
                    ItemDebugDialog(id, assets!!, onDismiss = { debugItemId = null })
                }

                selectedItemRecipes?.let { recipes ->
                    RecipeBrowserDialog(recipes, assets!!) {
                        selectedItemRecipes = null
                    }
                }
            }
        }
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
                    
                    val expectedZipPath = remember(id) {
                        val parts = id.split("/")
                        val typePath = if (parts.size > 1) parts.dropLast(1).joinToString("/") + "/" else ""
                        val fileName = parts.last().replace(":", "_")
                        "inventory_images/$typePath$fileName.png"
                    }
                    
                    DebugRow("Expected Zip Path", expectedZipPath)
                    DebugRow("Actual Zip Path Found", zipPath)
                    DebugRow("Cache File Path", internalPath)
                    
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
    fun LoadingOverlay(progress: Float) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                elevation = 8.dp,
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Loading Assets", style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = MaterialTheme.colors.primary,
                        backgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.12f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }

    @Composable
    fun ImportLibrary(
        importedFiles: List<File>,
        onFileSelected: (File) -> Unit,
        onFileDelete: (File) -> Unit,
        onImportClick: () -> Unit
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Imported Assets", style = MaterialTheme.typography.h6, color = MaterialTheme.colors.primary)
            Spacer(Modifier.height(8.dp))
            
            if (importedFiles.isEmpty()) {
                WelcomeScreen(onImportClick)
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(importedFiles) { file ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onFileSelected(file) },
                            elevation = 2.dp,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Description, contentDescription = null, tint = Color.Gray)
                                Spacer(Modifier.width(16.dp))
                                Text(file.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { onFileDelete(file) }) {
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
            Text("App Version: 1.0", style = MaterialTheme.typography.caption, color = Color.Gray)
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
    fun RecipeBrowserDialog(recipes: List<RecipeDump>, assets: RecipeAssets, onDismiss: () -> Unit) {
        var currentIndex by remember { mutableIntStateOf(0) }
        val currentRecipe = recipes[currentIndex]

        Dialog(onDismissRequest = onDismiss) {
            Card(
                shape = RoundedCornerShape(12.dp),
                elevation = 8.dp,
                modifier = Modifier.fillMaxWidth(0.95f).wrapContentHeight()
            ) {
                Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Recipe", style = MaterialTheme.typography.subtitle2, color = MaterialTheme.colors.primary)
                            Text(currentRecipe.recipeType, fontSize = 11.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        
                        if (recipes.size > 1) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { if (currentIndex > 0) currentIndex-- }, enabled = currentIndex > 0) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                                Text("${currentIndex + 1} / ${recipes.size}", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                IconButton(onClick = { if (currentIndex < recipes.size - 1) currentIndex++ }, enabled = currentIndex < recipes.size - 1) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }

                    Divider(Modifier.padding(vertical = 8.dp))
                    Text("Outputs:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    currentRecipe.slots.filter { it.role == "OUTPUT" }.forEach { slot ->
                        slot.ingredients.forEach { ing ->
                            val id = ing.getResolvedId() ?: "Unknown"
                            IngredientRow(id, ing.count?.toLong() ?: ing.amount ?: 1L, assets)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Inputs:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    val compressedInputs = remember(currentRecipe) {
                        currentRecipe.slots
                            .filter { it.role == "INPUT" }
                            .flatMap { it.ingredients }
                            .filter { it.getResolvedId() != null }
                            .groupBy { it.getResolvedId() ?: "Unknown" }
                            .mapValues { (_, ings) -> ings.sumOf { (it.count?.toLong() ?: it.amount ?: 1L) } }
                    }

                    if (compressedInputs.isEmpty()) {
                        Text("No inputs", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(start = 8.dp))
                    } else {
                        compressedInputs.forEach { (id, qty) ->
                            IngredientRow(id, qty, assets)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End), shape = RoundedCornerShape(8.dp)) {
                        Text("CLOSE")
                    }
                }
            }
        }
    }

    @Composable
    fun IngredientRow(id: String, qty: Long, assets: RecipeAssets) {
        val name = assets.getTranslation(id)
        var bitmap by remember(id, assets) { mutableStateOf<Bitmap?>(null) }

        LaunchedEffect(id, assets) {
            withContext(Dispatchers.IO) {
                bitmap = AssetManager.loadBitmap(assets.imagePaths[id])
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
        ) {
            bitmap?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(28.dp))
            } ?: MissingTexturePlaceholder(Modifier.size(28.dp))
            
            Spacer(Modifier.width(8.dp))
            Text(name, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("x$qty", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colors.primary)
        }
    }

    @Composable
    fun WelcomeScreen(onImport: () -> Unit) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
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
