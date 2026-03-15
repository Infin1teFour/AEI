package me.infin1te.aei

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

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
                // Ensure the app fills the screen and respects system bars
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
        var searchQuery by remember { mutableStateOf("") }

        val pickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let {
                scope.launch {
                    val imported = AssetManager.importAeiFile(this@MainActivity, it)
                    if (imported != null) assets = imported
                }
            }
        }

        val uniqueItems = remember(assets) {
            assets?.recipes?.flatMap { r -> 
                r.slots.filter { it.role == "OUTPUT" }.flatMap { it.ingredients }.mapNotNull { it.item ?: it.fluid }
            }?.distinct() ?: emptyList()
        }

        val recipesByOutput = remember(assets) {
            assets?.recipes?.let { allRecipes ->
                uniqueItems.associateWith { id ->
                    allRecipes.filter { r -> 
                        r.slots.any { s -> s.role == "OUTPUT" && s.ingredients.any { i -> (i.item ?: i.fluid) == id } }
                    }
                }
            } ?: emptyMap()
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                Surface(elevation = 4.dp) {
                    Column(modifier = Modifier.statusBarsPadding()) {
                        TopAppBar(
                            title = { Text("AEI Viewer") },
                            elevation = 0.dp,
                            actions = {
                                TextButton(onClick = { pickerLauncher.launch("*/*") }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                                    Text("IMPORT")
                                }
                            }
                        )
                        if (assets != null) {
                            SearchField(searchQuery) { searchQuery = it }
                        }
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                if (assets == null) {
                    WelcomeScreen { pickerLauncher.launch("*/*") }
                } else {
                    val filteredIds = remember(searchQuery, uniqueItems) {
                        uniqueItems.filter { id ->
                            val name = assets!!.translations[id] ?: id
                            name.contains(searchQuery, ignoreCase = true) || id.contains(searchQuery, ignoreCase = true)
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 100.dp),
                        contentPadding = PaddingValues(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredIds) { id ->
                            ItemCard(id, assets!!) {
                                selectedItemRecipes = recipesByOutput[id]
                            }
                        }
                    }
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
            colors = TextFieldDefaults.textFieldColors(
                backgroundColor = MaterialTheme.colors.surface,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
    }

    @Composable
    fun ItemCard(id: String, assets: RecipeAssets, onClick: () -> Unit) {
        val displayName = assets.translations[id] ?: id
        val image = assets.images[id]

        Card(
            modifier = Modifier
                .padding(4.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clickable { onClick() },
            elevation = 2.dp,
            shape = RoundedCornerShape(8.dp),
            backgroundColor = MaterialTheme.colors.surface
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(8.dp)
            ) {
                if (image != null) {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = displayName,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    @Composable
    fun RecipeBrowserDialog(recipes: List<RecipeDump>, assets: RecipeAssets, onDismiss: () -> Unit) {
        var currentIndex by remember { mutableStateOf(0) }
        val currentRecipe = recipes[currentIndex]

        Dialog(onDismissRequest = onDismiss) {
            Card(
                shape = RoundedCornerShape(12.dp),
                elevation = 8.dp,
                modifier = Modifier.fillMaxWidth(0.95f).wrapContentHeight()
            ) {
                Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    // Header Area
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
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

                    // Outputs Section
                    Text("Outputs:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    currentRecipe.slots.filter { it.role == "OUTPUT" }.forEach { slot ->
                        slot.ingredients.forEach { ing ->
                            val id = ing.item ?: ing.fluid ?: "Unknown"
                            IngredientRow(id, ing.count ?: ing.amount ?: 1, assets)
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Inputs Section
                    Text("Inputs:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    val compressedInputs = remember(currentRecipe) {
                        currentRecipe.slots
                            .filter { it.role == "INPUT" }
                            .flatMap { it.ingredients }
                            .filter { it.item != null || it.fluid != null }
                            .groupBy { it.item ?: it.fluid ?: "Unknown" }
                            .mapValues { (_, ings) -> ings.sumOf { it.count ?: it.amount ?: 1 } }
                    }

                    if (compressedInputs.isEmpty()) {
                        Text("No inputs", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.padding(start = 8.dp))
                    } else {
                        compressedInputs.forEach { (id, qty) ->
                            IngredientRow(id, qty, assets)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("CLOSE")
                    }
                }
            }
        }
    }

    @Composable
    fun IngredientRow(id: String, qty: Int, assets: RecipeAssets) {
        val name = assets.translations[id] ?: id
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
        ) {
            assets.images[id]?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(28.dp))
            } ?: Box(Modifier.size(28.dp).background(Color.Gray.copy(alpha = 0.2f)))
            
            Spacer(Modifier.width(8.dp))
            Text("$name", fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("x$qty", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colors.primary)
        }
    }

    @Composable
    fun WelcomeScreen(onImport: () -> Unit) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
