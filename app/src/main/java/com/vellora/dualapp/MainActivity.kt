@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vellora.dualapp

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.vellora.dualapp.data.AppDatabase
import com.vellora.dualapp.data.ClonedAppEntity
import com.vellora.dualapp.ui.theme.DualAppTheme
import com.vellora.dualapp.virtual.VirtualCore
import kotlinx.coroutines.launch

// ---------- Data model ----------

data class InstalledApp(
    val label: String,
    val packageName: String,
    val icon: Drawable
)

data class ClonedApp(
    val id: Long,
    val label: String,
    val originalPackageName: String,
    val icon: Drawable
)

// ---------- Activity ----------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VirtualCore.init(applicationContext)
        setContent {
            DualAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DualAppRoot()
                }
            }
        }
    }
}

// ---------- Root composable: holds navigation state ----------

@Composable
fun DualAppRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }

    // Persisted via Room — survives app restarts. VirtualCore (Phase 3)
    // will eventually own each clone's sandboxed storage; this DB just
    // tracks *which* packages have been cloned for the UI.
    val clonedEntities by db.clonedAppDao().getAll().collectAsState(initial = emptyList())
    val clonedApps = remember(clonedEntities) {
        clonedEntities.map { entity ->
            ClonedApp(
                id = entity.id,
                label = entity.label,
                originalPackageName = entity.packageName,
                icon = loadAppIcon(context.packageManager, entity.packageName)
            )
        }
    }

    var showAppPicker by remember { mutableStateOf(false) }
    var pendingApp by remember { mutableStateOf<InstalledApp?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        HomeScreen(
            title = "Cloned Apps",
            clonedApps = clonedApps,
            onAddClick = { showAppPicker = true },
            onRemoveClick = { app ->
                scope.launch {
                    db.clonedAppDao().delete(
                        ClonedAppEntity(app.id, app.label, app.originalPackageName)
                    )
                }
                VirtualCore.removeClone(app.originalPackageName)
            },
            onTileClick = { app ->
                val launched = VirtualCore.launchClonedApp(app.originalPackageName)
                if (!launched) {
                    Toast.makeText(
                        context,
                        "\"${app.label}\" launch nahi ho saki — is Android version par hooking fail hui (log check karein).",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )

        if (showAppPicker) {
            AppPickerScreen(
                onAppSelected = { app ->
                    pendingApp = app
                    showAppPicker = false
                },
                onDismiss = { showAppPicker = false }
            )
        }

        pendingApp?.let { app ->
            CloneConfirmDialog(
                app = app,
                onConfirm = {
                    val ok = VirtualCore.cloneApp(app.packageName)
                    scope.launch {
                        db.clonedAppDao().insert(
                            ClonedAppEntity(
                                id = System.currentTimeMillis(),
                                label = app.label,
                                packageName = app.packageName
                            )
                        )
                    }
                    Toast.makeText(
                        context,
                        if (ok) "\"${app.label}\" clone list mein add ho gayi."
                        else "Clone add nahi ho saki.",
                        Toast.LENGTH_LONG
                    ).show()
                    pendingApp = null
                },
                onCancel = { pendingApp = null }
            )
        }
    }
}

// ---------- Home screen: grid of cloned apps + FAB ----------

@Composable
fun HomeScreen(
    title: String,
    clonedApps: List<ClonedApp>,
    onAddClick: () -> Unit,
    onRemoveClick: (ClonedApp) -> Unit,
    onTileClick: (ClonedApp) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title) })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(Icons.Filled.Add, contentDescription = "Add / Clone an app")
            }
        }
    ) { padding ->
        if (clonedApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Koi app clone nahi hui abhi.\n\"+\" dabao aur ek app select karo.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(clonedApps, key = { it.id }) { app ->
                    ClonedAppTile(
                        app = app,
                        onClick = { onTileClick(app) },
                        onRemove = { onRemoveClick(app) }
                    )
                }
            }
        }
    }
}

@Composable
fun ClonedAppTile(app: ClonedApp, onClick: () -> Unit, onRemove: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box {
            AppIcon(drawable = app.icon, sizeDp = 56)
            // Small "cloned" badge in the corner
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("2", color = MaterialTheme.colorScheme.onPrimary, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            app.label,
            fontSize = 12.sp,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

// ---------- Confirmation dialog ----------

@Composable
fun CloneConfirmDialog(
    app: InstalledApp,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { AppIcon(drawable = app.icon, sizeDp = 40) },
        title = { Text("\"${app.label}\" clone karein?") },
        text = {
            Text("Yeh app clone ho kar ek naya, alag instance banayegi. Kya aap ijazat dete hain?")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("OK, Clone Karein") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}

// ---------- Installed-apps picker (full-screen list with search) ----------

@Composable
fun AppPickerScreen(
    onAppSelected: (InstalledApp) -> Unit,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var query by remember { mutableStateOf("") }
    val allApps = remember { loadInstalledApps(context.packageManager) }

    val filtered = remember(query, allApps) {
        if (query.isBlank()) allApps
        else allApps.filter { it.label.contains(query, ignoreCase = true) }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Select an app") },
                actions = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.packageName }) { app ->
                    ListItem(
                        headlineContent = { Text(app.label) },
                        supportingContent = { Text(app.packageName, fontSize = 11.sp) },
                        leadingContent = { AppIcon(drawable = app.icon, sizeDp = 40) },
                        modifier = Modifier.clickable { onAppSelected(app) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

// ---------- Helpers ----------

@Composable
fun AppIcon(drawable: Drawable, sizeDp: Int) {
    val bitmap = remember(drawable) { drawable.toBitmap().asImageBitmap() }
    androidx.compose.foundation.Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = Modifier.size(sizeDp.dp)
    )
}

/**
 * Loads an installed app's icon by package name at runtime. Icons can't be
 * stored in Room directly, so we re-fetch them each time from
 * PackageManager — cheap, and always reflects the app's current icon.
 */
fun loadAppIcon(pm: PackageManager, packageName: String): Drawable {
    return try {
        pm.getApplicationIcon(packageName)
    } catch (e: PackageManager.NameNotFoundException) {
        pm.defaultActivityIcon
    }
}

/**
 * Returns every launchable app installed on the device (system + user apps),
 * excluding this app itself. This only reads public PackageManager info —
 * no other app's data or files are touched.
 */
fun loadInstalledApps(pm: PackageManager): List<InstalledApp> {
    val intent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }
    val resolved = pm.queryIntentActivities(intent, 0)
    return resolved
        .asSequence()
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != "com.vellora.dualapp" }
        .map { info: ApplicationInfo ->
            InstalledApp(
                label = pm.getApplicationLabel(info).toString(),
                packageName = info.packageName,
                icon = pm.getApplicationIcon(info)
            )
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}
