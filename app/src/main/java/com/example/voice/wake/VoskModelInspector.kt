package com.example.voice.wake

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream

data class VoskModelInspectionResult(
    val isReady: Boolean,
    val sourceLabel: String,
    val resolvedPath: String?,
    val totalSizeBytes: Long,
    val supportsGrammarMode: Boolean,
    val detectedLanguageHint: String,
    val missingFiles: List<String>,
    val diagnosticMessage: String
) {
    val formattedSizeMb: String
        get() = if (totalSizeBytes <= 0L) "0 MB" else "${"%.1f".format(totalSizeBytes / (1024.0 * 1024.0))} MB"
}

/**
 * Inspects actual Vosk model files available in app-private storage (imported via SAF)
 * or bundled in APK assets—without assuming or hardcoding any guessed model path.
 */
object VoskModelInspector {

    private val IGNORED_SYSTEM_ASSET_DIRS = setOf("images", "webkit", "geoid_height_map", "dexopt")

    fun inspect(context: Context): VoskModelInspectionResult {
        val appContext = context.applicationContext

        // 1. Check user-imported model directory under filesDir/vosk_model
        val importedRoot = File(appContext.filesDir, "vosk_model")
        if (importedRoot.exists() && importedRoot.isDirectory) {
            val candidateDir = resolveModelRootDir(importedRoot)
            if (candidateDir != null) {
                return validateDiskModelDir(
                    dir = candidateDir,
                    sourceLabel = "Imported Storage (${candidateDir.name})"
                )
            }
        }

        // 2. Dynamically inspect bundled APK assets without assuming any hardcoded folder name
        val assetDirs = runCatching { appContext.assets.list("")?.toList().orEmpty() }.getOrDefault(emptyList())
        for (entry in assetDirs) {
            if (entry in IGNORED_SYSTEM_ASSET_DIRS || entry.contains(".")) continue
            val subEntries = runCatching { appContext.assets.list(entry)?.toList().orEmpty() }.getOrDefault(emptyList())
            val looksLikeVosk = subEntries.contains("am") && subEntries.contains("conf") && subEntries.contains("graph")
            if (looksLikeVosk) {
                val cachedAssetDir = File(File(appContext.filesDir, "vosk_assets_cache"), entry)
                copyAssetFolderIfNeeded(appContext, entry, cachedAssetDir)
                return validateDiskModelDir(
                    dir = cachedAssetDir,
                    sourceLabel = "Bundled Assets ($entry)"
                )
            }
        }

        return VoskModelInspectionResult(
            isReady = false,
            sourceLabel = "Not Found",
            resolvedPath = null,
            totalSizeBytes = 0L,
            supportsGrammarMode = false,
            detectedLanguageHint = "Unknown",
            missingFiles = listOf("am/final.mdl", "conf/mfcc.conf", "graph/Gr.fst or graph/HCLG.fst"),
            diagnosticMessage = "No Vosk model found in project assets or imported storage. Import an unzipped Vosk model folder (e.g., vosk-model-small-en-in) to activate offline wake detection."
        )
    }

    /**
     * Imports an unzipped Vosk model folder chosen via Android's Play-compliant
     * Storage Access Framework (OpenDocumentTree) into app-private storage.
     */
    fun importModelFromTreeUri(context: Context, treeUri: Uri): Result<VoskModelInspectionResult> {
        val appContext = context.applicationContext
        val destRoot = File(appContext.filesDir, "vosk_model")
        val staging = File(appContext.filesDir, "vosk_model_staging")
        staging.deleteRecursively()
        staging.mkdirs()

        return runCatching {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            copyDocumentTreeRecursive(appContext, treeUri, rootDocId, staging)

            val candidate = resolveModelRootDir(staging)
                ?: throw IllegalArgumentException(
                    "Selected folder does not contain Kaldi/Vosk subdirectories (am/, conf/, graph/)."
                )
            val validation = validateDiskModelDir(candidate, "Imported Storage (${candidate.name})")
            if (!validation.isReady) {
                throw IllegalArgumentException(validation.diagnosticMessage)
            }

            destRoot.deleteRecursively()
            if (!candidate.renameTo(destRoot)) {
                candidate.copyRecursively(destRoot, overwrite = true)
            }
            staging.deleteRecursively()
            inspect(appContext)
        }.onFailure {
            staging.deleteRecursively()
        }
    }

