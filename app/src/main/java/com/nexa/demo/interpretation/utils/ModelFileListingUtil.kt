package com.nexa.demo.interpretation.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Utility class to list and download model files from S3 or Hugging Face Hub.
 * Provides fallback mechanism: tries S3 first, then falls back to HF if S3 fails.
 */
object ModelFileListingUtil {
    private const val TAG = "ModelFileListingUtil"
    private const val HF_OWNER = "NexaAI"

    /**
     * Result of file listing operation
     */
    data class FileListResult(
        val files: List<String>,
        val source: Source,
        val repoId: String? = null
    ) {
        enum class Source { S3, HUGGINGFACE, FAILED }
    }

    /**
     * Extracts repo name from S3 URL.
     */
    fun extractRepoNameFromS3Url(s3Url: String): String {
        val path = s3Url.removePrefix("https://").removePrefix("http://")
            .substringAfter("/")
            .trimEnd('/')
        
        val segments = path.split("/").filter { it.isNotEmpty() }
        
        return if (segments.isNotEmpty()) {
            val lastSegment = segments.last()
            if (lastSegment.contains(".") && !lastSegment.endsWith("/")) {
                if (segments.size >= 2) segments[segments.size - 2] else lastSegment
            } else {
                lastSegment
            }
        } else {
            ""
        }
    }

    /**
     * Constructs HF repo ID from S3 URL.
     */
    fun getHfRepoId(s3Url: String): String {
        val repoName = extractRepoNameFromS3Url(s3Url)
        return "$HF_OWNER/$repoName"
    }

    /**
     * Constructs HF download URL for a file.
     */
    fun getHfDownloadUrl(repoId: String, fileName: String): String {
        return "https://huggingface.co/$repoId/resolve/main/$fileName?download=true"
    }

    /**
     * Lists all files under a given S3 base URL, with fallback to Hugging Face.
     */
    suspend fun listFilesWithFallback(
        baseUrl: String,
        client: OkHttpClient
    ): FileListResult = withContext(Dispatchers.IO) {
        // Try S3 first
        val s3Files = listFilesFromS3(baseUrl, client)
        if (s3Files.isNotEmpty()) {
            Log.d(TAG, "Successfully listed ${s3Files.size} files from S3")
            return@withContext FileListResult(s3Files, FileListResult.Source.S3)
        }

        // Fallback to Hugging Face
        Log.w(TAG, "S3 listing failed, falling back to Hugging Face")
        val repoId = getHfRepoId(baseUrl)
        val hfFiles = listFilesFromHuggingFace(repoId, client)
        if (hfFiles.isNotEmpty()) {
            Log.d(TAG, "Successfully listed ${hfFiles.size} files from HuggingFace: $repoId")
            return@withContext FileListResult(hfFiles, FileListResult.Source.HUGGINGFACE, repoId)
        }

        Log.e(TAG, "Failed to list files from both S3 and HuggingFace")
        FileListResult(emptyList(), FileListResult.Source.FAILED)
    }

    /**
     * Lists all files under a given S3 base URL.
     */
    suspend fun listFilesFromS3(baseUrl: String, client: OkHttpClient): List<String> = withContext(Dispatchers.IO) {
        try {
            val urlWithoutProtocol = baseUrl.removePrefix("https://").removePrefix("http://")
            val hostEndIndex = urlWithoutProtocol.indexOf('/')
            if (hostEndIndex == -1) {
                Log.e(TAG, "Invalid S3 URL format: $baseUrl")
                return@withContext emptyList()
            }

            val host = urlWithoutProtocol.substring(0, hostEndIndex)
            val prefix = urlWithoutProtocol.substring(hostEndIndex + 1).trimEnd('/')

            val listUrl = "https://$host/?list-type=2&prefix=$prefix/"
            Log.d(TAG, "Listing S3 bucket: $listUrl")

            val request = Request.Builder()
                .url(listUrl)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to list S3 bucket: ${response.code}")
                return@withContext emptyList()
            }

            val xmlContent = response.body?.string() ?: return@withContext emptyList()
            Log.d(TAG, "S3 response received, parsing XML...")

            val files = parseS3ListResponse(xmlContent, prefix)
            Log.d(TAG, "Found ${files.size} files from S3: $files")
            files
        } catch (e: Exception) {
            Log.e(TAG, "Error listing S3 files: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Lists all files from a Hugging Face repository.
     */
    suspend fun listFilesFromHuggingFace(repoId: String, client: OkHttpClient): List<String> = withContext(Dispatchers.IO) {
        try {
            val apiUrl = "https://huggingface.co/api/models/$repoId/tree/main"
            Log.d(TAG, "Listing HuggingFace repo: $apiUrl")

            val request = Request.Builder()
                .url(apiUrl)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to list HuggingFace repo: ${response.code}")
                return@withContext emptyList()
            }

            val jsonContent = response.body?.string() ?: return@withContext emptyList()
            Log.d(TAG, "HuggingFace response received, parsing JSON...")

            val files = parseHuggingFaceResponse(jsonContent)
            Log.d(TAG, "Found ${files.size} files from HuggingFace: $files")
            files
        } catch (e: Exception) {
            Log.e(TAG, "Error listing HuggingFace files: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Parses the S3 ListObjectsV2 XML response and extracts file names.
     */
    private fun parseS3ListResponse(xmlContent: String, prefix: String): List<String> {
        val files = mutableListOf<String>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlContent))

            var eventType = parser.eventType
            var currentTag = ""
            val prefixWithSlash = if (prefix.endsWith("/")) prefix else "$prefix/"

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                    }
                    XmlPullParser.TEXT -> {
                        if (currentTag == "Key") {
                            val key = parser.text
                            if (key.startsWith(prefixWithSlash) && key.length > prefixWithSlash.length) {
                                val relativePath = key.removePrefix(prefixWithSlash)
                                if (!relativePath.endsWith("/") && relativePath.isNotEmpty()) {
                                    files.add(relativePath)
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing S3 XML response: ${e.message}", e)
        }
        return files
    }

    /**
     * Parses the Hugging Face API JSON response and extracts file names.
     */
    private fun parseHuggingFaceResponse(jsonContent: String): List<String> {
        val files = mutableListOf<String>()
        try {
            val jsonArray = JSONArray(jsonContent)
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val type = item.optString("type", "")
                val path = item.optString("path", "")
                
                if (type == "file" && path.isNotEmpty()) {
                    files.add(path)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing HuggingFace JSON response: ${e.message}", e)
        }
        return files
    }
}
