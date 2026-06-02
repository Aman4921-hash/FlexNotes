package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.AppDatabase
import com.example.data.database.FolderEntity
import com.example.data.database.NoteEntity
import com.example.data.database.NotePageEntity
import com.example.data.model.Attachment
import com.example.data.model.Point
import com.example.data.model.Stroke
import com.example.data.repository.NoteRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class NoteViewModel(application: Application) : AndroidViewModel(application) {

    private val sharedPrefs = application.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val _isDarkMode = MutableStateFlow(sharedPrefs.getBoolean("dark_mode", false))
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    fun setDarkMode(enabled: Boolean) {
        _isDarkMode.value = enabled
        sharedPrefs.edit().putBoolean("dark_mode", enabled).apply()
    }

    private val repository: NoteRepository

    init {
        val database = AppDatabase.getDatabase(application)
        repository = NoteRepository(
            database.folderDao(),
            database.noteDao(),
            database.notePageDao()
        )
        // Seed default content if necessary
        viewModelScope.launch {
            repository.initializeDefaultData()
        }
    }

    // Folders & Notes States
    val folders: StateFlow<List<FolderEntity>> = repository.allFolders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allNotes: StateFlow<List<NoteEntity>> = repository.allNotes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active Filters
    private val _selectedFolderId = MutableStateFlow<Long?>(null)
    val selectedFolderId: StateFlow<Long?> = _selectedFolderId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Filtered Notes
    val filteredNotes: StateFlow<List<NoteEntity>> = combine(
        allNotes, _selectedFolderId, _searchQuery
    ) { notes, folderId, query ->
        var result = notes
        if (folderId != null) {
            result = result.filter { it.folderId == folderId }
        }
        if (query.isNotEmpty()) {
            result = result.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.coverTitle.contains(query, ignoreCase = true)
            }
        }
        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active Note Editor Session
    private val _activeNote = MutableStateFlow<NoteEntity?>(null)
    val activeNote: StateFlow<NoteEntity?> = _activeNote.asStateFlow()

    private val _activeNotePages = MutableStateFlow<List<NotePageEntity>>(emptyList())
    val activeNotePages: StateFlow<List<NotePageEntity>> = _activeNotePages.asStateFlow()

    private val _currentPageIndex = MutableStateFlow(0)
    val currentPageIndex: StateFlow<Int> = _currentPageIndex.asStateFlow()

    // Stroke Drawing State for Active Page (Loaded reactive buffer)
    private val _activeStrokes = MutableStateFlow<List<Stroke>>(emptyList())
    val activeStrokes: StateFlow<List<Stroke>> = _activeStrokes.asStateFlow()

    // Undo/Redo Stacking
    private val redoStrokesStack = mutableListOf<List<Stroke>>()

    // Attachment State for Active Page
    private val _activeAttachments = MutableStateFlow<List<Attachment>>(emptyList())
    val activeAttachments: StateFlow<List<Attachment>> = _activeAttachments.asStateFlow()

    // Tool Configs
    val activeTool = MutableStateFlow("PEN") // "PEN", "ERASER", "HIGHLIGHTER", "SHAPE", "LASSO"
    val activeShape = MutableStateFlow("NONE") // "NONE", "RECTANGLE", "CIRCLE", "LINE", "ARROW"
    val activePenColor = MutableStateFlow(-16777216) // Default black
    val activePencilColor = MutableStateFlow(-16777216) // Default black
    val activeShapeColor = MutableStateFlow(-16777216) // Default black
    val activeHighlighterColor = MutableStateFlow(-256) // Default yellow
    
    // Independent Tool Memory System
    val penThickness = MutableStateFlow(4f)
    val pencilThickness = MutableStateFlow(4f)
    val shapeThickness = MutableStateFlow(4f)
    val highlighterThickness = MutableStateFlow(24f)
    val eraserThickness = MutableStateFlow(40f)
    
    val activeThickness = MutableStateFlow(4f)
    val activeColor = MutableStateFlow(-16777216)
    
    fun setToolClassAndRestore(toolClass: String) {
        activeTool.value = toolClass
        if (toolClass != "SHAPE") {
            activeShape.value = "NONE"
        }
        activeThickness.value = when (toolClass) {
            "PENCIL" -> pencilThickness.value
            "HIGHLIGHTER" -> highlighterThickness.value
            "ERASER" -> eraserThickness.value
            "SHAPE" -> shapeThickness.value
            else -> penThickness.value
        }
        activeColor.value = getActiveColorForTool(toolClass)
    }

    fun getActiveColorForTool(tool: String): Int {
        return when (tool) {
            "PENCIL" -> activePencilColor.value
            "HIGHLIGHTER" -> activeHighlighterColor.value
            "SHAPE" -> activeShapeColor.value
            else -> activePenColor.value
        }
    }

    fun updateActiveThickness(thickness: Float) {
        activeThickness.value = thickness
        when (activeTool.value) {
            "PENCIL" -> pencilThickness.value = thickness
            "HIGHLIGHTER" -> highlighterThickness.value = thickness
            "ERASER" -> eraserThickness.value = thickness
            "SHAPE" -> shapeThickness.value = thickness
            "PEN" -> penThickness.value = thickness
        }
    }
    
    fun updateActiveColor(color: Int) {
        activeColor.value = color
        when (activeTool.value) {
            "PENCIL" -> activePencilColor.value = color
            "HIGHLIGHTER" -> activeHighlighterColor.value = color
            "SHAPE" -> activeShapeColor.value = color
            else -> activePenColor.value = color
        }
    }
    
    // Advanced Pen / Pencil / Brush system attributes
    val activePenSubTool = MutableStateFlow("PEN_BALLPOINT") // Default Ballpoint Pen
    val pressureSensitivityEnabled = MutableStateFlow(true)
    val strokeSmoothingEnabled = MutableStateFlow(true)
    val activeOpacity = MutableStateFlow(1.0f)
    val isFullScreen = MutableStateFlow(false)
    val temporaryHighlightedStrokes = MutableStateFlow<List<Stroke>>(emptyList())

    // Lasso Selection States
    val lassoSelectedStrokes = MutableStateFlow<List<Stroke>>(emptyList())
    val lassoSelectedAttachments = MutableStateFlow<List<Attachment>>(emptyList())
    val lassoSelectionRect = MutableStateFlow<androidx.compose.ui.geometry.Rect?>(null)

    // Stylus Support & Palm Rejection Mode
    val stylusOnlyMode = MutableStateFlow(false) // Toggle palm rejection
    val isReadingMode = MutableStateFlow(false) // Toggle reading mode

    // Infinite Canvas Coordinate States
    val panOffset = MutableStateFlow(Offset.Zero)
    val zoomScale = MutableStateFlow(1.0f)

    // Global settings
    val paperGridSpacing = MutableStateFlow(30f)
    val ruleGridThickness = MutableStateFlow(1f)
    val graphGridThickness = MutableStateFlow(1f)
    val dotGridThickness = MutableStateFlow(1.5f)
    val strokeStabilization = MutableStateFlow(1f) // Range 0f to 10f for interpolation aggressiveness
    val canvasBackgroundColor = MutableStateFlow(-1) // Default white (-1)
    val noteBackgroundColor = MutableStateFlow(-1) // Default white (-1)
    val gridColor = MutableStateFlow(-16777216) // Default black
    val canvasBackgroundImageUri = MutableStateFlow<String?>(null)

    // Folder Actions
    fun createFolder(name: String, colorHex: String) {
        viewModelScope.launch {
            repository.insertFolder(FolderEntity(name = name, colorHex = colorHex, parentFolderId = _selectedFolderId.value))
        }
    }

    fun deleteFolder(folder: FolderEntity) {
        viewModelScope.launch {
            repository.deleteFolder(folder)
            if (_selectedFolderId.value == folder.id) {
                _selectedFolderId.value = null
            }
        }
    }

    fun selectFolder(folderId: Long?) {
        _selectedFolderId.value = folderId
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // Note Actions
    fun createNote(
        title: String,
        coverTitle: String,
        folderId: Long?,
        canvasType: String,
        paperStyle: String,
        coverColorHex: String
    ) {
        viewModelScope.launch {
            val noteId = repository.insertNote(
                NoteEntity(
                    folderId = folderId,
                    title = title,
                    coverTitle = coverTitle,
                    coverColorHex = coverColorHex,
                    canvasType = canvasType,
                    paperStyle = paperStyle
                )
            )

            // Seed first blank page
            repository.insertPage(
                NotePageEntity(
                    noteId = noteId,
                    pageIndex = 0,
                    drawingDataJson = "[]",
                    attachmentsJson = "[]"
                )
            )
        }
    }

    fun deleteNote(note: NoteEntity) {
        viewModelScope.launch {
            repository.deleteNote(note)
            if (_activeNote.value?.id == note.id) {
                clearActiveNote()
            }
        }
    }

    fun moveNotesToFolder(noteIds: Set<Long>, destFolderId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            val notesList = allNotes.value
            notesList.filter { noteIds.contains(it.id) }.forEach {
                repository.updateNote(it.copy(folderId = destFolderId))
            }
        }
    }

    fun deleteNotes(noteIds: Set<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            val notesList = allNotes.value
            notesList.filter { noteIds.contains(it.id) }.forEach {
                repository.deleteNote(it)
                if (_activeNote.value?.id == it.id) {
                    clearActiveNote()
                }
            }
        }
    }

    fun copyExternalUriToLocal(uri: Uri, extension: String): String {
        val context = getApplication<Application>().applicationContext
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return uri.toString()
            val file = File(context.filesDir, "imported_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}.$extension")
            file.outputStream().use { outputStream ->
                inputStream.use { it.copyTo(outputStream) }
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            uri.toString()
        }
    }

    // Active single note page collector job reference
    private var loadPagesJob: kotlinx.coroutines.Job? = null

    // Set & Load Active Note Session
    fun loadNote(note: NoteEntity) {
        _activeNote.value = note
        _currentPageIndex.value = 0
        panOffset.value = Offset.Zero
        zoomScale.value = 1.0f
        redoStrokesStack.clear()

        loadPagesJob?.cancel()
        loadPagesJob = viewModelScope.launch {
            repository.getPagesForNote(note.id).collect { pages ->
                _activeNotePages.value = pages
                loadPageContent(pages, _currentPageIndex.value)
            }
        }
    }

    fun clearActiveNote() {
        // Auto-save existing state before clearing
        saveCurrentPageSnapshot()
        loadPagesJob?.cancel()
        loadPagesJob = null
        _activeNote.value = null
        _activeNotePages.value = emptyList()
        _activeStrokes.value = emptyList()
        _activeAttachments.value = emptyList()
        clearLassoSelection()
    }

    fun toggleOrientation() {
        val note = _activeNote.value ?: return
        viewModelScope.launch {
            val updated = note.copy(isLandscape = !note.isLandscape, updatedAt = System.currentTimeMillis())
            repository.updateNote(updated)
            _activeNote.value = updated
        }
    }

    val lassoClipboardStrokes = MutableStateFlow<List<Stroke>>(emptyList())
    val lassoClipboardAttachments = MutableStateFlow<List<Attachment>>(emptyList())

    fun copyLassoSelected() {
        lassoClipboardStrokes.value = lassoSelectedStrokes.value
        lassoClipboardAttachments.value = lassoSelectedAttachments.value
    }

    fun cutLassoSelected() {
        copyLassoSelected()
        deleteLassoSelected()
    }

    fun pasteLassoClipboard() {
        val strokes = lassoClipboardStrokes.value.map { stroke ->
            stroke.copy(points = stroke.points.map { Point(it.x + 50f, it.y + 50f, it.pressure) })
        }
        val attachments = lassoClipboardAttachments.value.map { att ->
            att.copy(id = java.util.UUID.randomUUID().toString(), x = att.x + 50f, y = att.y + 50f)
        }
        _activeStrokes.value = _activeStrokes.value + strokes
        _activeAttachments.value = _activeAttachments.value + attachments
        
        lassoSelectedStrokes.value = strokes
        lassoSelectedAttachments.value = attachments
        val allX = strokes.flatMap { s -> s.points.map { it.x } } + attachments.map { it.x } + attachments.map { it.x + it.width }
        val allY = strokes.flatMap { s -> s.points.map { it.y } } + attachments.map { it.y } + attachments.map { it.y + it.height }
        if (allX.isNotEmpty() && allY.isNotEmpty()) {
            lassoSelectionRect.value = androidx.compose.ui.geometry.Rect(allX.minOrNull() ?: 0f, allY.minOrNull() ?: 0f, allX.maxOrNull() ?: 0f, allY.maxOrNull() ?: 0f)
        }
        saveCurrentPageSnapshot()
    }

    fun clearLassoSelection() {
        lassoSelectedStrokes.value = emptyList()
        lassoSelectedAttachments.value = emptyList()
        lassoSelectionRect.value = null
    }

    fun translateLassoSelection(deltaX: Float, deltaY: Float) {
        val selectedStrokes = lassoSelectedStrokes.value
        val selectedAttachments = lassoSelectedAttachments.value
        if (selectedStrokes.isEmpty() && selectedAttachments.isEmpty()) return

        // Update selected strokes
        val updatedSelectedStrokes = selectedStrokes.map { stroke ->
            stroke.copy(points = stroke.points.map { Point(it.x + deltaX, it.y + deltaY, it.pressure) })
        }
        val strokesMap = selectedStrokes.zip(updatedSelectedStrokes).toMap()
        _activeStrokes.value = _activeStrokes.value.map { strokesMap[it] ?: it }
        lassoSelectedStrokes.value = updatedSelectedStrokes

        // Update selected attachments
        val updatedSelectedAttachments = selectedAttachments.map { att ->
            att.copy(x = att.x + deltaX, y = att.y + deltaY)
        }
        val attachmentsMap = selectedAttachments.zip(updatedSelectedAttachments).toMap()
        _activeAttachments.value = _activeAttachments.value.map { attachmentsMap[it] ?: it }
        lassoSelectedAttachments.value = updatedSelectedAttachments

        // Update rect
        lassoSelectionRect.value = lassoSelectionRect.value?.let { rect ->
            androidx.compose.ui.geometry.Rect(
                rect.left + deltaX, rect.top + deltaY,
                rect.right + deltaX, rect.bottom + deltaY
            )
        }
    }

    fun deleteLassoSelected() {
        val selectedStrokes = lassoSelectedStrokes.value
        val selectedAttachments = lassoSelectedAttachments.value
        if (selectedStrokes.isNotEmpty()) {
            _activeStrokes.value = _activeStrokes.value.filter { it !in selectedStrokes }
        }
        if (selectedAttachments.isNotEmpty()) {
            _activeAttachments.value = _activeAttachments.value.filter { it !in selectedAttachments }
        }
        clearLassoSelection()
        saveCurrentPageSnapshot()
    }

    fun duplicateLassoSelected() {
        val selectedStrokes = lassoSelectedStrokes.value
        val selectedAttachments = lassoSelectedAttachments.value
        val offset = 50f
        
        val duplicatedStrokes = selectedStrokes.map { stroke ->
            stroke.copy(
                points = stroke.points.map { Point(it.x + offset, it.y + offset, it.pressure) }
            )
        }
        val duplicatedAttachments = selectedAttachments.map { att ->
            att.copy(
                id = java.util.UUID.randomUUID().toString(),
                x = att.x + offset,
                y = att.y + offset
            )
        }
        
        if (duplicatedStrokes.isNotEmpty()) {
            _activeStrokes.value = _activeStrokes.value + duplicatedStrokes
        }
        if (duplicatedAttachments.isNotEmpty()) {
            _activeAttachments.value = _activeAttachments.value + duplicatedAttachments
        }
        clearLassoSelection()
        saveCurrentPageSnapshot()
    }

    fun moveLassoSelected(dx: Float, dy: Float) {
        val selectedStrokes = lassoSelectedStrokes.value
        val selectedAttachments = lassoSelectedAttachments.value
        
        if (selectedStrokes.isNotEmpty()) {
            val mappedStrokesList = _activeStrokes.value.map { stroke ->
                if (stroke in selectedStrokes) {
                    val newStroke = stroke.copy(
                        points = stroke.points.map { Point(it.x + dx, it.y + dy, it.pressure) }
                    )
                    newStroke
                } else {
                    stroke
                }
            }
            _activeStrokes.value = mappedStrokesList
            lassoSelectedStrokes.value = mappedStrokesList.filter { stroke ->
                selectedStrokes.any { sel ->
                    sel.color == stroke.color && sel.thickness == stroke.thickness && sel.points.size == stroke.points.size
                }
            }
        }
        
        if (selectedAttachments.isNotEmpty()) {
            val mappedAttachmentsList = _activeAttachments.value.map { att ->
                if (att in selectedAttachments) {
                    att.copy(x = att.x + dx, y = att.y + dy)
                } else {
                    att
                }
            }
            _activeAttachments.value = mappedAttachmentsList
            lassoSelectedAttachments.value = mappedAttachmentsList.filter { att ->
                selectedAttachments.any { sel -> sel.id == att.id }
            }
        }
        
        lassoSelectionRect.value = lassoSelectionRect.value?.let { rect ->
            androidx.compose.ui.geometry.Rect(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy)
        }
        saveCurrentPageSnapshot()
    }

    // Load specfic page item
    private fun loadPageContent(pages: List<NotePageEntity>, index: Int) {
        val targetPage = pages.find { it.pageIndex == index }
        if (targetPage != null) {
            _activeStrokes.value = Stroke.listFromJson(targetPage.drawingDataJson)
            _activeAttachments.value = Attachment.listFromJson(targetPage.attachmentsJson)
        } else {
            _activeStrokes.value = emptyList()
            _activeAttachments.value = emptyList()
        }
    }

    fun changePage(index: Int) {
        if (index >= 0 && index < _activeNotePages.value.size) {
            saveCurrentPageSnapshot()
            _currentPageIndex.value = index
            loadPageContent(_activeNotePages.value, index)
            redoStrokesStack.clear()
        }
    }

    fun addPage() {
        val note = _activeNote.value ?: return
        val currentSize = _activeNotePages.value.size
        viewModelScope.launch {
            saveCurrentPageSnapshot()
            repository.insertPage(
                NotePageEntity(
                    noteId = note.id,
                    pageIndex = currentSize,
                    drawingDataJson = "[]",
                    attachmentsJson = "[]"
                )
            )
            _currentPageIndex.value = currentSize
            _activeStrokes.value = emptyList()
            _activeAttachments.value = emptyList()
            redoStrokesStack.clear()
        }
    }

    fun insertPageWithPdf(pdfPath: String, title: String) {
        val note = _activeNote.value ?: return
        val application = getApplication<android.app.Application>()
        val context = application.applicationContext
        
        viewModelScope.launch(Dispatchers.IO) {
            saveCurrentPageSnapshot()
            
            try {
                val pfd = if (pdfPath.startsWith("/")) {
                    android.os.ParcelFileDescriptor.open(java.io.File(pdfPath), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                } else {
                    val uri = android.net.Uri.parse(pdfPath)
                    context.contentResolver.openFileDescriptor(uri, "r")
                }
                if (pfd != null) {
                    val renderer = android.graphics.pdf.PdfRenderer(pfd)
                    val pageCount = renderer.pageCount
                    val currentSize = _activeNotePages.value.size
                    
                    for (i in 0 until pageCount) {
                        val newAttachment = Attachment(
                            id = java.util.UUID.randomUUID().toString(),
                            name = "$title (Page ${i + 1})",
                            type = "PDF_PAGE",
                            path = pdfPath,
                            x = 0f,
                            y = 0f,
                            width = 794f,
                            height = 1123f,
                            pdfPageIndex = i
                        )
                        
                        val attachList = listOf(newAttachment)
                        val jsonAttach = org.json.JSONArray()
                        for (a in attachList) {
                            jsonAttach.put(a.toJson())
                        }
                        
                        repository.insertPage(
                            NotePageEntity(
                                noteId = note.id,
                                pageIndex = currentSize + i,
                                drawingDataJson = "[]",
                                attachmentsJson = jsonAttach.toString()
                            )
                        )
                    }
                    renderer.close()
                    pfd.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun deleteCurrentPage() {
        val note = _activeNote.value ?: return
        val pages = _activeNotePages.value
        if (pages.size <= 1) {
            Toast.makeText(getApplication(), "Notes must contain at least 1 page", Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val targetIdx = _currentPageIndex.value
            repository.deletePageByIndex(note.id, targetIdx)

            // Re-index remaining pages
            val remainingPages = repository.getPagesForNoteSync(note.id)
            val updatedPages = remainingPages.mapIndexed { idx, page ->
                page.copy(pageIndex = idx)
            }
            repository.updatePages(updatedPages)

            // Adjust active index
            val nextIdx = if (targetIdx >= remainingPages.size) remainingPages.size - 1 else targetIdx
            
            withContext(Dispatchers.Main) {
                _currentPageIndex.value = nextIdx
                loadPageContent(remainingPages, nextIdx)
                redoStrokesStack.clear()
            }
        }
    }

    val isSorting = MutableStateFlow(false)

    fun movePage(fromIndex: Int, toIndex: Int) {
        val note = _activeNote.value ?: return
        if (fromIndex == toIndex || isSorting.value) return
        
        viewModelScope.launch(Dispatchers.IO) {
            isSorting.value = true
            val pages = repository.getPagesForNoteSync(note.id).sortedBy { it.pageIndex }.toMutableList()
            if (fromIndex !in pages.indices || toIndex !in pages.indices) {
                isSorting.value = false
                return@launch
            }
            
            val pageToMove = pages.removeAt(fromIndex)
            pages.add(toIndex, pageToMove)
            
            val updatedPages = pages.mapIndexed { index, notePageEntity ->
                notePageEntity.copy(pageIndex = index)
            }
            repository.updatePages(updatedPages)
            
            withContext(Dispatchers.Main) {
                _currentPageIndex.value = toIndex
                loadPageContent(pages, toIndex)
                redoStrokesStack.clear()
                isSorting.value = false
            }
        }
    }

    fun reorderPages(orderedPagesList: List<com.example.data.database.NotePageEntity>) {
        val note = _activeNote.value ?: return
        if (isSorting.value) return
        
        viewModelScope.launch(Dispatchers.IO) {
            isSorting.value = true
            
            // Remember the current active page
            val oldIdx = _currentPageIndex.value
            val currentPages = _activeNotePages.value
            val activePageEntity = if (oldIdx in currentPages.indices) currentPages[oldIdx] else null
            
            // Update page indices in database
            val updatedPages = orderedPagesList.mapIndexed { index, notePageEntity ->
                notePageEntity.copy(pageIndex = index)
            }
            repository.updatePages(updatedPages)
            
            // Refresh
            val refreshedPages = repository.getPagesForNoteSync(note.id).sortedBy { it.pageIndex }
            
            // Find the new index of the active page
            val newIdx = if (activePageEntity != null) {
                refreshedPages.indexOfFirst { it.id == activePageEntity.id }.coerceAtLeast(0)
            } else {
                0
            }
            
            withContext(Dispatchers.Main) {
                _currentPageIndex.value = newIdx
                _activeNotePages.value = refreshedPages
                loadPageContent(refreshedPages, newIdx)
                isSorting.value = false
            }
        }
    }

    // Drawing Actions
    fun updateStrokesLive(strokes: List<Stroke>) {
        _activeStrokes.value = strokes
    }

    fun updateStrokes(strokes: List<Stroke>) {
        _activeStrokes.value = strokes
        saveCurrentPageSnapshot()
    }

    fun addStroke(stroke: Stroke) {
        val updated = _activeStrokes.value.toMutableList()
        updated.add(stroke)
        _activeStrokes.value = updated
        redoStrokesStack.clear()
        saveCurrentPageSnapshot()
    }

    fun undoLastStroke() {
        val current = _activeStrokes.value
        if (current.isNotEmpty()) {
            val updated = current.toMutableList()
            val removed = updated.removeAt(updated.size - 1)
            redoStrokesStack.add(listOf(removed))
            _activeStrokes.value = updated
            saveCurrentPageSnapshot()
        }
    }

    fun redoLastStroke() {
        if (redoStrokesStack.isNotEmpty()) {
            val toRestores = redoStrokesStack.removeAt(redoStrokesStack.size - 1)
            val updated = _activeStrokes.value.toMutableList()
            updated.addAll(toRestores)
            _activeStrokes.value = updated
            saveCurrentPageSnapshot()
        }
    }

    fun clearCanvas() {
        _activeStrokes.value = emptyList()
        _activeAttachments.value = emptyList()
        redoStrokesStack.clear()
        saveCurrentPageSnapshot()
    }

    // Attachments Actions
    fun addAttachment(attachment: Attachment) {
        val updated = _activeAttachments.value.toMutableList()
        updated.add(attachment)
        _activeAttachments.value = updated
        saveCurrentPageSnapshot()
    }

    fun updateAttachment(attachment: Attachment) {
        val updated = _activeAttachments.value.map {
            if (it.id == attachment.id) attachment else it
        }
        _activeAttachments.value = updated
        saveCurrentPageSnapshot()
    }

    fun removeAttachment(id: String) {
        val updated = _activeAttachments.value.filter { it.id != id }
        _activeAttachments.value = updated
        saveCurrentPageSnapshot()
    }

    // Database Sync Persistence
    fun saveCurrentPageSnapshot() {
        val note = _activeNote.value ?: return
        val currentIdx = _currentPageIndex.value
        val pages = _activeNotePages.value
        val targetPage = pages.find { it.pageIndex == currentIdx } ?: return

        // Capture state snapshots on the UI thread safely
        val strokesSnapshot = _activeStrokes.value
        val attachmentsSnapshot = _activeAttachments.value

        viewModelScope.launch(Dispatchers.Default) {
            val strokeJson = Stroke.listToJson(strokesSnapshot)
            val attachJson = Attachment.listToJson(attachmentsSnapshot)
            repository.updatePage(
                targetPage.copy(
                    drawingDataJson = strokeJson,
                    attachmentsJson = attachJson
                )
            )
            repository.updateNote(note) // matches timestamp
        }
    }

    // PDF Export Vector Layout Generator (Native compile with fixed A4 dimensions)
    fun exportNoteAsPdf() {
        val note = _activeNote.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val context = getApplication<Application>()
            val pagesData = repository.getPagesForNoteSync(note.id)
            val pdfDocument = PdfDocument()

            // Dynamic A4 specs (At 72 dots per inch: standard A4 is 595 x 842 points)
            val isLandscape = note.isLandscape
            val pageWidth = if (isLandscape) 842 else 595
            val pageHeight = if (isLandscape) 595 else 842
            
            // Screen layout widths used as baseline matches CanvasScreen: 1754x1240
            val canvasWidthDP = if (isLandscape) 1754f else 1240f
            
            // Scaling conversion factor from screen layout PX to PDF points
            val density = getApplication<Application>().resources.displayMetrics.density
            val scale = pageWidth.toFloat() / (canvasWidthDP * density)
            val scaleDp = pageWidth.toFloat() / canvasWidthDP

            pagesData.forEachIndexed { index, pageEntity ->
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val pdfPage = pdfDocument.startPage(pageInfo)
                val canvas = pdfPage.canvas

                // Set soft canvas background matching student themes
                val bgPaint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    style = Paint.Style.FILL
                }
                canvas.drawRect(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat(), bgPaint)

                // Optional: Draw notebook grids for authenticity
                val gridPaint = Paint().apply {
                    color = android.graphics.Color.parseColor("#E0F2F1")
                    strokeWidth = 1f
                }
                for (y in 40..pageHeight step 30) {
                    canvas.drawLine(0f, y.toFloat(), pageWidth.toFloat(), y.toFloat(), gridPaint)
                }

                // Render vector draw strokes
                val paint = Paint().apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }

                val strokes = Stroke.listFromJson(pageEntity.drawingDataJson)
                strokes.forEach { stroke ->
                    if (stroke.points.size > 1) {
                        paint.color = stroke.color
                        paint.strokeWidth = stroke.thickness * scale
                        if (stroke.tool == "HIGHLIGHTER") {
                            paint.alpha = 100 // alpha transparency
                        } else {
                            paint.alpha = 255
                        }

                        if (stroke.tool == "SHAPE") {
                            // Render pure geometric vector forms on PDF
                            val first = stroke.points.first()
                            val last = stroke.points.last()
                            when (stroke.shape) {
                                "RECTANGLE" -> {
                                    val rPaint = Paint(paint).apply { style = Paint.Style.STROKE }
                                    canvas.drawRect(first.x * scale, first.y * scale, last.x * scale, last.y * scale, rPaint)
                                }
                                "CIRCLE" -> {
                                    val rPaint = Paint(paint).apply { style = Paint.Style.STROKE }
                                    val dx = (last.x - first.x) * scale
                                    val dy = (last.y - first.y) * scale
                                    val radius = kotlin.math.sqrt(dx * dx + dy * dy)
                                    canvas.drawCircle(first.x * scale, first.y * scale, radius, rPaint)
                                }
                                "LINE", "ARROW" -> {
                                    canvas.drawLine(first.x * scale, first.y * scale, last.x * scale, last.y * scale, paint)
                                }
                            }
                        } else {
                            // Standard sketch lines
                            val path = android.graphics.Path()
                            val first = stroke.points.first()
                            path.moveTo(first.x * scale, first.y * scale)
                            for (i in 1 until stroke.points.size) {
                                val pt = stroke.points[i]
                                path.lineTo(pt.x * scale, pt.y * scale)
                            }
                            canvas.drawPath(path, paint)
                        }
                    }
                }

                // Draw attachment badges / simulated PDF covers to the final PDF Document
                val attachments = Attachment.listFromJson(pageEntity.attachmentsJson)
                val textPaint = Paint().apply {
                    color = android.graphics.Color.DKGRAY
                    textSize = 12f
                    isAntiAlias = true
                }
                val borderPaint = Paint().apply {
                    color = android.graphics.Color.GRAY
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }

                attachments.forEach { attachment ->
                    val x = attachment.x * scale
                    val y = attachment.y * scale
                    val w = attachment.width * scaleDp
                    val h = attachment.height * scaleDp

                    var drawn = false
                    if (attachment.type == "IMAGE") {
                        val bitmap = try {
                            if (attachment.path.startsWith("/")) {
                                android.graphics.BitmapFactory.decodeFile(attachment.path)
                            } else {
                                val uri = android.net.Uri.parse(attachment.path)
                                val resolver = context.contentResolver
                                val inputStream = resolver.openInputStream(uri)
                                android.graphics.BitmapFactory.decodeStream(inputStream)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            null
                        }
                        if (bitmap != null) {
                            val srcRect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
                            val destRect = android.graphics.RectF(x, y, x + w, y + h)
                            canvas.drawBitmap(bitmap, srcRect, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
                            bitmap.recycle()
                            drawn = true
                        }
                    } else if (attachment.type == "PDF_PAGE") {
                        val pdfBmp = try {
                            val pfd = if (attachment.path.startsWith("/")) {
                                android.os.ParcelFileDescriptor.open(java.io.File(attachment.path), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                            } else {
                                context.contentResolver.openFileDescriptor(android.net.Uri.parse(attachment.path), "r")
                            }
                            if (pfd != null) {
                                val renderer = android.graphics.pdf.PdfRenderer(pfd)
                                if (attachment.pdfPageIndex >= 0 && attachment.pdfPageIndex < renderer.pageCount) {
                                    val page = renderer.openPage(attachment.pdfPageIndex)
                                    val renderW = (page.width * 2f).toInt()
                                    val renderH = (page.height * 2f).toInt()
                                    val bmp = android.graphics.Bitmap.createBitmap(renderW, renderH, android.graphics.Bitmap.Config.ARGB_8888)
                                    val bCanvas = android.graphics.Canvas(bmp)
                                    bCanvas.drawColor(android.graphics.Color.WHITE)
                                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    page.close()
                                    renderer.close()
                                    pfd.close()
                                    bmp
                                } else {
                                    renderer.close()
                                    pfd.close()
                                    null
                                }
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            null
                        }
                        if (pdfBmp != null) {
                            val srcRect = android.graphics.Rect(0, 0, pdfBmp.width, pdfBmp.height)
                            val destRect = android.graphics.RectF(x, y, x + w, y + h)
                            canvas.drawBitmap(pdfBmp, srcRect, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
                            pdfBmp.recycle()
                            drawn = true
                        }
                    }

                    if (!drawn) {
                        // Draw placeholder fallback only if decoding/rendering failed
                        val boxPaint = Paint().apply {
                            color = if (attachment.type == "PDF") {
                                android.graphics.Color.parseColor("#FFF5F5") // Reddish PDF
                            } else {
                                android.graphics.Color.parseColor("#F1F8E9") // Greenish image
                            }
                            style = Paint.Style.FILL
                        }
                        canvas.drawRect(x, y, x + w, y + h, boxPaint)
                        canvas.drawRect(x, y, x + w, y + h, borderPaint)

                        // Stamp title and label
                        canvas.drawText("[ ${attachment.type} attachment ]", x + 10f, y + 25f, textPaint)
                        val label = if (attachment.name.length > 20) attachment.name.take(17) + "..." else attachment.name
                        canvas.drawText(label, x + 10f, y + 55f, textPaint)
                    }
                }

                pdfDocument.finishPage(pdfPage)
            }

            // Save PDF physically to Download folder or internal files
            try {
                val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val directory = File(downloadDir, "Exported_Notes")
                if (!directory.exists()) {
                    directory.mkdirs()
                }
                var safeName = note.title
                    .replace("[\\\\/:*?\"<>|\\x00-\\x1F]".toRegex(), "_")
                    .replace("\\s+".toRegex(), "_")
                if (safeName.trim().isEmpty() || safeName.all { it == '_' }) {
                    safeName = "Note_${note.id}"
                }
                var file = File(directory, "${safeName}_export.pdf")
                if (file.exists()) {
                    try {
                        file.delete()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                var counter = 1
                while (file.exists()) {
                    file = File(directory, "${safeName}_export_$counter.pdf")
                    counter++
                }

                var fos: FileOutputStream? = null
                var attempt = 0
                while (fos == null && attempt < 5) {
                    try {
                        fos = FileOutputStream(file)
                    } catch (e: IOException) {
                        if (e.message?.contains("EEXIST") == true || e.message?.contains("exists") == true) {
                            file = File(directory, "${safeName}_export_${System.currentTimeMillis()}_${attempt + 1}.pdf")
                            attempt++
                        } else {
                            throw e
                        }
                    }
                }
                if (fos == null) {
                    throw IOException("Could not resolve EEXIST for file path: ${file.absolutePath}")
                }
                pdfDocument.writeTo(fos)
                fos.close()
                pdfDocument.close()

                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Exported successfully: Downloads/Exported_Notes/${file.name}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Failed to export PDF: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun importPdfAsNote(uri: Uri, title: String, folderId: Long?) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            // Copy PDF to app's internal filesDir immediately to prevent permission expiration and maintain original quality
            val localPath = copyExternalUriToLocal(uri, "pdf")
            var pageCount = 1
            
            try {
                val pfd = android.os.ParcelFileDescriptor.open(java.io.File(localPath), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                if (pfd != null) {
                    val renderer = PdfRenderer(pfd)
                    pageCount = renderer.pageCount
                    renderer.close()
                    pfd.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            val noteId = repository.insertNote(
                NoteEntity(
                    folderId = folderId,
                    title = title,
                    coverTitle = "PDF: $title",
                    coverColorHex = "#FEE2E2", // Soft red for PDF cover card
                    canvasType = "PAGES"
                )
            )
            
            for (i in 0 until pageCount) {
                val name = "Page ${i + 1}"
                val attachmentsList = listOf(
                    Attachment(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name,
                        type = "PDF_PAGE",
                        path = localPath,
                        x = 5f,
                        y = 5f,
                        width = 1200f,
                        height = 1600f,
                        pdfPageIndex = i
                    )
                )
                val jsonDraw = "[]"
                val jsonAttach = Attachment.listToJson(attachmentsList)
                
                repository.insertPage(
                    NotePageEntity(
                        noteId = noteId,
                        pageIndex = i,
                        drawingDataJson = jsonDraw,
                        attachmentsJson = jsonAttach
                    )
                )
            }
            
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Successfully imported $pageCount pages from PDF!", Toast.LENGTH_LONG).show()
            }
        }
    }
}
