package com.nexa.demo.interpretation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import com.nexa.demo.interpretation.bean.DownloadableFile
import com.nexa.demo.interpretation.bean.DownloadableFileWithFallback
import com.nexa.demo.interpretation.bean.ModelData
import com.nexa.demo.interpretation.bean.downloadableFiles
import com.nexa.demo.interpretation.bean.downloadableFilesWithFallback
import com.nexa.demo.interpretation.bean.getMissingFile
import com.nexa.demo.interpretation.bean.getNexaManifest
import com.nexa.demo.interpretation.bean.isNpuModel
import com.nexa.demo.interpretation.bean.modelDir
import com.nexa.demo.interpretation.bean.modelFile
import com.nexa.demo.interpretation.bean.withFallbackUrls
import com.nexa.demo.interpretation.databinding.ActivityMainBinding
import com.nexa.demo.interpretation.utils.ModelFileListingUtil
import com.nexa.sdk.AsrWrapper
import com.nexa.sdk.NexaSdk
import com.nexa.sdk.bean.AsrCreateInput
import com.nexa.sdk.bean.AsrStreamBeginInput
import com.nexa.sdk.bean.AsrStreamConfig
import com.nexa.sdk.bean.ModelConfig
import com.nexa.sdk.callback.AsrTranscriptionCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Main Activity for the ASR Streaming Demo.
 * 
 * Features:
 * - Model download functionality with S3/HF fallback
 * - One-tap recording: tap button to start, tap again to stop
 * - Real-time streaming transcription display
 * - RecyclerView for message history
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ChatAdapter
    private val messages = mutableListOf<Message>()
    private lateinit var originalAdapter: ChatAdapter
    
    // Model configuration
    private lateinit var modelList: List<ModelData>
    private var selectedModel: ModelData? = null
    
    // ASR components
    private var asrWrapper: AsrWrapper? = null
    private var audioRecorder: StreamingAudioRecorder? = null
    
    // Translation components
    private val translationManager = TranslationManager()
    private var translationEnabled = false
    private var selectedLanguage = TranslationManager.Companion.Language.NONE
    private var selectedSourceLanguage = TranslationManager.Companion.Language.ENGLISH // Default to English
    
    // Interpretation (TTS) components
    private lateinit var interpretationManager: InterpretationManager
    private var ttsEnabled = false
    
    // State tracking
    @Volatile
    private var isRecording = false
    @Volatile
    private var isModelLoaded = false
    @Volatile
    private var isDownloading = false
    @Volatile
    private var isSdkInitialized = false
    
    // Coroutine scope for background operations
    private val modelScope = CoroutineScope(Dispatchers.IO)
    
    // Current streaming transcription (updated in real-time)
    private var currentTranscription = StringBuilder()
    private var currentTranscriptionMessageIndex = -1 // Index for translated messages (ASSISTANT)
    // Original transcription messages (USER) - separate list for original text area
    private val originalMessages = mutableListOf<Message>()
    private var currentOriginalMessageIndex = -1
    
    // Toggle original text display
    private var showOriginalText = true
    
    // Recording time and latency tracking
    private var recordingStartTime: Long = 0
    private var latencyCalculated: Boolean = false
    private val timeUpdateHandler = Handler(Looper.getMainLooper())
    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            if (isRecording && recordingStartTime > 0) {
                val elapsedSeconds = (System.currentTimeMillis() - recordingStartTime) / 1000
                val minutes = elapsedSeconds / 60
                val seconds = elapsedSeconds % 60
                val timeValue = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                setTimeDisplay(timeValue)
                timeUpdateHandler.postDelayed(this, 1000)
            }
        }
    }

    companion object {
        private const val TAG = "ASRStreamingDemo"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val PLUGIN_ID = "npu"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize interpretation manager
        interpretationManager = InterpretationManager(this, translationManager)
        
        parseModelList()
        initViews()
        initNexaSdk()
        checkPermissions()
        initTts()
    }

    /**
     * Parse model list from assets.
     */
    private fun parseModelList() {
        try {
            val jsonString = assets.open("model_list.json").bufferedReader().use { it.readText() }
            modelList = Json { ignoreUnknownKeys = true }.decodeFromString<List<ModelData>>(jsonString)
            if (modelList.isNotEmpty()) {
                selectedModel = modelList[0]
            }
            Log.d(TAG, "Parsed ${modelList.size} models from model_list.json")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse model list", e)
            modelList = emptyList()
        }
    }

    /**
     * Initialize views and set up listeners.
     */
    private fun initViews() {
        // Set up RecyclerView for translated messages (teal area)
        adapter = ChatAdapter(messages)
        binding.rvMessages.adapter = adapter
        
        // Set up RecyclerView for original messages (white area)
        originalAdapter = ChatAdapter(originalMessages)
        binding.rvOriginalMessages.adapter = originalAdapter
        
        // Download button click listener
        binding.btnDownload.setOnClickListener {
            if (selectedModel == null) {
                Toast.makeText(this, "No model selected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            if (isDownloading) {
                Toast.makeText(this, "Download in progress", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val modelDir = selectedModel!!.modelDir(this)
            // Check if nexa.manifest exists (indicates download is complete for NPU models)
            val manifestFile = File(modelDir, "nexa.manifest")
            if (manifestFile.exists()) {
                Toast.makeText(this, "Model already downloaded", Toast.LENGTH_SHORT).show()
                if (!isModelLoaded) {
                    loadAsrModel()
                }
            } else {
                downloadModel()
            }
        }
        
        // Record button click listener
        binding.micButtonView.setOnClickListener {
            if (!isModelLoaded) {
                val modelDir = selectedModel?.modelDir(this)
                val manifestFile = modelDir?.let { File(it, "nexa.manifest") }
                if (manifestFile?.exists() == true) {
                    Toast.makeText(this, "Loading model, please wait...", Toast.LENGTH_SHORT).show()
                    loadAsrModel()
                } else {
                    Toast.makeText(this, "Please download the model first", Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }
            
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }
        
        // Clear button click listener (now an ImageButton)
        binding.btnClear.setOnClickListener {
            clearHistory()
        }
        
        // TTS toggle button (person wireframe icons)
        binding.btnTtsToggle.setOnClickListener {
            ttsEnabled = !ttsEnabled
            if (ttsEnabled) {
                if (!interpretationManager.isTtsReady()) {
                    Toast.makeText(this, getString(R.string.tts_initializing), Toast.LENGTH_SHORT).show()
                    ttsEnabled = false
                } else {
                    // Switch to "Speaking" icon
                    binding.btnTtsToggle.setImageResource(R.drawable.ic_person_speaking_wireframe)
                    binding.llHeadphoneHint.visibility = View.VISIBLE
                }
            } else {
                // Switch to "Quiet" icon
                binding.btnTtsToggle.setImageResource(R.drawable.ic_person_wireframe)
                binding.llHeadphoneHint.visibility = View.GONE
            }
        }
        
        // Setup language spinner
        setupLanguageSpinner()
        
        // Swap languages button
        binding.btnSwapLanguages.setOnClickListener {
            swapLanguages()
        }
        
        // Translation switch listener
        binding.switchTranslation.setOnCheckedChangeListener { _, isChecked ->
            translationEnabled = isChecked
            if (isChecked && selectedLanguage != TranslationManager.Companion.Language.NONE) {
                initializeTranslation(selectedLanguage, selectedSourceLanguage)
            }
        }
        
        // Close headphone hint button
        binding.ivCloseHint.setOnClickListener {
            binding.llHeadphoneHint.visibility = View.GONE
        }
        
        // Toggle original text button (fold/unfold)
        binding.btnToggleOriginal.setOnClickListener {
            showOriginalText = !showOriginalText
            updateOriginalTextVisibility()
        }
        
        // Update model name display
        selectedModel?.let {
            binding.tvModelName.text = it.displayName
        }
        
        // Check if model is already downloaded
        checkModelStatus()
    }
    
    /**
     * Update the visibility of original text area based on toggle state.
     */
    private fun updateOriginalTextVisibility() {
        if (showOriginalText) {
            binding.llInputArea.visibility = View.VISIBLE
            binding.btnToggleOriginal.setImageResource(R.drawable.ic_toggle_expand)
        } else {
            binding.llInputArea.visibility = View.GONE
            binding.btnToggleOriginal.setImageResource(R.drawable.ic_toggle_collapse)
        }
    }

    /**
     * Swap source and target languages.
     */
    private fun swapLanguages() {
        val tempSource = selectedSourceLanguage
        val tempTarget = selectedLanguage
        
        // Find positions in spinners
        val sourcePosition = getSourceLanguagePosition(tempSource)
        val targetPosition = getTargetLanguagePosition(tempTarget)
        
        // Swap selections
        if (sourcePosition >= 0 && targetPosition >= 0) {
            // Update source language spinner
            binding.spinnerSourceLanguage.setSelection(getTargetLanguagePosition(tempTarget))
            // Update target language spinner
            binding.spinnerLanguage.setSelection(getSourceLanguagePosition(tempSource))
        }
    }
    
    /**
     * Get spinner position for source language.
     */
    private fun getSourceLanguagePosition(language: TranslationManager.Companion.Language): Int {
        return when (language) {
            TranslationManager.Companion.Language.ENGLISH -> 0
            TranslationManager.Companion.Language.SPANISH -> 1
            TranslationManager.Companion.Language.FRENCH -> 2
            TranslationManager.Companion.Language.GERMAN -> 3
            TranslationManager.Companion.Language.ITALIAN -> 4
            TranslationManager.Companion.Language.PORTUGUESE -> 5
            TranslationManager.Companion.Language.RUSSIAN -> 6
            else -> 0
        }
    }
    
    /**
     * Get spinner position for target language.
     */
    private fun getTargetLanguagePosition(language: TranslationManager.Companion.Language): Int {
        return when (language) {
            TranslationManager.Companion.Language.NONE -> 0
            TranslationManager.Companion.Language.ENGLISH -> 1
            TranslationManager.Companion.Language.SPANISH -> 2
            TranslationManager.Companion.Language.FRENCH -> 3
            TranslationManager.Companion.Language.GERMAN -> 4
            TranslationManager.Companion.Language.ITALIAN -> 5
            TranslationManager.Companion.Language.PORTUGUESE -> 6
            TranslationManager.Companion.Language.CHINESE -> 7
            TranslationManager.Companion.Language.JAPANESE -> 8
            TranslationManager.Companion.Language.KOREAN -> 9
            TranslationManager.Companion.Language.ARABIC -> 10
            TranslationManager.Companion.Language.RUSSIAN -> 11
            TranslationManager.Companion.Language.HINDI -> 12
            else -> 0
        }
    }
    
    /**
     * Map source language spinner position to Language enum.
     * Source languages are: English, Spanish, French, German, Italian, Portuguese, Russian
     */
    private fun getSourceLanguageFromPosition(position: Int): TranslationManager.Companion.Language {
        return when (position) {
            0 -> TranslationManager.Companion.Language.ENGLISH
            1 -> TranslationManager.Companion.Language.SPANISH
            2 -> TranslationManager.Companion.Language.FRENCH
            3 -> TranslationManager.Companion.Language.GERMAN
            4 -> TranslationManager.Companion.Language.ITALIAN
            5 -> TranslationManager.Companion.Language.PORTUGUESE
            6 -> TranslationManager.Companion.Language.RUSSIAN
            else -> TranslationManager.Companion.Language.ENGLISH
        }
    }
    
    /**
     * Map target language spinner position to Language enum.
     * Target languages are: None, English, Spanish, French, German, Italian, Portuguese, 
     * Chinese, Japanese, Korean, Arabic, Russian, Hindi (NO AUTO)
     */
    private fun getTargetLanguageFromPosition(position: Int): TranslationManager.Companion.Language {
        return when (position) {
            0 -> TranslationManager.Companion.Language.NONE
            1 -> TranslationManager.Companion.Language.ENGLISH
            2 -> TranslationManager.Companion.Language.SPANISH
            3 -> TranslationManager.Companion.Language.FRENCH
            4 -> TranslationManager.Companion.Language.GERMAN
            5 -> TranslationManager.Companion.Language.ITALIAN
            6 -> TranslationManager.Companion.Language.PORTUGUESE
            7 -> TranslationManager.Companion.Language.CHINESE
            8 -> TranslationManager.Companion.Language.JAPANESE
            9 -> TranslationManager.Companion.Language.KOREAN
            10 -> TranslationManager.Companion.Language.ARABIC
            11 -> TranslationManager.Companion.Language.RUSSIAN
            12 -> TranslationManager.Companion.Language.HINDI
            else -> TranslationManager.Companion.Language.NONE
        }
    }
    
    /**
     * Helper function to set Spinner dropdown height using reflection.
     * Works with both standard Spinner and AppCompatSpinner.
     */
    private fun setSpinnerDropDownHeight(spinner: android.widget.Spinner, height: Int) {
        try {
            // Try standard Spinner method first
            val method = spinner.javaClass.getMethod("setDropDownHeight", Int::class.javaPrimitiveType)
            method.invoke(spinner, height)
        } catch (e: NoSuchMethodException) {
            // Try AppCompatSpinner approach via mPopup field
            try {
                val field = spinner.javaClass.getDeclaredField("mPopup")
                field.isAccessible = true
                val popup = field.get(spinner)
                // Try different method names
                val methods = listOf("setHeight", "setDropDownHeight", "setContentHeight")
                for (methodName in methods) {
                    try {
                        val popupMethod = popup?.javaClass?.getMethod(methodName, Int::class.javaPrimitiveType)
                        popupMethod?.invoke(popup, height)
                        return
                    } catch (ex: Exception) {
                        // Continue to next method
                    }
                }
            } catch (ex: Exception) {
                Log.e("MainActivity", "Failed to set dropdown height for spinner", ex)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to set dropdown height for spinner", e)
        }
    }
    
    /**
     * Setup the language selection spinner.
     */
    private fun setupLanguageSpinner() {
        // Source language list (ASR supported languages that overlap with translation languages)
        val sourceLanguageNames = resources.getStringArray(R.array.source_language_names)
        val sourceAdapter = ArrayAdapter(this, R.layout.spinner_item_source, sourceLanguageNames)
        sourceAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_source)
        
        // Target language list (all translation languages)
        val languageNames = resources.getStringArray(R.array.language_names)
        val targetAdapter = ArrayAdapter(this, R.layout.spinner_item_target, languageNames)
        targetAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_target)
        
        // Setup source language spinner (left)
        binding.spinnerSourceLanguage.adapter = sourceAdapter
        binding.spinnerSourceLanguage.setSelection(0) // Select English (index 0)
        
        binding.spinnerSourceLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedSourceLanguage = getSourceLanguageFromPosition(position)
                
                // If translation is enabled, reinitialize translator with new source language
                if (translationEnabled && selectedLanguage != TranslationManager.Companion.Language.NONE) {
                    initializeTranslation(selectedLanguage, selectedSourceLanguage)
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
        
        // Setup target language spinner (right)
        binding.spinnerLanguage.adapter = targetAdapter
        
        // Set dropdown height to fixed value (398dp) and width to match spinner button
        // Left edge aligns with spinner button left edge
        binding.spinnerLanguage.post {
            val fixedHeightPx = (398 * resources.displayMetrics.density).toInt()
            val spinnerWidth = binding.spinnerLanguage.width
            setSpinnerDropDownHeight(binding.spinnerLanguage, fixedHeightPx)
            binding.spinnerLanguage.dropDownWidth = spinnerWidth
            binding.spinnerLanguage.dropDownHorizontalOffset = 0
        }
        
        binding.spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedLanguage = getTargetLanguageFromPosition(position)
                
                // Automatically enable translation when a target language is selected (not NONE)
                if (selectedLanguage != TranslationManager.Companion.Language.NONE) {
                    if (!translationEnabled) {
                        translationEnabled = true
                        binding.switchTranslation.isChecked = true
                    }
                    initializeTranslation(selectedLanguage, selectedSourceLanguage)
                } else {
                    // If NONE is selected, disable translation
                    translationEnabled = false
                    binding.switchTranslation.isChecked = false
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
    }

    /**
     * Initialize translation for the selected source and target languages.
     */
    private fun initializeTranslation(
        targetLanguage: TranslationManager.Companion.Language,
        sourceLanguage: TranslationManager.Companion.Language
    ) {
        modelScope.launch {
            try {
                translationManager.initializeTranslator(targetLanguage, sourceLanguage) { progress ->
                    runOnUiThread {
                        if (progress.contains("Downloading")) {
                            Toast.makeText(this@MainActivity, progress, Toast.LENGTH_SHORT).show()
                        }
                    }
                }.onSuccess {
                    runOnUiThread {
                        Log.d(TAG, "Translation initialized for ${sourceLanguage.displayName} -> ${targetLanguage.displayName}")
                        
                        // Update TTS language to match target language
                        if (ttsEnabled) {
                            updateTtsLanguage(targetLanguage)
                        }
                    }
                }.onFailure { error ->
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Translation setup failed: ${error.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                        binding.switchTranslation.isChecked = false
                        translationEnabled = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing translation", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Translation error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Initialize Text-to-Speech engine.
     */
    private fun initTts() {
        interpretationManager.initializeTts(
            onSuccess = {
                Log.d(TAG, "TTS initialized successfully")
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.tts_ready), Toast.LENGTH_SHORT).show()
                }
            },
            onError = { error ->
                Log.e(TAG, "TTS initialization failed: $error")
                runOnUiThread {
                    Toast.makeText(this, "${getString(R.string.tts_error)}: $error", Toast.LENGTH_LONG).show()
                    binding.btnTtsToggle.isEnabled = false
                }
            }
        )
        
        // Set up callbacks
        interpretationManager.onTtsStatusChange = { status ->
            runOnUiThread {
                Log.d(TAG, "TTS status: $status")
            }
        }
        
        interpretationManager.onSentenceSpoken = { original, translated ->
            runOnUiThread {
                Log.d(TAG, "Spoken - Original: $original, Translated: $translated")
            }
        }
        
        // Waveform volume update callback (same method as microphone button)
        interpretationManager.onTtsVolumeChange = { volume ->
            runOnUiThread {
                binding.waveformView.setVolume(volume)
                // Use isRecording=true logic in WaveformView to show fill during playback
                binding.waveformView.setRecording(volume > 0f)
            }
        }
    }

    /**
     * Update TTS language to match the target translation language.
     */
    private fun updateTtsLanguage(language: TranslationManager.Companion.Language) {
        val success = interpretationManager.setTtsLanguage(language)
        if (!success) {
            Toast.makeText(
                this,
                "TTS language ${language.displayName} not available",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Check if model files exist and update UI accordingly.
     */
    private fun checkModelStatus() {
        selectedModel?.let { model ->
            val modelDir = model.modelDir(this)
            val manifestFile = File(modelDir, "nexa.manifest")
            if (manifestFile.exists()) {
                binding.btnDownload.text = getString(R.string.load_model)
                binding.tvStatus.text = getString(R.string.status_model_ready)
            } else {
                binding.btnDownload.text = getString(R.string.download_model)
                binding.tvStatus.text = "Model not downloaded"
            }
        }
        
        // Update status dot color based on model loaded state
        runOnUiThread {
            if (isModelLoaded) {
                binding.vStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_dot_green)
            } else {
                binding.vStatusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_dot_orange)
            }
        }
    }

    /**
     * Get an OkHttpClient that trusts all certificates (for development).
     */
    private fun getUnsafeOkHttpClient(): OkHttpClient {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            val sslSocketFactory = sslContext.socketFactory

            return OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    /**
     * Download model files with S3/HF fallback support.
     */
    private fun downloadModel() {
        val model = selectedModel ?: return
        val modelDir = model.modelDir(this)
        
        isDownloading = true
        binding.llDownloading.visibility = View.VISIBLE
        binding.tvDownloadProgress.text = "Fetching file list..."
        binding.btnDownload.isEnabled = false
        
        modelScope.launch {
            try {
                val client = getUnsafeOkHttpClient()
                
                // Track URL mapping for fallback: primary URL -> fallback URL
                val fallbackUrlMap = mutableMapOf<String, String>()
                
                // For NPU models without explicit files list, fetch file list dynamically
                val filesToDownloadWithFallback: List<DownloadableFileWithFallback> = if (model.isNpuModel() && 
                    model.files.isNullOrEmpty() && 
                    !model.baseUrl.isNullOrEmpty()) {
                    
                    Log.d(TAG, "NPU model detected, fetching file list: ${model.baseUrl}")
                    
                    // Fetch file list with fallback support
                    val result = ModelFileListingUtil.listFilesWithFallback(model.baseUrl!!, client)
                    
                    if (result.files.isEmpty()) {
                        Log.e(TAG, "Failed to fetch file list for ${model.id}")
                        withContext(Dispatchers.Main) {
                            isDownloading = false
                            binding.llDownloading.visibility = View.GONE
                            binding.btnDownload.isEnabled = true
                            Toast.makeText(this@MainActivity, "Failed to fetch file list", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    
                    val useHfUrls = result.source == ModelFileListingUtil.FileListResult.Source.HUGGINGFACE
                    Log.d(TAG, "Found ${result.files.size} files from ${result.source}: ${result.files}")
                    
                    model.downloadableFilesWithFallback(modelDir, result.files, useHfUrls)
                } else {
                    // For non-NPU models or models with explicit files, use the original method
                    model.downloadableFiles(modelDir).withFallbackUrls()
                }
                
                // Build fallback URL map
                filesToDownloadWithFallback.forEach { 
                    fallbackUrlMap[it.primaryUrl] = it.fallbackUrl
                }
                
                Log.d(TAG, "Files to download: ${filesToDownloadWithFallback.size}")
                
                if (filesToDownloadWithFallback.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        isDownloading = false
                        binding.llDownloading.visibility = View.GONE
                        binding.btnDownload.isEnabled = true
                        Toast.makeText(this@MainActivity, "No files to download", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                // Get file sizes
                withContext(Dispatchers.Main) {
                    binding.tvDownloadProgress.text = "Calculating download size..."
                }
                
                val fileSizeMap = mutableMapOf<String, Long>()
                filesToDownloadWithFallback.forEach { fileWithFallback ->
                    var size = getUrlFileSize(client, fileWithFallback.primaryUrl)
                    if (size == 0L && fileWithFallback.fallbackUrl != fileWithFallback.primaryUrl) {
                        Log.w(TAG, "Primary URL failed, trying fallback for size: ${fileWithFallback.file.name}")
                        size = getUrlFileSize(client, fileWithFallback.fallbackUrl)
                    }
                    fileSizeMap[fileWithFallback.primaryUrl] = size
                }
                
                // Filter out files with size 0 (non-essential)
                val essentialExtensions = listOf(".nexa", ".gguf", ".bin", ".safetensors", ".manifest")
                val filesToDownloadFiltered = filesToDownloadWithFallback.filter { fileWithFallback ->
                    val size = fileSizeMap[fileWithFallback.primaryUrl] ?: 0L
                    val isEssential = essentialExtensions.any { fileWithFallback.file.name.endsWith(it, ignoreCase = true) }
                    size > 0L || !isEssential
                }.filter { fileSizeMap[it.primaryUrl]?.let { s -> s > 0L } ?: false }
                
                if (filesToDownloadFiltered.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        isDownloading = false
                        binding.llDownloading.visibility = View.GONE
                        binding.btnDownload.isEnabled = true
                        Toast.makeText(this@MainActivity, "No valid files to download", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                val totalBytes = filesToDownloadFiltered.sumOf { fileSizeMap[it.primaryUrl] ?: 0L }
                Log.d(TAG, "Total download size: $totalBytes bytes (${filesToDownloadFiltered.size} files)")
                
                // Download files
                var downloadedBytes = 0L
                var downloadedFiles = 0
                val totalFiles = filesToDownloadFiltered.size
                
                for (fileWithFallback in filesToDownloadFiltered) {
                    if (fileWithFallback.file.exists() && fileWithFallback.file.length() > 0) {
                        downloadedBytes += fileWithFallback.file.length()
                        downloadedFiles++
                        continue
                    }
                    
                    withContext(Dispatchers.Main) {
                        val progress = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                        binding.tvDownloadProgress.text = "$progress% - ${fileWithFallback.file.name}"
                    }
                    
                    // Try primary URL first, then fallback
                    var success = downloadFile(fileWithFallback.file, fileWithFallback.primaryUrl)
                    if (!success && fileWithFallback.fallbackUrl != fileWithFallback.primaryUrl) {
                        Log.w(TAG, "Primary download failed, trying fallback for: ${fileWithFallback.file.name}")
                        success = downloadFile(fileWithFallback.file, fileWithFallback.fallbackUrl)
                    }
                    
                    if (success) {
                        downloadedBytes += fileSizeMap[fileWithFallback.primaryUrl] ?: 0L
                        downloadedFiles++
                        val progress = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                        withContext(Dispatchers.Main) {
                            binding.tvDownloadProgress.text = "$progress%"
                        }
                    } else {
                        Log.e(TAG, "Failed to download: ${fileWithFallback.file.name}")
                    }
                }
                
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    binding.llDownloading.visibility = View.GONE
                    binding.btnDownload.isEnabled = true
                    
                    val manifestFile = File(modelDir, "nexa.manifest")
                    if (manifestFile.exists()) {
                        Toast.makeText(this@MainActivity, "Download complete!", Toast.LENGTH_SHORT).show()
                        binding.btnDownload.text = getString(R.string.load_model)
                        checkModelStatus()
                        loadAsrModel()
                    } else {
                        Toast.makeText(this@MainActivity, "Download incomplete - missing manifest", Toast.LENGTH_SHORT).show()
                        checkModelStatus()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error", e)
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    binding.llDownloading.visibility = View.GONE
                    binding.btnDownload.isEnabled = true
                    Toast.makeText(this@MainActivity, "Download error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Get file size from URL using HEAD request.
     */
    private fun getUrlFileSize(client: OkHttpClient, url: String): Long {
        return try {
            val request = Request.Builder().url(url).head().build()
            client.newCall(request).execute().use { response ->
                response.header("Content-Length")?.toLongOrNull() ?: 0L
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file size for $url: ${e.message}")
            0L
        }
    }

    /**
     * Download a file from URL.
     */
    private fun downloadFile(file: File, url: String): Boolean {
        return try {
            Log.d(TAG, "Downloading: ${file.name} from $url")
            val client = getUnsafeOkHttpClient()
            val request = Request.Builder().url(url).build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: ${response.code} for $url")
                    return false
                }
                
                file.parentFile?.mkdirs()
                
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }
                
                Log.d(TAG, "Downloaded ${file.name} successfully (${file.length()} bytes)")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download ${file.name}: ${e.message}", e)
            false
        }
    }

    /**
     * Initialize Nexa SDK.
     */
    private fun initNexaSdk() {
        // Set environment variables for native library paths
        // This tells the SDK where to find plugin libraries
        val nativeLibPath = applicationInfo.nativeLibraryDir
        val adspLibPath = File(filesDir, "npu/htp-files").absolutePath
        val ldLibraryPath = "$nativeLibPath:$nativeLibPath/npu:$adspLibPath:\$LD_LIBRARY_PATH"
        
        Log.d(TAG, "Setting NEXA_PLUGIN_PATH: $nativeLibPath")
        Log.d(TAG, "Setting LD_LIBRARY_PATH: $ldLibraryPath")
        Log.d(TAG, "Setting ADSP_LIBRARY_PATH: $adspLibPath")
        
        try {
            Os.setenv("NEXA_PLUGIN_PATH", nativeLibPath, true)
            Os.setenv("LD_LIBRARY_PATH", ldLibraryPath, true)
            Os.setenv("ADSP_LIBRARY_PATH", adspLibPath, true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set environment variables", e)
        }
        
        NexaSdk.getInstance().init(this, object : NexaSdk.InitCallback {
            override fun onSuccess() {
                Log.d(TAG, "NexaSdk initialized successfully")
                isSdkInitialized = true
                // Check if model is already downloaded
                selectedModel?.let { model ->
                    val modelDir = model.modelDir(this@MainActivity)
                    val manifestFile = File(modelDir, "nexa.manifest")
                    if (manifestFile.exists()) {
                        loadAsrModel()
                    }
                }
            }

            override fun onFailure(reason: String) {
                // SDK reports failure if ANY plugin is missing, but it can still work
                // as long as the required plugins for our use case are loaded.
                // For ASR, we need: npu plugin (which was loaded successfully)
                Log.w(TAG, "NexaSdk init reported issues: $reason")
                
                // Check if the critical plugins for ASR are loaded (npu or whisper_cpp)
                // The SDK is still usable even with some missing plugins
                val criticalPluginMissing = reason.contains("npu") && reason.contains("whisper_cpp")
                
                if (criticalPluginMissing) {
                    Log.e(TAG, "Critical plugins missing for ASR")
                    isSdkInitialized = false
                    runOnUiThread {
                        binding.llLoading.visibility = View.GONE
                        Toast.makeText(this@MainActivity, "SDK init failed: Missing critical plugins", Toast.LENGTH_LONG).show()
                    }
                } else {
                    // Non-critical plugins missing (like TTS), SDK is still usable for ASR
                    Log.d(TAG, "Non-critical plugins missing, SDK still usable for ASR")
                    isSdkInitialized = true
                    // Check if model is already downloaded
                    selectedModel?.let { model ->
                        val modelDir = model.modelDir(this@MainActivity)
                        val manifestFile = File(modelDir, "nexa.manifest")
                        if (manifestFile.exists()) {
                            loadAsrModel()
                        }
                    }
                }
            }
        })
    }

    /**
     * Load the ASR model for streaming transcription.
     */
    private fun loadAsrModel() {
        val model = selectedModel ?: return
        
        // Check if SDK is initialized
        if (!isSdkInitialized) {
            Log.e(TAG, "Cannot load model: SDK not initialized")
            Toast.makeText(this, "SDK not initialized. Please restart the app.", Toast.LENGTH_LONG).show()
            return
        }
        
        modelScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    binding.tvLoadingText.text = getString(R.string.loading_model)
                    binding.llLoading.visibility = View.VISIBLE
                }

                val modelDir = model.modelDir(this@MainActivity)
                val nexaManifest = model.getNexaManifest(this@MainActivity)
                val modelFile = model.modelFile(this@MainActivity)
                
                // Debug logging
                Log.d(TAG, "modelDir: ${modelDir.absolutePath}")
                Log.d(TAG, "modelDir exists: ${modelDir.exists()}")
                Log.d(TAG, "modelFile: ${modelFile?.absolutePath}")
                Log.d(TAG, "modelFile exists: ${modelFile?.exists()}")
                Log.d(TAG, "model.modelName: ${model.modelName}")
                Log.d(TAG, "model.modelUrl: ${model.modelUrl}")
                
                // List files in modelDir for debugging
                if (modelDir.exists()) {
                    Log.d(TAG, "Files in modelDir:")
                    modelDir.listFiles()?.forEach { file ->
                        Log.d(TAG, "  - ${file.name} (${file.length()} bytes)")
                    }
                }
                
                if (modelFile == null || !modelFile.exists()) {
                    withContext(Dispatchers.Main) {
                        binding.llLoading.visibility = View.GONE
                        Toast.makeText(
                            this@MainActivity,
                            "Model file not found: ${modelFile?.name ?: "null"}. Please download the model first.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }
                
                val modelName = nexaManifest?.ModelName ?: model.modelName
                val pluginId = nexaManifest?.PluginId ?: PLUGIN_ID
                
                Log.d(TAG, "Loading ASR model:")
                Log.d(TAG, "  model_name: $modelName")
                Log.d(TAG, "  model_path: ${modelFile.absolutePath}")
                Log.d(TAG, "  npu_model_folder_path: ${modelDir.absolutePath}")
                Log.d(TAG, "  npu_lib_folder_path: ${applicationInfo.nativeLibraryDir}")
                Log.d(TAG, "  plugin_id: $pluginId")
                
                val asrCreateInput = AsrCreateInput(
                    model_name = modelName,
                    model_path = modelFile.absolutePath,
                    config = ModelConfig(
                        npu_lib_folder_path = applicationInfo.nativeLibraryDir,
                        npu_model_folder_path = modelDir.absolutePath
                    ),
                    plugin_id = pluginId
                )

                AsrWrapper.builder()
                    .asrCreateInput(asrCreateInput)
                    .build()
                    .onSuccess { wrapper ->
                        asrWrapper = wrapper
                        isModelLoaded = true
                        runOnUiThread {
                            binding.llLoading.visibility = View.GONE
                            binding.tvStatus.text = getString(R.string.status_ready)
                            binding.btnDownload.visibility = View.GONE
                            // Update status dot color to green
                            binding.vStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_dot_green)
                            Toast.makeText(this@MainActivity, getString(R.string.model_loaded), Toast.LENGTH_SHORT).show()
                        }
                        Log.d(TAG, "ASR model loaded successfully")
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Failed to load ASR model", error)
                        runOnUiThread {
                            binding.llLoading.visibility = View.GONE
                            // Update status dot color to orange on failure
                            binding.vStatusDot.backgroundTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.status_dot_orange)
                            Toast.makeText(
                                this@MainActivity,
                                "${getString(R.string.model_load_failed)}: ${error.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading ASR model", e)
                withContext(Dispatchers.Main) {
                    binding.llLoading.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        "${getString(R.string.model_load_failed)}: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Check and request audio recording permission.
     */
    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Start recording and streaming ASR.
     */
    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            checkPermissions()
            return
        }

        isRecording = true
        updateRecordingUI(true)
        
        // Initialize recording time tracking
        recordingStartTime = System.currentTimeMillis()
        latencyCalculated = false
        setTimeDisplay("00:00")
        setLatencyDisplay("-ms")
        
        // Start time update timer
        timeUpdateHandler.post(timeUpdateRunnable)
        
        // Don't reset transcription state - keep it until clearHistory() is called
        // Transcription will be cleared only when:
        // 1. Clearing history (clearHistory)
        
        // Reset message indices so new transcription starts new messages
        // This allows history to accumulate - each recording session adds new messages
        currentTranscriptionMessageIndex = -1
        currentOriginalMessageIndex = -1
        
        // Reset interpretation manager for new session
        interpretationManager.reset()

        // Create transcription callback
        val transcriptionCallback = object : AsrTranscriptionCallback {
            override fun onTranscription(text: String) {
                Log.d(TAG, "ASR Transcription received (expected language: ${selectedSourceLanguage.displayName}): $text")
                runOnUiThread {
                    updateTranscription(text)
                }
            }
        }

        // Begin streaming ASR
        modelScope.launch {
            try {
                val streamConfig = AsrStreamConfig(
                    chunkDuration = 4.0f,
                    overlapDuration = 3.0f,
                    sampleRate = 16000,
                    maxQueueSize = 10,
                    bufferSize = 512
                )
                
                // Get language code from selected source language
                // Parakeet TDT 0.6B V3 supports 25 languages. We only show languages that overlap
                // with translation languages: English, Spanish, French, German, Italian, Portuguese, Russian
                val sourceLanguageCode = when (selectedSourceLanguage) {
                    TranslationManager.Companion.Language.ENGLISH -> "en"
                    TranslationManager.Companion.Language.SPANISH -> "es"
                    TranslationManager.Companion.Language.FRENCH -> "fr"
                    TranslationManager.Companion.Language.GERMAN -> "de"
                    TranslationManager.Companion.Language.ITALIAN -> "it"
                    TranslationManager.Companion.Language.PORTUGUESE -> "pt"
                    TranslationManager.Companion.Language.RUSSIAN -> "ru"
                    else -> {
                        Log.w(TAG, "Unknown source language selected, defaulting to English")
                        "en" // Default to English if unknown
                    }
                }
                
                Log.d(TAG, "Starting ASR with language code: $sourceLanguageCode (selected: ${selectedSourceLanguage.displayName})")
                
                val streamInput = AsrStreamBeginInput(
                    language = sourceLanguageCode,
                    streamConfig = streamConfig,
                    callback = transcriptionCallback
                )

                asrWrapper?.streamBegin(streamInput)?.onSuccess {
                    Log.d(TAG, "Streaming ASR started successfully with language: $sourceLanguageCode")
                    
                    // Start audio recording
                    withContext(Dispatchers.Main) {
                        startAudioCapture()
                    }
                }?.onFailure { error ->
                    Log.e(TAG, "Failed to start streaming ASR with language '$sourceLanguageCode'", error)
                    
                    // Error message based on selected language
                    val errorMessage = "Failed to start ASR with language '${selectedSourceLanguage.displayName}': ${error.message}"
                    
                    withContext(Dispatchers.Main) {
                        stopRecording()
                        Toast.makeText(
                            this@MainActivity,
                            errorMessage,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting streaming ASR", e)
                withContext(Dispatchers.Main) {
                    stopRecording()
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Start capturing audio and pushing to ASR.
     */
    @Suppress("MissingPermission")
    private fun startAudioCapture() {
        audioRecorder = StreamingAudioRecorder(
            sampleRate = 16000,
            bufferSize = 512,
            enableAEC = true,  // Enable Acoustic Echo Cancellation
            enableNS = true    // Enable Noise Suppression
        )

        audioRecorder?.startRecording(object : StreamingAudioRecorder.AudioCallback {
            override fun onAudioSamples(samples: FloatArray) {
                // Push audio samples to ASR
                modelScope.launch {
                    asrWrapper?.streamPushAudio(samples)
                }
            }

            override fun onRecordingStopped() {
                Log.d(TAG, "Audio recording stopped")
            }

            override fun onError(error: String) {
                Log.e(TAG, "Audio recording error: $error")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Recording error: $error", Toast.LENGTH_SHORT).show()
                    stopRecording()
                }
            }
        })
    }

    /**
     * Stop recording and streaming ASR.
     */
    private fun stopRecording() {
        isRecording = false
        updateRecordingUI(false)
        
        // Stop time update timer
        timeUpdateHandler.removeCallbacks(timeUpdateRunnable)
        
        // Don't reset time and latency display - keep them until next recording or clear
        // Time and latency will be reset only when:
        // 1. Starting a new recording (startRecording)
        // 2. Clearing history (clearHistory)
        
        // Finalize any remaining sentences (handles "last sentence" problem)
        if (ttsEnabled) {
            modelScope.launch {
                interpretationManager.finalizeRemainingSentences(
                    translationEnabled = translationEnabled && translationManager.isTranslationEnabled(),
                    scope = modelScope
                )
            }
        }
        
        // Stop audio recorder
        audioRecorder?.stopRecording()
        audioRecorder = null

        // Stop ASR streaming
        modelScope.launch {
            try {
                asrWrapper?.streamStop(graceful = true)?.onSuccess {
                    Log.d(TAG, "Streaming ASR stopped")
                }?.onFailure { error ->
                    Log.e(TAG, "Error stopping ASR", error)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping streaming ASR", e)
            }
        }
    }

    /**
     * Update the transcription display with new text.
     * Applies translation if enabled, and triggers TTS for simultaneous interpretation.
     */
    private fun updateTranscription(text: String) {
        if (text.isEmpty()) return
        
        currentTranscription.clear()
        currentTranscription.append(text)
        
        // Update white input area with original text
        // Add to separate original messages list for history accumulation (same behavior as translation)
        runOnUiThread {
            // Add original text to separate list for history accumulation
            if (currentOriginalMessageIndex < 0 || 
                currentOriginalMessageIndex >= originalMessages.size) {
                originalMessages.add(Message(text, MessageType.USER))
                currentOriginalMessageIndex = originalMessages.size - 1
                originalAdapter.notifyItemInserted(currentOriginalMessageIndex)
            } else {
                originalMessages[currentOriginalMessageIndex] = Message(text, MessageType.USER)
                originalAdapter.notifyItemChanged(currentOriginalMessageIndex)
            }
            
            // Scroll to latest message
            binding.rvOriginalMessages.scrollToPosition(originalMessages.size - 1)
            
            // Hide empty state hint when there are messages
            binding.tvOriginalEmpty.visibility = if (originalMessages.isEmpty()) View.VISIBLE else View.GONE
        }
        
        // Process streaming text for simultaneous interpretation (sentence detection + TTS)
        if (ttsEnabled) {
            modelScope.launch {
                interpretationManager.processStreamingText(
                    newText = text,
                    translationEnabled = translationEnabled && translationManager.isTranslationEnabled(),
                    scope = modelScope
                )
            }
        }
        
        // Update teal area with translation if enabled
        if (translationEnabled && translationManager.isTranslationEnabled()) {
            modelScope.launch {
                try {
                    val translatedText = translationManager.translate(text)
                    
                    // Show translated text in the teal area
                    runOnUiThread {
                        updateMessageDisplay(translatedText)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Translation error", e)
                    runOnUiThread {
                        updateMessageDisplay(text)
                    }
                }
            }
        } else {
            updateMessageDisplay(text)
        }
    }

    /**
     * Set time display with colored label and value.
     */
    private fun setTimeDisplay(timeValue: String) {
        val labelText = getString(R.string.time_format, timeValue)
        val spannable = SpannableString(labelText)
        val labelColor = ContextCompat.getColor(this, R.color.kuromi_label_text)
        val valueColor = ContextCompat.getColor(this, R.color.kuromi_value_text)
        
        // Set label color ("Time: ")
        val labelEnd = labelText.indexOf(timeValue)
        if (labelEnd > 0) {
            spannable.setSpan(ForegroundColorSpan(labelColor), 0, labelEnd, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        // Set value color (time value)
        if (labelEnd >= 0 && labelEnd < labelText.length) {
            spannable.setSpan(ForegroundColorSpan(valueColor), labelEnd, labelText.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        binding.tvTime.text = spannable
    }
    
    /**
     * Set latency display with colored label and value.
     */
    private fun setLatencyDisplay(latencyValue: String) {
        val labelText = getString(R.string.latency_format, latencyValue)
        val spannable = SpannableString(labelText)
        val labelColor = ContextCompat.getColor(this, R.color.kuromi_label_text)
        val valueColor = ContextCompat.getColor(this, R.color.kuromi_value_text)
        
        // Set label color ("Latency: ")
        val labelEnd = labelText.indexOf(latencyValue)
        if (labelEnd > 0) {
            spannable.setSpan(ForegroundColorSpan(labelColor), 0, labelEnd, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        // Set value color (latency value)
        if (labelEnd >= 0 && labelEnd < labelText.length) {
            spannable.setSpan(ForegroundColorSpan(valueColor), labelEnd, labelText.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        
        binding.tvLatency.text = spannable
    }
    
    /**
     * Update the message display with the given text.
     */
    private fun updateMessageDisplay(text: String) {
        // Calculate latency on first display (translated text or original text)
        if (!latencyCalculated && recordingStartTime > 0) {
            val displayTime = System.currentTimeMillis()
            val latencyMs = displayTime - recordingStartTime
            setLatencyDisplay("${latencyMs}ms")
            latencyCalculated = true
        }
        
        if (currentTranscriptionMessageIndex < 0 || 
            currentTranscriptionMessageIndex >= messages.size) {
            addMessage(Message(text, MessageType.ASSISTANT))
            currentTranscriptionMessageIndex = messages.size - 1
        } else {
            messages[currentTranscriptionMessageIndex] = Message(text, MessageType.ASSISTANT)
            adapter.notifyItemChanged(currentTranscriptionMessageIndex)
        }
        
        binding.rvMessages.scrollToPosition(messages.size - 1)
        
        // Hide empty state hint when there are messages
        binding.tvTranslationEmpty.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Add a new message to the list.
     */
    private fun addMessage(message: Message) {
        messages.add(message)
        adapter.notifyItemInserted(messages.size - 1)
        binding.rvMessages.scrollToPosition(messages.size - 1)
        
        // Hide empty state hint when there are messages
        binding.tvTranslationEmpty.visibility = View.GONE
    }

    /**
     * Clear message history.
     */
    private fun clearHistory() {
        if (isRecording) {
            stopRecording()  // stopRecording() will reset time and latency
        } else {
            // If not recording, reset time and latency display
            setTimeDisplay("00:00")
            setLatencyDisplay("-ms")
            recordingStartTime = 0
            latencyCalculated = false
        }
        
        messages.clear()
        originalMessages.clear()
        currentTranscription.clear()
        currentTranscriptionMessageIndex = -1
        currentOriginalMessageIndex = -1
        adapter.notifyDataSetChanged()
        originalAdapter.notifyDataSetChanged()
        
        // Show empty state hints when lists are cleared
        binding.tvTranslationEmpty.visibility = View.VISIBLE
        binding.tvOriginalEmpty.visibility = View.VISIBLE
    }

    /**
     * Update UI based on recording state.
     */
    private fun updateRecordingUI(recording: Boolean) {
        // Convert dp to pixels
        val density = resources.displayMetrics.density
        val padding8dp = (8 * density).toInt()
        val padding16dp = (16 * density).toInt()
        
        if (recording) {
            binding.tvStatus.text = getString(R.string.status_recording)
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.recording_red))
            // Update microphone button recording state
            binding.micButtonView.setRecording(true)
            // Start microphone volume animation (smooth 1.5s loop matching SVG code)
            startMicrophoneVolumeAnimation()
            // Update waveform to show orange bars
            binding.waveformView.setRecording(true)
            // Adjust bottom panel padding
            binding.llBottomPanel.setPadding(padding16dp, padding8dp, padding16dp, padding8dp)
        } else {
            binding.tvStatus.text = getString(R.string.status_ready)
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            // Update microphone button recording state
            binding.micButtonView.setRecording(false)
            // Stop microphone volume animation
            stopMicrophoneVolumeAnimation()
            binding.micButtonView.setVolume(0f)
            // Update waveform to show white stroke lines
            binding.waveformView.setRecording(false)
            // Default bottom panel padding
            binding.llBottomPanel.setPadding(padding16dp, padding8dp, padding16dp, padding8dp)
        }
    }
    
    private var microphoneVolumeAnimator: android.animation.ValueAnimator? = null
    
    /**
     * Smooth volume animation for mic recording (1.5s loop based on provided micFill keyframes)
     */
    private fun startMicrophoneVolumeAnimation() {
        stopMicrophoneVolumeAnimation()
        microphoneVolumeAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500 // 1.5s loop
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener {
                val progress = it.animatedValue as Float
                // Map progress to volume heights based on SVG micFill keyframes
                val volume = when {
                    progress < 0.25f -> 0.3f + (progress / 0.25f) * 0.3f // 0.3 to 0.6
                    progress < 0.50f -> 0.6f + ((progress - 0.25f) / 0.25f) * 0.4f // 0.6 to 1.0
                    progress < 0.75f -> 1.0f - ((progress - 0.50f) / 0.25f) * 0.5f // 1.0 to 0.5
                    else -> 0.5f - ((progress - 0.75f) / 0.25f) * 0.2f // 0.5 to 0.3
                }
                binding.micButtonView.setVolume(volume)
                // Sync the waveform view at the top with the same volume loop
                binding.waveformView.setVolume(volume)
            }
            start()
        }
    }
    
    private fun stopMicrophoneVolumeAnimation() {
        microphoneVolumeAnimator?.cancel()
        microphoneVolumeAnimator = null
    }

    override fun onDestroy() {
        super.onDestroy()
        
        if (isRecording) {
            audioRecorder?.stopRecording()
        }
        
        modelScope.launch {
            asrWrapper?.destroy()
        }
        
        // Close translation manager
        translationManager.close()
        
        // Shutdown interpretation manager (TTS)
        interpretationManager.shutdown()
    }
}
