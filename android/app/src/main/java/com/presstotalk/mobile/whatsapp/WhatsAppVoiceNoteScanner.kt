package com.presstotalk.mobile.whatsapp

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/** One audio file found under the granted WhatsApp media folder. */
data class VoiceNoteFile(
    val uri: Uri,
    val name: String,
    val folderName: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

/** Files sharing a parent folder - WhatsApp's closest thing to grouping. */
data class VoiceNoteGroup(
    val folderName: String,
    val files: List<VoiceNoteFile>,
)

/**
 * Finds audio files under a SAF-granted WhatsApp media folder tree.
 *
 * Uses [DocumentsContract] cursor queries instead of [androidx.documentfile.provider.DocumentFile],
 * which does one ContentResolver round-trip per file. A cursor returns all
 * children in a single query, making scans 10-50x faster on folders with
 * hundreds of voice notes.
 */
object WhatsAppVoiceNoteScanner {

    private const val TAG = "VoiceNoteScanner"

    private val AUDIO_EXTENSIONS = setOf("opus", "ogg", "m4a", "aac", "mp3", "wav", "amr")

    private const val MAX_DEPTH = 6

    private val PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    fun scan(context: Context, treeUri: Uri): List<VoiceNoteGroup> {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val found = mutableListOf<VoiceNoteFile>()
        walkCursor(context, treeUri, rootDocId, parentName = null, depth = 0, found)
        return found
            .groupBy { it.folderName }
            .map { (folder, files) -> VoiceNoteGroup(folder, files.sortedByDescending { it.lastModifiedMs }) }
            .sortedByDescending { it.folderName }
    }

    private fun walkCursor(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        parentName: String?,
        depth: Int,
        out: MutableList<VoiceNoteFile>,
    ) {
        if (depth > MAX_DEPTH) return

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cursor: Cursor?
        try {
            cursor = context.contentResolver.query(childrenUri, PROJECTION, null, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot list children of $parentDocId", e)
            return
        }

        cursor?.use {
            val idCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modCol = it.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (it.moveToNext()) {
                val docId = it.getString(idCol)
                val name = it.getString(nameCol) ?: continue
                val mime = it.getString(mimeCol) ?: ""

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkCursor(context, treeUri, docId, parentName = name, depth + 1, out)
                } else {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in AUDIO_EXTENSIONS) {
                        out += VoiceNoteFile(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                            name = name,
                            folderName = parentName ?: "Root",
                            sizeBytes = if (sizeCol >= 0) it.getLong(sizeCol) else 0L,
                            lastModifiedMs = if (modCol >= 0) it.getLong(modCol) else 0L,
                        )
                    }
                }
            }
        }
    }

    /**
     * Best-effort human-readable path for the metadata dialog.
     *
     * SAF document IDs on primary storage look like
     * `primary:Android/media/com.whatsapp/WhatsApp/Media/.../file.opus`, which
     * is a real path but not guaranteed for every provider - falls back to the
     * raw URI when the format is unrecognised.
     */
    fun readablePath(uri: Uri): String {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return uri.toString()
        val parts = docId.split(":", limit = 2)
        return if (parts.size == 2) {
            val volume = if (parts[0] == "primary") "Internal storage" else parts[0]
            "$volume/${parts[1]}"
        } else {
            docId
        }
    }
}
