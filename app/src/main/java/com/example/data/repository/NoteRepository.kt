package com.example.data.repository

import com.example.data.database.*
import com.example.data.model.Attachment
import com.example.data.model.Point
import com.example.data.model.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class NoteRepository(
    private val folderDao: FolderDao,
    private val noteDao: NoteDao,
    private val notePageDao: NotePageDao
) {
    val allFolders: Flow<List<FolderEntity>> = folderDao.getAllFolders()
    val allNotes: Flow<List<NoteEntity>> = noteDao.getAllNotes()

    fun getNotesByFolder(folderId: Long): Flow<List<NoteEntity>> = noteDao.getNotesByFolder(folderId)

    suspend fun getNoteById(noteId: Long): NoteEntity? = withContext(Dispatchers.IO) {
        noteDao.getNoteById(noteId)
    }

    fun getPagesForNote(noteId: Long): Flow<List<NotePageEntity>> = notePageDao.getPagesForNote(noteId)

    suspend fun getPagesForNoteSync(noteId: Long): List<NotePageEntity> = withContext(Dispatchers.IO) {
        notePageDao.getPagesForNoteSync(noteId)
    }

    suspend fun insertFolder(folder: FolderEntity): Long = withContext(Dispatchers.IO) {
        folderDao.insertFolder(folder)
    }

    suspend fun updateFolder(folder: FolderEntity) = withContext(Dispatchers.IO) {
        folderDao.updateFolder(folder)
    }

    suspend fun deleteFolder(folder: FolderEntity) = withContext(Dispatchers.IO) {
        folderDao.deleteFolder(folder)
    }

    suspend fun insertNote(note: NoteEntity): Long = withContext(Dispatchers.IO) {
        noteDao.insertNote(note)
    }

    suspend fun updateNote(note: NoteEntity) = withContext(Dispatchers.IO) {
        noteDao.updateNote(note.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteNote(note: NoteEntity) = withContext(Dispatchers.IO) {
        noteDao.deleteNote(note)
    }

    suspend fun insertPage(page: NotePageEntity): Long = withContext(Dispatchers.IO) {
        notePageDao.insertPage(page)
    }

    suspend fun updatePage(page: NotePageEntity) = withContext(Dispatchers.IO) {
        notePageDao.updatePage(page)
    }

    suspend fun updatePages(pages: List<NotePageEntity>) = withContext(Dispatchers.IO) {
        notePageDao.updatePagesList(pages)
    }

    suspend fun deletePage(page: NotePageEntity) = withContext(Dispatchers.IO) {
        notePageDao.deletePage(page)
    }

    suspend fun deletePageByIndex(noteId: Long, pageIndex: Int) = withContext(Dispatchers.IO) {
        notePageDao.deletePageByIndex(noteId, pageIndex)
    }

    suspend fun initializeDefaultData() = withContext(Dispatchers.IO) {
        val folders = folderDao.getAllFolders().first()
        if (folders.isEmpty()) {
            // Seed folders
            val f1 = folderDao.insertFolder(FolderEntity(name = "Lecture Notes", colorHex = "#3F51B5"))
            val f2 = folderDao.insertFolder(FolderEntity(name = "Mathematics Proofs", colorHex = "#E91E63"))
            val f3 = folderDao.insertFolder(FolderEntity(name = "Quick Thoughts", colorHex = "#009688"))
            val f4 = folderDao.insertFolder(FolderEntity(name = "Assignment Drafts", colorHex = "#FF9800"))

            // Seed Note 1: Anatomy lecture note inside folder 1
            val n1 = noteDao.insertNote(
                NoteEntity(
                    folderId = f1,
                    title = "Biology: Heart Structure",
                    coverTitle = "Heart Anatomy",
                    coverColorHex = "#E3F2FD",
                    canvasType = "PAGES" // Multipage
                )
            )

            // Some drawn strokes for Anatomy Cover (simulating heart chambers & annotations)
            val heartStrokes = listOf(
                Stroke(
                    points = listOf(Point(100f, 150f), Point(120f, 120f), Point(150f, 110f), Point(180f, 130f), Point(200f, 170f), Point(190f, 210f), Point(150f, 250f), Point(110f, 210f), Point(100f, 170f)),
                    color = -23296, // Coral/Red color decimal
                    thickness = 8f,
                    tool = "PEN",
                    shape = "NONE"
                ),
                Stroke(
                    points = listOf(Point(135f, 160f), Point(150f, 190f)),
                    color = -16777216, // Black
                    thickness = 4f,
                    tool = "HIGHLIGHTER",
                    shape = "LINE"
                )
            )

            val notesAttach1 = listOf(
                Attachment(
                    id = "heart_diag",
                    name = "Anatomy Reference Diagram.png",
                    type = "IMAGE",
                    path = "https://images.unsplash.com/photo-1530026405186-ed1ea0ac7a63?w=400&auto=format&fit=crop", // placeholder heart or cell url
                    x = 80f,
                    y = 300f,
                    width = 240f,
                    height = 200f
                )
            )

            notePageDao.insertPage(
                NotePageEntity(
                    noteId = n1,
                    pageIndex = 0,
                    drawingDataJson = Stroke.listToJson(heartStrokes),
                    attachmentsJson = Attachment.listToJson(notesAttach1)
                )
            )

            // Page 2 in Anatomy Note
            val pg2Strokes = listOf(
                Stroke(
                    points = listOf(Point(50f, 80f), Point(250f, 80f)),
                    color = -16776961, // Blue
                    thickness = 12f,
                    tool = "HIGHLIGHTER"
                )
            )
            notePageDao.insertPage(
                NotePageEntity(
                    noteId = n1,
                    pageIndex = 1,
                    drawingDataJson = Stroke.listToJson(pg2Strokes),
                    attachmentsJson = "[]"
                )
            )

            // Seed Note 2: Math proofs inside folder 2 (Infinite Canvas!)
            val n2 = noteDao.insertNote(
                NoteEntity(
                    folderId = f2,
                    title = "Linear Algebra Theorem 1",
                    coverTitle = "Matrices & Vectors",
                    coverColorHex = "#FCE4EC",
                    canvasType = "INFINITE"
                )
            )

            val mathStrokes = listOf(
                Stroke(
                    points = listOf(Point(100f, 100f), Point(350f, 100f), Point(350f, 300f), Point(100f, 300f), Point(100f, 100f)),
                    color = -16724992, // Green
                    thickness = 5f,
                    tool = "SHAPE",
                    shape = "RECTANGLE"
                ),
                Stroke(
                    points = listOf(Point(120f, 150f), Point(320f, 250f)),
                    color = -16776961, // Blue
                    thickness = 6f,
                    tool = "SHAPE",
                    shape = "ARROW"
                )
            )

            val notesAttach2 = listOf(
                Attachment(
                    id = "math_pdf",
                    name = "Theorem_Derivation.pdf",
                    type = "PDF",
                    path = "linear_algebra.pdf",
                    x = 450f,
                    y = 80f,
                    width = 300f,
                    height = 360f
                )
            )

            notePageDao.insertPage(
                NotePageEntity(
                    noteId = n2,
                    pageIndex = 0,
                    drawingDataJson = Stroke.listToJson(mathStrokes),
                    attachmentsJson = Attachment.listToJson(notesAttach2)
                )
            )
        }
    }
}
