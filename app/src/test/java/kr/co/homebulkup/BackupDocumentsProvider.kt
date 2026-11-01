package kr.co.homebulkup

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import java.io.File

/** File-backed SAF test provider. ContentProvider bridges Robolectric's legacy query
 * dispatch, which DocumentsProvider on API 26+ rejects before reaching queryDocument. */
class BackupDocumentsProvider : ContentProvider() {
    private val files = linkedMapOf<String, Pair<String, File>>()
    private var serial = 0
    override fun onCreate() = true
    fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf("root_id", "document_id", "title", "flags")).apply { addRow(arrayOf("root", "root", "Test backups", 0)) }
    fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = cursor(projection).apply { addDocument(this, documentId) }
    fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = cursor(projection).apply { files.keys.forEach { addDocument(this, it) } }
    fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        check(parentDocumentId == "root")
        val id = "backup-${++serial}"
        files[id] = displayName to File(context!!.cacheDir, id).apply { writeText("") }
        return id
    }
    fun deleteDocument(documentId: String) { files.remove(documentId)?.second?.delete() }
    fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(files.getValue(documentId).second, ParcelFileDescriptor.parseMode(mode))
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
        if (uri.lastPathSegment == "children") queryChildDocuments("root", projection, sortOrder)
        else queryDocument(DocumentsContract.getDocumentId(uri), projection)
    override fun getType(uri: Uri): String = if (DocumentsContract.getDocumentId(uri) == "root") Document.MIME_TYPE_DIR else "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { deleteDocument(DocumentsContract.getDocumentId(uri)); return 1 }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = openDocument(DocumentsContract.getDocumentId(uri), mode, null)
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uri = extras?.getParcelable<Uri>("uri") ?: error("Document URI missing: $method")
        return when (method) {
            "android:createDocument" -> {
                val id = createDocument(DocumentsContract.getDocumentId(uri), "application/json", extras.getString(Document.COLUMN_DISPLAY_NAME)!!)
                Bundle().apply { putParcelable("uri", DocumentsContract.buildDocumentUri(uri.authority!!, id)) }
            }
            "android:deleteDocument" -> { deleteDocument(DocumentsContract.getDocumentId(uri)); Bundle() }
            else -> error("Unexpected document operation: $method")
        }
    }
    private fun cursor(projection: Array<out String>?): MatrixCursor = MatrixCursor(projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED))
    private fun addDocument(cursor: MatrixCursor, id: String) {
        val row = cursor.newRow()
        cursor.columnNames.forEach { column -> row.add(when (column) {
            Document.COLUMN_DOCUMENT_ID -> id
            Document.COLUMN_DISPLAY_NAME -> if (id == "root") "Backups" else files.getValue(id).first
            Document.COLUMN_MIME_TYPE -> if (id == "root") Document.MIME_TYPE_DIR else "application/json"
            Document.COLUMN_FLAGS -> if (id == "root") Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
            Document.COLUMN_SIZE -> if (id == "root") 0L else files.getValue(id).second.length()
            Document.COLUMN_LAST_MODIFIED -> if (id == "root") 0L else files.getValue(id).second.lastModified()
            else -> null
        }) }
    }
}
