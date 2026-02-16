package com.nexa.demo.interpretation.bean

import android.content.Context
import com.nexa.demo.interpretation.utils.ModelFileListingUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DownloadFileConfig(
    val name: String,
    val path: String = "",
    val url: String? = null
)

@Serializable
data class ModelData(
    val id: String,
    val displayName: String,
    val modelName: String,
    val type: String? = null,
    val versionCode: Int = 0,
    val baseUrl: String? = null,
    val modelUrl: String? = null,
    val files: List<DownloadFileConfig>? = null
)

@Serializable
data class NexaManifestBean(
    val ModelName: String? = null,
    val ModelType: String? = null,
    val PluginId: String? = null
)

data class DownloadableFile(
    val file: File,
    val url: String
)

data class DownloadableFileWithFallback(
    val file: File,
    val primaryUrl: String,
    val fallbackUrl: String
)

fun ModelData.modelDir(context: Context): File =
    if (versionCode == 1) {
        File(context.filesDir, "models/$id").apply { if (!exists()) mkdirs() }
    } else {
        File(context.filesDir, "models").apply { if (!exists()) mkdirs() }
    }

fun ModelData.modelFile(context: Context): File? =
    modelUrl?.takeIf { it.isNotBlank() }?.let {
        File(modelDir(context), modelName)
    }

private fun ModelData.getRealUrl(url: String) = if (baseUrl.isNullOrEmpty()) {
    url
} else {
    if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
        url
    } else {
        if (baseUrl.endsWith("/")) "$baseUrl$url" else "$baseUrl/$url"
    }
}

/**
 * Checks if this model is an NPU model.
 * NPU models are identified by:
 * - "NPU" or "npu" (case-insensitive) in the model id, OR
 * - ".nexa" suffix in modelName
 */
fun ModelData.isNpuModel(): Boolean {
    return id.contains("NPU", ignoreCase = true) || 
           id.contains("npu", ignoreCase = true) ||
           modelName.endsWith(".nexa", ignoreCase = true)
}

/**
 * Creates downloadable files list from explicit files configuration.
 * Used when files are listed in model_list.json.
 */
fun ModelData.downloadableFiles(modelDir: File): List<DownloadableFile> {
    val result = mutableListOf<DownloadableFile>()
    
    // Add main model file
    modelUrl?.takeIf { it.isNotBlank() }?.let {
        result.add(DownloadableFile(File(modelDir, modelName), getRealUrl(it)))
    }
    
    // Add additional files
    files?.forEach { fileConfig ->
        val fileName = if (fileConfig.path.isNotEmpty()) {
            fileConfig.path + File.separator + fileConfig.name
        } else {
            fileConfig.name
        }
        val url = if (fileConfig.url.isNullOrEmpty()) {
            getRealUrl(fileConfig.name)
        } else {
            getRealUrl(fileConfig.url)
        }
        result.add(DownloadableFile(File(modelDir, fileName), url))
    }
    
    return result
}

/**
 * Creates downloadable files list with fallback URLs from a dynamically fetched file list.
 * Used for NPU models where files are fetched from S3/HF.
 */
fun ModelData.downloadableFilesWithFallback(
    modelDir: File,
    fileNames: List<String>,
    useHfUrls: Boolean = false
): List<DownloadableFileWithFallback> {
    val repoId = if (!baseUrl.isNullOrEmpty()) {
        ModelFileListingUtil.getHfRepoId(baseUrl)
    } else {
        "NexaAI/$id"
    }
    
    return fileNames.map { fileName ->
        val s3Url = if (baseUrl.isNullOrEmpty()) {
            fileName
        } else {
            if (baseUrl.endsWith("/")) "$baseUrl$fileName" else "$baseUrl/$fileName"
        }
        val hfUrl = ModelFileListingUtil.getHfDownloadUrl(repoId, fileName)
        
        // If useHfUrls is true, swap primary and fallback
        if (useHfUrls) {
            DownloadableFileWithFallback(File(modelDir, fileName), hfUrl, s3Url)
        } else {
            DownloadableFileWithFallback(File(modelDir, fileName), s3Url, hfUrl)
        }
    }
}

/**
 * Converts a list of DownloadableFile to DownloadableFileWithFallback.
 * Adds HuggingFace fallback URLs for each file.
 */
fun List<DownloadableFile>.withFallbackUrls(): List<DownloadableFileWithFallback> {
    return map { df ->
        val fallbackUrl = ModelFileListingUtil.getHfDownloadUrl(
            ModelFileListingUtil.getHfRepoId(df.url),
            df.file.name
        )
        DownloadableFileWithFallback(df.file, df.url, fallbackUrl)
    }
}

fun ModelData.getMissingFile(modelDir: File): String? {
    downloadableFiles(modelDir).forEach {
        if (!(it.file.exists() && it.file.length() > 0)) {
            return it.file.name
        }
    }
    return null
}

fun ModelData.allFilesExist(modelDir: File): Boolean {
    return getMissingFile(modelDir) == null
}

fun ModelData.getNexaManifest(context: Context): NexaManifestBean? {
    return try {
        val manifestFile = File(modelDir(context), "nexa.manifest")
        if (manifestFile.exists()) {
            val str = manifestFile.bufferedReader().use { it.readText() }
            Json { ignoreUnknownKeys = true }.decodeFromString<NexaManifestBean>(str)
        } else {
            null
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
