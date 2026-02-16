package com.nexa.demo.interpretation

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * Manager for simultaneous interpretation: handles sentence detection, 
 * translation, and text-to-speech output.
 * 
 * Strategy:
 * 1. Detects sentence boundaries (., ?, !)
 * 2. Waits for confirmation (new text after punctuation)
 * 3. Translates finalized sentences
 * 4. Speaks them via TTS
 */
class InterpretationManager(
    private val context: Context,
    private val translationManager: TranslationManager
) {
    
    companion object {
        private const val TAG = "InterpretationManager"
        
        // Sentence ending punctuation
        private val SENTENCE_ENDINGS = setOf('.', '?', '!')
        
        // Minimum sentence length to process (avoid speaking single words)
        private const val MIN_SENTENCE_LENGTH = 3
    }
    
    // Text-to-Speech engine
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var currentTtsLocale: Locale? = null
    private var currentTtsLanguage: TranslationManager.Companion.Language? = null // Track current TTS language to avoid redundant sets
    
    // Buffering state
    private var previousText = ""
    private var processedUpToIndex = 0
    private val mutex = Mutex()
    
    // Callbacks
    var onTtsStatusChange: ((String) -> Unit)? = null
    var onSentenceSpoken: ((String, String?) -> Unit)? = null // (original, translated)
    var onTtsVolumeChange: ((Float) -> Unit)? = null // Volume level (0.0 to 1.0) for waveform visualization
    
    /**
     * Initialize TTS engine.
     */
    fun initializeTts(onSuccess: () -> Unit, onError: (String) -> Unit) {
        Log.d(TAG, "Initializing TTS...")
        
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
                Log.d(TAG, "TTS initialized successfully")
                
                // Set up utterance progress listener
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    private var waveformAnimator: android.animation.ValueAnimator? = null
                    
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS started: $utteranceId")
                        // Start waveform animation
                        waveformAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = 100 // Update every 100ms
                            repeatCount = android.animation.ValueAnimator.INFINITE
                            addUpdateListener {
                                // Simulate volume variation during playback (0.6 to 1.0)
                                val volume = 0.6f + (Math.random() * 0.4).toFloat()
                                onTtsVolumeChange?.invoke(volume)
                            }
                            start()
                        }
                    }
                    
                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS finished: $utteranceId")
                        // Stop waveform animation
                        waveformAnimator?.cancel()
                        waveformAnimator = null
                        onTtsVolumeChange?.invoke(0f)
                    }
                    
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS error: $utteranceId")
                        // Stop waveform animation on error
                        waveformAnimator?.cancel()
                        waveformAnimator = null
                        onTtsVolumeChange?.invoke(0f)
                    }
                })
                
                onSuccess()
            } else {
                Log.e(TAG, "TTS initialization failed")
                isTtsReady = false
                onError("TTS initialization failed")
            }
        }
    }
    
    /**
     * Set TTS language based on target translation language.
     * For translation mode, we speak in the target language.
     */
    fun setTtsLanguage(language: TranslationManager.Companion.Language): Boolean {
        if (!isTtsReady) {
            Log.w(TAG, "TTS not ready, cannot set language")
            return false
        }
        
        val locale = when (language) {
            TranslationManager.Companion.Language.NONE -> Locale.US
            TranslationManager.Companion.Language.ENGLISH -> Locale.US
            TranslationManager.Companion.Language.SPANISH -> Locale("es")
            TranslationManager.Companion.Language.FRENCH -> Locale.FRENCH
            TranslationManager.Companion.Language.GERMAN -> Locale.GERMAN
            TranslationManager.Companion.Language.ITALIAN -> Locale.ITALIAN
            TranslationManager.Companion.Language.PORTUGUESE -> Locale("pt")
            TranslationManager.Companion.Language.CHINESE -> Locale("zh", "CN")
            TranslationManager.Companion.Language.JAPANESE -> Locale.JAPANESE
            TranslationManager.Companion.Language.KOREAN -> Locale.KOREAN
            TranslationManager.Companion.Language.ARABIC -> Locale("ar")
            TranslationManager.Companion.Language.RUSSIAN -> Locale("ru")
            TranslationManager.Companion.Language.HINDI -> Locale("hi")
        }
        
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Language ${locale.displayName} not supported for TTS")
            onTtsStatusChange?.invoke("TTS: ${locale.displayName} not available")
            return false
        }
        
        currentTtsLocale = locale
        currentTtsLanguage = language // Track the language we set
        Log.d(TAG, "TTS language set to: ${locale.displayName}")
        onTtsStatusChange?.invoke("TTS: ${locale.displayName}")
        return true
    }
    
    /**
     * Process new streaming text from ASR.
     * Detects finalized sentences and triggers interpretation.
     * 
     * @param newText The latest full transcription from ASR
     * @param translationEnabled Whether to translate before speaking
     * @param scope Coroutine scope for async operations
     */
    suspend fun processStreamingText(
        newText: String,
        translationEnabled: Boolean,
        scope: CoroutineScope
    ) {
        if (!isTtsReady || newText.isEmpty()) return
        
        mutex.withLock {
            // Find newly finalized sentences
            val finalizedSentences = detectFinalizedSentences(previousText, newText)
            
            // Process each finalized sentence
            for (sentence in finalizedSentences) {
                if (sentence.length >= MIN_SENTENCE_LENGTH) {
                    scope.launch(Dispatchers.IO) {
                        processSentence(sentence, translationEnabled)
                    }
                }
            }
            
            // Also check if there's a remaining sentence ending with punctuation in the new text
            // This handles cases where the last sentence is in the newly added portion
            // Process it immediately even if it's the last sentence
            if (processedUpToIndex < newText.length) {
                val remainingText = newText.substring(processedUpToIndex).trim()
                if (remainingText.isNotEmpty() && remainingText.length >= MIN_SENTENCE_LENGTH) {
                    val lastChar = remainingText.lastOrNull()
                    if (lastChar != null && SENTENCE_ENDINGS.contains(lastChar)) {
                        // This is a complete sentence ending with punctuation
                        // Process it immediately
                        Log.d(TAG, "Processing remaining sentence ending with punctuation: '$remainingText'")
                        scope.launch(Dispatchers.IO) {
                            processSentence(remainingText, translationEnabled)
                        }
                        processedUpToIndex = newText.length
                    }
                }
            }
            
            previousText = newText
        }
    }
    
    /**
     * Detect sentences that have been finalized (punctuation + new content after).
     * 
     * Logic:
     * - Compare previous text with new text
     * - Find sentence endings in the overlapping portion
     * - If there's new content after a sentence ending, that sentence is final
     */
    private fun detectFinalizedSentences(oldText: String, newText: String): List<String> {
        val finalizedSentences = mutableListOf<String>()
        
        // If this is the first text, no finalized sentences yet
        if (oldText.isEmpty()) {
            processedUpToIndex = 0
            return emptyList()
        }
        
        // Find the common prefix between old and new text
        val minLength = minOf(oldText.length, newText.length)
        var commonPrefixEnd = 0
        for (i in 0 until minLength) {
            if (oldText[i] == newText[i]) {
                commonPrefixEnd = i + 1
            } else {
                break
            }
        }
        
        // Look for sentence endings in the unprocessed portion up to the common prefix
        var searchStart = processedUpToIndex
        while (searchStart < commonPrefixEnd) {
            // Find next sentence ending
            var sentenceEndIndex = -1
            for (i in searchStart until commonPrefixEnd) {
                if (SENTENCE_ENDINGS.contains(newText[i])) {
                    sentenceEndIndex = i
                    break
                }
            }
            
            if (sentenceEndIndex == -1) {
                // No sentence ending found in this segment
                break
            }
            
            // Process sentence immediately when punctuation is detected
            // No need to wait for content after punctuation
            val sentence = newText.substring(searchStart, sentenceEndIndex + 1).trim()
            if (sentence.isNotEmpty()) {
                finalizedSentences.add(sentence)
                Log.d(TAG, "Finalized sentence: '$sentence'")
            }
            processedUpToIndex = sentenceEndIndex + 1
            searchStart = sentenceEndIndex + 1
        }
        
        return finalizedSentences
    }
    
    /**
     * Process a single finalized sentence: translate (if enabled) and speak.
     */
    private suspend fun processSentence(sentence: String, translationEnabled: Boolean) {
        try {
            val textToSpeak: String
            val originalText: String = sentence
            
            if (translationEnabled && translationManager.isTranslationEnabled()) {
                // Translate the sentence (this is the main source of delay)
                val translated = translationManager.translate(sentence)
                textToSpeak = translated
                
                Log.d(TAG, "Translation: '$sentence' -> '$translated'")
                onSentenceSpoken?.invoke(originalText, translated)
                
                // Ensure TTS language is set to target language (only if changed or first time)
                val targetLanguage = translationManager.getCurrentLanguage()
                if (targetLanguage != TranslationManager.Companion.Language.NONE && 
                    currentTtsLanguage != targetLanguage) {
                    setTtsLanguage(targetLanguage)
                }
            } else {
                // Speak original (English)
                textToSpeak = sentence
                onSentenceSpoken?.invoke(originalText, null)
            }
            
            // Speak the text
            speak(textToSpeak)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing sentence", e)
        }
    }
    
    /**
     * Speak text using TTS.
     */
    private fun speak(text: String) {
        if (!isTtsReady) {
            Log.w(TAG, "TTS not ready, cannot speak")
            return
        }
        
        Log.d(TAG, "Speaking: '$text'")
        
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, System.currentTimeMillis().toString())
    }
    
    /**
     * Finalize and process any remaining sentences when recording stops.
     * Handles the "last sentence" problem where there's no content after punctuation.
     */
    suspend fun finalizeRemainingSentences(translationEnabled: Boolean, scope: CoroutineScope) {
        mutex.withLock {
            if (previousText.isEmpty() || processedUpToIndex >= previousText.length) {
                return
            }
            
            // Get any remaining unprocessed text
            val remainingText = previousText.substring(processedUpToIndex).trim()
            
            if (remainingText.isEmpty() || remainingText.length < MIN_SENTENCE_LENGTH) {
                return
            }
            
            // Check if remaining text ends with sentence punctuation
            val lastChar = remainingText.lastOrNull()
            val endsWithPunctuation = lastChar != null && SENTENCE_ENDINGS.contains(lastChar)
            
            if (endsWithPunctuation) {
                // This is a complete sentence that hasn't been processed yet
                Log.d(TAG, "Finalizing last sentence: '$remainingText'")
                scope.launch(Dispatchers.IO) {
                    processSentence(remainingText, translationEnabled)
                }
            } else {
                // Partial sentence - could still process it or skip it
                // For now, let's process it as-is (user might not have finished)
                Log.d(TAG, "Processing incomplete last sentence: '$remainingText'")
                scope.launch(Dispatchers.IO) {
                    processSentence(remainingText, translationEnabled)
                }
            }
            
            processedUpToIndex = previousText.length
        }
    }
    
    /**
     * Reset buffering state (call when starting a new recording session).
     */
    fun reset() {
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                previousText = ""
                processedUpToIndex = 0
                
                // Stop any ongoing speech
                tts?.stop()
                
                Log.d(TAG, "Interpretation manager reset")
            }
        }
    }
    
    /**
     * Check if TTS is ready.
     */
    fun isTtsReady(): Boolean = isTtsReady
    
    /**
     * Shutdown TTS and release resources.
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
        currentTtsLocale = null
        currentTtsLanguage = null
        Log.d(TAG, "TTS shutdown")
    }
}