    fun clearImportedModel(context: Context) {
        File(context.applicationContext.filesDir, "vosk_model").deleteRecursively()
    }

    private fun resolveModelRootDir(root: File): File? {
        if (hasVoskSubdirs(root)) return root
        val children = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        return children.firstOrNull { hasVoskSubdirs(it) }
    }

    private fun hasVoskSubdirs(dir: File): Boolean =
        File(dir, "am").isDirectory && File(dir, "conf").isDirectory && File(dir, "graph").isDirectory

    internal fun validateDiskModelDir(dir: File, sourceLabel: String): VoskModelInspectionResult {
        val missing = mutableListOf<String>()
        val finalMdl = File(dir, "am/final.mdl")
        if (!finalMdl.exists()) missing.add("am/final.mdl")

        val mfccConf = File(dir, "conf/mfcc.conf")
        val modelConf = File(dir, "conf/model.conf")
        if (!mfccConf.exists() && !modelConf.exists()) missing.add("conf/mfcc.conf or conf/model.conf")

        val grFst = File(dir, "graph/Gr.fst")
        val hclgFst = File(dir, "graph/HCLG.fst")
        val hasGraph = grFst.exists() || hclgFst.exists()
        if (!hasGraph) missing.add("graph/Gr.fst or graph/HCLG.fst")

        val totalSize = computeDirSize(dir)
        val supportsGrammar = grFst.exists()
        val langHint = inferLanguageHint(dir.name)
        val ready = missing.isEmpty()

        val diagnostic = if (ready) {
            val modeNote = if (supportsGrammar) {
                "Grammar mode supported (Gr.fst present — low CPU wake spotting)."
            } else {
                "Full-graph model (HCLG.fst only — higher CPU; runs phrase matching without custom grammar)."
            }
            "Vosk model verified ($langHint, ${"%.1f".format(totalSize / (1024.0 * 1024.0))} MB). $modeNote"
        } else {
            "Incomplete Vosk model in ${dir.name}. Missing: ${missing.joinToString(", ")}"
        }

        return VoskModelInspectionResult(
            isReady = ready,
            sourceLabel = sourceLabel,
            resolvedPath = dir.absolutePath,
            totalSizeBytes = totalSize,
            supportsGrammarMode = supportsGrammar,
            detectedLanguageHint = langHint,
            missingFiles = missing,
            diagnosticMessage = diagnostic
        )
    }

    private fun inferLanguageHint(folderName: String): String {
        val lower = folderName.lowercase()
        return when {
            "en-in" in lower -> "English (India)"
            "en-us" in lower -> "English (US)"
            "hi" in lower -> "Hindi"
            "es" in lower -> "Spanish"
            "fr" in lower -> "French"
            else -> "Local Vosk Model"
        }
    }

    private fun computeDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun copyAssetFolderIfNeeded(context: Context, assetPath: String, destDir: File) {
        if (File(destDir, "am/final.mdl").exists()) return
        destDir.mkdirs()
        copyAssetRecursive(context, assetPath, destDir)
    }

    private fun copyAssetRecursive(context: Context, assetPath: String, dest: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
        } else {
            dest.mkdirs()
            for (child in children) {
                copyAssetRecursive(context, "$assetPath/$child", File(dest, child))
            }
        }
    }

    private fun copyDocumentTreeRecursive(
        context: Context,
        treeUri: Uri,
        parentDocId: String,
        targetDir: File
    ) {
        val targetCanonical = targetDir.canonicalPath
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val docId = cursor.getString(idIdx)
                val name = cursor.getString(nameIdx) ?: continue
                val mime = cursor.getString(mimeIdx) ?: ""

                // Prevent path traversal from malicious document names
                val sanitizedName = name.replace("/", "_").replace("..", "_")
                val destFile = File(targetDir, sanitizedName)
                if (!destFile.canonicalPath.startsWith(targetCanonical)) continue

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    destFile.mkdirs()
                    copyDocumentTreeRecursive(context, treeUri, docId, destFile)
                } else {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    context.contentResolver.openInputStream(docUri)?.use { input ->
                        FileOutputStream(destFile).use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }
}
