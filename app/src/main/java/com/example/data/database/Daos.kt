package com.example.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY name ASC")
    fun getAllFolders(): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity): Long

    @Update
    suspend fun updateFolder(folder: FolderEntity)

    @Delete
    suspend fun deleteFolder(folder: FolderEntity)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun getAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE folderId = :folderId ORDER BY updatedAt DESC")
    fun getNotesByFolder(folderId: Long): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :noteId")
    suspend fun getNoteById(noteId: Long): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity): Long

    @Update
    suspend fun updateNote(note: NoteEntity)

    @Delete
    suspend fun deleteNote(note: NoteEntity)
}

@Dao
interface NotePageDao {
    @Query("SELECT * FROM note_pages WHERE noteId = :noteId ORDER BY pageIndex ASC")
    fun getPagesForNote(noteId: Long): Flow<List<NotePageEntity>>

    @Query("SELECT * FROM note_pages WHERE noteId = :noteId ORDER BY pageIndex ASC")
    suspend fun getPagesForNoteSync(noteId: Long): List<NotePageEntity>

    @Query("SELECT * FROM note_pages WHERE noteId = :noteId AND pageIndex = :pageIndex LIMIT 1")
    suspend fun getPageByIndex(noteId: Long, pageIndex: Int): NotePageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPage(page: NotePageEntity): Long

    @Update
    suspend fun updatePage(page: NotePageEntity)

    @Transaction
    suspend fun updatePagesList(pages: List<NotePageEntity>) {
        pages.forEach { page ->
            updatePage(page)
        }
    }

    @Delete
    suspend fun deletePage(page: NotePageEntity)

    @Query("DELETE FROM note_pages WHERE noteId = :noteId AND pageIndex = :pageIndex")
    suspend fun deletePageByIndex(noteId: Long, pageIndex: Int)
}
