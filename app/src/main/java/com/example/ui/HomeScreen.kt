package com.example.ui

import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.database.FolderEntity
import com.example.data.database.NoteEntity
import com.example.ui.viewmodel.NoteViewModel
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.combinedClickable

private fun safeParseColor(hex: String?, defaultColor: Color = Color.Gray): Color {
    if (hex.isNullOrBlank()) return defaultColor
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (e: Exception) {
        try {
            Color(android.graphics.Color.parseColor("#" + hex.trim().removePrefix("#")))
        } catch (ex: Exception) {
            defaultColor
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: NoteViewModel,
    onNoteClick: (NoteEntity) -> Unit
) {
    val folders by viewModel.folders.collectAsState()
    val notes by viewModel.filteredNotes.collectAsState()
    val allNotes by viewModel.allNotes.collectAsState()
    val selectedFolderId by viewModel.selectedFolderId.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    // Back navigation for subfolders (typical file manager behavior)
    if (selectedFolderId != null) {
        val currentFolder = folders.find { it.id == selectedFolderId }
        val parentId = currentFolder?.parentFolderId
        BackHandler {
            viewModel.selectFolder(parentId)
        }
    }

    var showAddFolderDialog by remember { mutableStateOf(false) }
    var showAddNoteDialog by remember { mutableStateOf(false) }
    var showImportPdfDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedNoteIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showMoveNotesDialog by remember { mutableStateOf(false) }
    var folderMenuId by remember { mutableStateOf<String?>(null) }
    var isListView by remember { mutableStateOf(false) }

    // If multi-select is off, clear
    LaunchedEffect(multiSelectMode) {
        if (!multiSelectMode) selectedNoteIds = emptySet()
    }

    Scaffold(
        topBar = {
            if (multiSelectMode) {
                TopAppBar(
                    title = { Text("${selectedNoteIds.size} Selected") },
                    navigationIcon = {
                        IconButton(onClick = { multiSelectMode = false }) {
                            Icon(Icons.Default.Close, "Cancel")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (selectedNoteIds.isNotEmpty()) {
                                viewModel.deleteNotes(selectedNoteIds)
                                multiSelectMode = false
                            }
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Selected", tint = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { showMoveNotesDialog = true }) {
                            Text("Move")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                )
            } else {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Brush,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "FlexNotes Student",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    actions = {
                        IconButton(onClick = { showAddFolderDialog = true }) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = "Add Folder", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { showImportPdfDialog = true }) {
                            Icon(Icons.Default.Backup, contentDescription = "Import PDF Book", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddNoteDialog = true },
                icon = { Icon(Icons.Default.Gesture, contentDescription = null) },
                text = { Text("Create Note") },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Search Input Block
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                placeholder = { Text("Search covers, titles, and topics...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            // 1. Breadcrumb Path Trail Bar (Unified visual File Manager look)
            val breadcrumbs = remember(selectedFolderId, folders) {
                val list = mutableListOf<FolderEntity>()
                val visited = mutableSetOf<Long>()
                var current = folders.find { it.id == selectedFolderId }
                while (current != null && !visited.contains(current.id)) {
                    visited.add(current.id)
                    list.add(0, current)
                    current = folders.find { it.id == current.parentFolderId }
                }
                list
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = "Directory Root",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { viewModel.selectFolder(null) }
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Storage",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { viewModel.selectFolder(null) }
                )
                
                breadcrumbs.forEach { folder ->
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "/",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(safeParseColor(folder.colorHex))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = folder.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (folder.id == selectedFolderId) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { viewModel.selectFolder(folder.id) }
                    )
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // Grid / List View Toggle Button
                IconButton(onClick = { isListView = !isListView }) {
                    Icon(
                        imageVector = if (isListView) Icons.Default.GridView else Icons.Default.FormatListBulleted,
                        contentDescription = "Toggle View",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 2. Centralized Folder / File Grid View
            val listToDisplay = if (searchQuery.isNotEmpty()) {
                notes.filter { it.title.contains(searchQuery, ignoreCase = true) || it.coverTitle.contains(searchQuery, ignoreCase = true) }
            } else if (selectedFolderId == null) {
                notes.filter { it.folderId == null }
            } else {
                notes.filter { it.folderId == selectedFolderId }
            }

            LazyVerticalGrid(
                columns = if (isListView) GridCells.Fixed(1) else GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Folders block (only seen when search is empty)
                if (searchQuery.isEmpty()) {
                    val subfolders = folders.filter { it.parentFolderId == selectedFolderId }
                    if (subfolders.isNotEmpty() || selectedFolderId == null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = "DIRECTORIES & DRAWERS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.secondary,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }

                        if (subfolders.isEmpty() && selectedFolderId == null) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showAddFolderDialog = true },
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color.White,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("No folders. Tap to create customized drawer!", fontSize = 13.sp, color = Color.Gray)
                                    }
                                }
                            }
                        } else {
                            items(subfolders, key = { "folder_${it.id}" }) { folder ->
                                val noteCount = allNotes.count { it.folderId == folder.id }
                                if (isListView) {
                                    FolderListRow(
                                        folder = folder,
                                        noteCount = noteCount,
                                        onClick = { viewModel.selectFolder(folder.id) },
                                        onLongClick = { folderMenuId = folder.id.toString() },
                                        onDelete = { viewModel.deleteFolder(folder) }
                                    )
                                } else {
                                    FolderGridCard(
                                        folder = folder,
                                        noteCount = noteCount,
                                        onClick = { viewModel.selectFolder(folder.id) },
                                        onLongClick = { folderMenuId = folder.id.toString() },
                                        onDelete = { viewModel.deleteFolder(folder) }
                                    )
                                }
                            }
                        }

                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }

                // Header for Notes section
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (searchQuery.isNotEmpty()) "SEARCH RESULTS" else "NOTEBOOKS & FILES",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.secondary,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "${listToDisplay.size} file${if (listToDisplay.size != 1) "s" else ""}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                // Inline quick New Note bento
                if (searchQuery.isEmpty()) {
                    item {
                        if (isListView) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                                    .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                    .clickable { showAddNoteDialog = true }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                Text("Compile customized notebook file...", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                            }
                        } else {
                            NewCanvasBentoCard(onClick = { showAddNoteDialog = true })
                        }
                    }
                }

                if (listToDisplay.isEmpty() && searchQuery.isEmpty()) {
                    if (selectedFolderId != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "This folder is empty",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Create a note to start writing inside this directory!",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = { viewModel.selectFolder(null) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Back to Storage")
                                }
                            }
                        }
                    } else {
                        item {
                            BentoGuideCard()
                        }
                    }
                }

                // Render matching notes
                items(listToDisplay, key = { it.id }) { note ->
                    val folderOfNote = folders.find { it.id == note.folderId }
                    if (isListView) {
                        NotebookListRow(
                            note = note,
                            folder = folderOfNote,
                            isSelected = selectedNoteIds.contains(note.id),
                            onClick = {
                                if (multiSelectMode) {
                                    selectedNoteIds = if (selectedNoteIds.contains(note.id)) {
                                        selectedNoteIds - note.id
                                    } else {
                                        selectedNoteIds + note.id
                                    }
                                } else {
                                    onNoteClick(note)
                                }
                            },
                            onLongClick = {
                                if (!multiSelectMode) {
                                    multiSelectMode = true
                                    selectedNoteIds = setOf(note.id)
                                }
                            },
                            onDelete = { viewModel.deleteNote(note) }
                        )
                    } else {
                        NotebookCoverCard(
                            note = note,
                            folder = folderOfNote,
                            isSelected = selectedNoteIds.contains(note.id),
                            isMultiSelectMode = multiSelectMode,
                            onClick = {
                                if (multiSelectMode) {
                                    selectedNoteIds = if (selectedNoteIds.contains(note.id)) {
                                        selectedNoteIds - note.id
                                    } else {
                                        selectedNoteIds + note.id
                                    }
                                } else {
                                    onNoteClick(note)
                                }
                            },
                            onLongClick = {
                                if (!multiSelectMode) {
                                    multiSelectMode = true
                                    selectedNoteIds = setOf(note.id)
                                }
                            },
                            onDelete = { viewModel.deleteNote(note) }
                        )
                    }
                }
            }
        }
    }

    if (showMoveNotesDialog) {
        var localFolderIndex by remember { mutableStateOf(0) }
        AlertDialog(
            onDismissRequest = { showMoveNotesDialog = false },
            title = { Text("Move ${selectedNoteIds.size} Notes") },
            text = {
                Column {
                    Text("Select a folder destination:", fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    ScrollableTabRow(
                         selectedTabIndex = localFolderIndex,
                         edgePadding = 0.dp
                    ) {
                         Tab(selected = localFolderIndex == 0, onClick = { localFolderIndex = 0 }) { Text("No Folder", modifier = Modifier.padding(16.dp)) }
                         folders.forEachIndexed { idx, folder ->
                             val path = remember(folder, folders) {
                                 val pList = mutableListOf<String>()
                                 val visited = mutableSetOf<Long>()
                                 var cur: FolderEntity? = folder
                                 while (cur != null && !visited.contains(cur.id)) {
                                     visited.add(cur.id)
                                     pList.add(0, cur.name)
                                     cur = folders.find { it.id == cur?.parentFolderId }
                                 }
                                 pList.joinToString(" / ")
                             }
                             Tab(selected = localFolderIndex == idx + 1, onClick = { localFolderIndex = idx + 1 }) { Text(path, modifier = Modifier.padding(16.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                         }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val folderId = if (localFolderIndex == 0) null else folders.getOrNull(localFolderIndex - 1)?.id
                    viewModel.moveNotesToFolder(selectedNoteIds, folderId)
                    showMoveNotesDialog = false
                    multiSelectMode = false
                }) {
                    Text("Move")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMoveNotesDialog = false }) { Text("Cancel") }
            }
        )
    }

    // dialogs
    if (showAddFolderDialog) {
        AddFolderDialog(
            onDismiss = { showAddFolderDialog = false },
            onConfirm = { name, colorHex ->
                viewModel.createFolder(name, colorHex)
                showAddFolderDialog = false
            }
        )
    }

    if (showAddNoteDialog) {
        val folderOptions = listOf(null) + folders
        AddNoteDialog(
            folders = folderOptions,
            onDismiss = { showAddNoteDialog = false },
            onConfirm = { title, coverTitle, folderId, canvasType, paperStyle, coverColorHex ->
                viewModel.createNote(title, coverTitle, folderId, canvasType, paperStyle, coverColorHex)
                showAddNoteDialog = false
            }
        )
    }

    if (showSettingsDialog) {
        SettingsDialog(
            viewModel = viewModel,
            onDismiss = { showSettingsDialog = false }
        )
    }

    if (showImportPdfDialog) {
        val folderOptions = listOf(null) + folders
        ImportPdfDialog(
            folders = folderOptions,
            onDismiss = { showImportPdfDialog = false },
            onConfirm = { pdfTitle, folderId, uri ->
                viewModel.importPdfAsNote(uri, pdfTitle, folderId)
                showImportPdfDialog = false
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotebookCoverCard(
    note: NoteEntity,
    folder: FolderEntity?,
    isSelected: Boolean = false,
    isMultiSelectMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onDelete: () -> Unit
) {
    val coverBgColor = try {
        Color(android.graphics.Color.parseColor(note.coverColorHex))
    } catch (e: Exception) {
        Color(0xFFFFFBEB) // Default warm organic cream
    }

    val folderColor = if (folder != null) {
        try {
            Color(android.graphics.Color.parseColor(folder.colorHex))
        } catch (e: Exception) {
            MaterialTheme.colorScheme.primary
        }
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    }

    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .shadow(2.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(if (isSelected) 3.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // COVER PREVIEW AREA
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(145.dp)
                    .background(coverBgColor)
            ) {
                // Background patterns for canvas styles
                if (note.canvasType == "INFINITE") {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "∞ Canvas",
                                fontSize = 10.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        repeat(5) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f), thickness = 1.dp)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // Binder stripe along spine
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(6.dp)
                        .background(folderColor)
                )

                // Spiral rings along spine
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    repeat(5) {
                        Box(
                            modifier = Modifier
                                .offset(x = 3.dp)
                                .size(width = 8.dp, height = 3.dp)
                                .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(1.dp))
                        )
                    }
                }

                // Cover label
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.92f))
                        .border(1.dp, Color.Black.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = note.coverTitle,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Options trigger (Top-right)
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = Color.DarkGray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Open Canvas") },
                            onClick = {
                                showMenu = false
                                onClick()
                            },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete This Canvas") },
                            onClick = {
                                showMenu = false
                                onDelete()
                            },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            }

            // TEXT CONTENT COMPARTMENT (Themed section)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = note.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val dateStr = remember(note.updatedAt) {
                        SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(note.updatedAt))
                    }
                    Text(
                        text = "Updated $dateStr",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    if (folder != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(folderColor.copy(alpha = 0.15f))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = folder.name,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = folderColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 50.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NewCanvasBentoCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .shadow(1.dp, CircleShape)
                    .background(MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "New Canvas",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tap to sketch",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun BentoGuideCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .shadow(1.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "STUDENT GUIDE",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            Text(
                text = "Sketch with anti-aliasing stylus paths, shapes, and custom layouts instantly.",
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Done,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Palm Rejection Enabled",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.Gray
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var folderName by remember { mutableStateOf("") }
    val folderColors = listOf("#3F51B5", "#E91E63", "#009688", "#FF9800", "#4CAF50", "#9C27B0", "#F44336", "#607D8B")
    var selectedColor by remember { mutableStateOf(folderColors[0]) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Student Folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder Name") },
                    placeholder = { Text("e.g. Physics, Chemistry...") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Choose Folder Label Color", fontSize = 14.sp, fontWeight = FontWeight.Medium)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    folderColors.forEach { colorStr ->
                        val isSelected = selectedColor == colorStr
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(safeParseColor(colorStr))
                                .border(
                                    if (isSelected) 3.dp else 0.dp,
                                    if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    CircleShape
                                )
                                .clickable { selectedColor = colorStr }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (folderName.isNotBlank()) onConfirm(folderName, selectedColor) },
                enabled = folderName.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNoteDialog(
    folders: List<FolderEntity?>,
    onDismiss: () -> Unit,
    onConfirm: (title: String, coverTitle: String, folderId: Long?, canvasType: String, paperStyle: String, coverColorHex: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var coverTitle by remember { mutableStateOf("") }
    var selectedFolderIndex by remember { mutableStateOf(0) }
    var selectedCanvasType by remember { mutableStateOf("PAGES") } // Default to PAGES
    var selectedPaperStyle by remember { mutableStateOf("BLANK") }

    val noteCoverColors = listOf("#E3F2FD", "#FCE4EC", "#E8F5E9", "#FFFDE7", "#F3E5F5", "#FDF2E9", "#263238")
    var selectedCoverColor by remember { mutableStateOf(noteCoverColors[0]) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "New Notebook",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            if (title.isNotBlank()) {
                                val selFolderId = folders.getOrNull(selectedFolderIndex)?.id
                                onConfirm(
                                    title,
                                    if (coverTitle.isBlank()) title else coverTitle,
                                    selFolderId,
                                    selectedCanvasType,
                                    selectedPaperStyle,
                                    selectedCoverColor
                                )
                            }
                        },
                        enabled = title.isNotBlank()
                    ) {
                        Text("Compile Book")
                    }
                }
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        val oldTitle = title
                        title = it
                        if (coverTitle.isEmpty() || coverTitle == oldTitle) {
                            coverTitle = it
                        }
                    },
                    label = { Text("Notebook Title") },
                    placeholder = { Text("e.g. Calculus Practice Proofs") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = coverTitle,
                    onValueChange = { coverTitle = it },
                    label = { Text("Notebook Cover Title Label") },
                    placeholder = { Text("Short visual label on front cover") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Folder Selection Chips/Dropdown
                Text("Place in Folder", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                ScrollableTabRow(
                    selectedTabIndex = selectedFolderIndex,
                    edgePadding = 0.dp,
                    divider = {},
                    indicator = {}
                ) {
                    folders.forEachIndexed { index, folder ->
                        val pathName = remember(folder, folders) {
                            if (folder == null) "No Folder"
                            else {
                                val pList = mutableListOf<String>()
                                val visited = mutableSetOf<Long>()
                                var cur: FolderEntity? = folder
                                while (cur != null && !visited.contains(cur.id)) {
                                    visited.add(cur.id)
                                    pList.add(0, cur.name)
                                    val parentId = cur.parentFolderId
                                    cur = if (parentId != null) folders.find { it?.id == parentId } else null
                                }
                                pList.joinToString(" / ")
                            }
                        }
                        FilterChip(
                            selected = selectedFolderIndex == index,
                            onClick = { selectedFolderIndex = index },
                            label = { Text(pathName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }

                // Choose Cover Background Color
                Text("Choose Front Cover Texture Color", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    noteCoverColors.forEach { colorHex ->
                        val isSelected = selectedCoverColor == colorHex
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(safeParseColor(colorHex))
                                .border(
                                    if (isSelected) 3.dp else 1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.onSurface else Color.LightGray,
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { selectedCoverColor = colorHex }
                        )
                    }
                }

                // Paper Background Pattern Selector
                Text("Paper Style", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                ScrollableTabRow(
                    selectedTabIndex = listOf("BLANK", "RULED", "GRAPH", "DOT").indexOf(selectedPaperStyle).coerceAtLeast(0),
                    edgePadding = 0.dp,
                    divider = {},
                    indicator = {}
                ) {
                    listOf("Blank", "College Ruled", "Graph Grid", "Dotted").forEachIndexed { index, styleName ->
                        val code = listOf("BLANK", "RULED", "GRAPH", "DOT")[index]
                        FilterChip(
                            selected = selectedPaperStyle == code,
                            onClick = { selectedPaperStyle = code },
                            label = { Text(styleName) },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportPdfDialog(
    folders: List<FolderEntity?>,
    onDismiss: () -> Unit,
    onConfirm: (title: String, folderId: Long?, uri: android.net.Uri) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var selectedFolderIndex by remember { mutableStateOf(0) }
    var fileUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val context = LocalContext.current

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        fileUri = uri
        if (uri != null && title.isEmpty()) {
            val lastSegment = uri.lastPathSegment ?: "Imported_PDF"
            val rawName = if (lastSegment.contains("/")) lastSegment.substringAfterLast("/") else lastSegment
            title = rawName.substringBeforeLast(".")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Import PDF",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val uri = fileUri
                            if (title.isNotBlank() && uri != null) {
                                val selFolderId = folders.getOrNull(selectedFolderIndex)?.id
                                onConfirm(title, selFolderId, uri)
                            }
                        },
                        enabled = title.isNotBlank() && fileUri != null
                    ) {
                        Text("Import Pages")
                    }
                }
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.wrapContentHeight()
            ) {
                Text(
                    "Each page of the PDF will be imported as one page of your notebook.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )

                Button(
                    onClick = { launcher.launch("application/pdf") },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (fileUri == null) "Select PDF File" else "PDF Selected!")
                }

                if (fileUri != null) {
                    Text(
                        "PDF File: ${fileUri?.lastPathSegment ?: "Selected"}",
                        fontSize = 11.sp,
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Notebook Title") },
                    placeholder = { Text("e.g. Physics lecture slides") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Folder Selection
                Text("Place in Folder", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                ScrollableTabRow(
                    selectedTabIndex = selectedFolderIndex,
                    edgePadding = 0.dp,
                    divider = {},
                    indicator = {}
                ) {
                    folders.forEachIndexed { index, folder ->
                        val pathName = remember(folder, folders) {
                            if (folder == null) "No Folder"
                            else {
                                val pList = mutableListOf<String>()
                                val visited = mutableSetOf<Long>()
                                var cur: FolderEntity? = folder
                                while (cur != null && !visited.contains(cur.id)) {
                                    visited.add(cur.id)
                                    pList.add(0, cur.name)
                                    val parentId = cur.parentFolderId
                                    cur = if (parentId != null) folders.find { it?.id == parentId } else null
                                }
                                pList.joinToString(" / ")
                            }
                        }
                        FilterChip(
                            selected = selectedFolderIndex == index,
                            onClick = { selectedFolderIndex = index },
                            label = { Text(pathName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    viewModel: com.example.ui.viewmodel.NoteViewModel,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("App Settings", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.5f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Appearance
                Text("Appearance", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                val darkMode by viewModel.isDarkMode.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Dark Mode", fontSize = 14.sp)
                    Switch(checked = darkMode, onCheckedChange = { viewModel.setDarkMode(it) })
                }
                
                // Writing Preferences
                Text("Writing Preferences", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                var smoothing by remember { mutableStateOf(true) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Handwriting Smoothing (Bezier interpolation)", fontSize = 14.sp)
                    Switch(checked = smoothing, onCheckedChange = { smoothing = it })
                }
                var hardwarePalmRejection by remember { mutableStateOf(true) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Native OS Palm Rejection", fontSize = 14.sp)
                    Switch(checked = hardwarePalmRejection, onCheckedChange = { hardwarePalmRejection = it })
                }
                
                Text(
                    "Note: Settings are currently saved per session and are applied directly onto the canvas framework.",
                    fontSize = 11.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = Color.Gray
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderGridCard(
    folder: FolderEntity,
    noteCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(105.dp)
            .shadow(1.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = safeParseColor(folder.colorHex),
                        modifier = Modifier.size(32.dp)
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(safeParseColor(folder.colorHex))
                    )
                }
                
                Column {
                    Text(
                        text = folder.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$noteCount item${if (noteCount != 1) "s" else ""}",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }
            
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Default.MoreVert, "Options", tint = Color.Gray, modifier = Modifier.size(14.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete Folder") },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderListRow(
    folder: FolderEntity,
    noteCount: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = safeParseColor(folder.colorHex),
            modifier = Modifier.size(32.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.name,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$noteCount item${if (noteCount != 1) "s" else ""}",
                fontSize = 11.sp,
                color = Color.Gray
            )
        }
        
        Box(modifier = Modifier) {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, "Options", tint = Color.Gray, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Delete Folder") },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotebookListRow(
    note: NoteEntity,
    folder: FolderEntity?,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onDelete: () -> Unit
) {
    val coverBgColor = try {
        Color(android.graphics.Color.parseColor(note.coverColorHex))
    } catch (e: Exception) {
        Color(0xFFFFFBEB)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(
                if (isSelected) 2.dp else 1.dp,
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(12.dp)
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(coverBgColor)
                .border(1.dp, Color.Black.copy(alpha = 0.1f), RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = note.title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val dateStr = remember(note.updatedAt) {
                SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(note.updatedAt))
            }
            Text(
                text = "Updated: $dateStr",
                fontSize = 11.sp,
                color = Color.Gray
            )
        }
        
        if (folder != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(safeParseColor(folder.colorHex).copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = folder.name,
                    color = safeParseColor(folder.colorHex),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
        }
    }
}


