package com.mardous.booming.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Write access to files in shared storage through folders the user granted with the
 * system folder picker (persisted tree URIs of the external storage provider).
 */
object DocumentTreeAccess {

    private const val AUTHORITY = "com.android.externalstorage.documents"

    /** Document id of [file] in the external storage provider, e.g. `primary:Music/a.lrc`. */
    private fun documentIdOf(file: File): String? {
        val path = file.absolutePath
        val primaryRoot = Environment.getExternalStorageDirectory().absolutePath
        if (path == primaryRoot || path.startsWith("$primaryRoot/")) {
            return "primary:" + path.removePrefix(primaryRoot).trimStart('/')
        }
        // Removable volumes: /storage/XXXX-XXXX/...
        val match = Regex("^/storage/([^/]+)(?:/(.*))?$").find(path) ?: return null
        val volume = match.groupValues[1]
        if (volume == "emulated" || volume == "self") return null
        return volume + ":" + match.groupValues[2]
    }

    private fun covers(treeDocId: String, docId: String): Boolean {
        if (treeDocId.substringBefore(':') != docId.substringBefore(':')) return false
        val treePath = treeDocId.substringAfter(':').trimEnd('/')
        val docPath = docId.substringAfter(':')
        return treePath.isEmpty() || docPath == treePath || docPath.startsWith("$treePath/")
    }

    /** A writable document URI for [file] under one of the granted folders, or null. */
    fun findDocumentUri(context: Context, file: File): Uri? {
        val docId = documentIdOf(file) ?: return null
        val tree = context.contentResolver.persistedUriPermissions
            .filter { it.isWritePermission && it.uri.authority == AUTHORITY }
            .map { it.uri }
            .filter { runCatching { DocumentsContract.getTreeDocumentId(it) }.isSuccess }
            .firstOrNull { covers(DocumentsContract.getTreeDocumentId(it), docId) }
            ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(tree, docId)
    }

    /** Where the folder picker should open for granting access to [folder]. */
    fun initialUriFor(folder: File?): Uri? {
        val docId = folder?.let { documentIdOf(it) } ?: return null
        return DocumentsContract.buildDocumentUri(AUTHORITY, docId)
    }

    fun persist(context: Context, treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }
}
