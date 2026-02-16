package com.nexa.demo.interpretation

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * Manager class for handling ML Kit translation.
 * Supports multiple target languages with automatic model download.
 */
class TranslationManager {
    
    companion object {
        private const val TAG = "TranslationManager"
        
        /**
         * Language options with their ML Kit language codes.
         */
        enum class Language(val displayName: String, val code: String) {
            NONE("None (Original)", ""),
            ENGLISH("English", TranslateLanguage.ENGLISH),
            SPANISH("Spanish (Español)", TranslateLanguage.SPANISH),
            FRENCH("French (Français)", TranslateLanguage.FRENCH),
            GERMAN("German (Deutsch)", TranslateLanguage.GERMAN),
            ITALIAN("Italian (Italiano)", TranslateLanguage.ITALIAN),
            PORTUGUESE("Portuguese (Português)", TranslateLanguage.PORTUGUESE),
            CHINESE("Chinese Simplified (中文)", TranslateLanguage.CHINESE),
            JAPANESE("Japanese (日本語)", TranslateLanguage.JAPANESE),
            KOREAN("Korean (한국어)", TranslateLanguage.KOREAN),
            ARABIC("Arabic (العربية)", TranslateLanguage.ARABIC),
            RUSSIAN("Russian (Русский)", TranslateLanguage.RUSSIAN),
            HINDI("Hindi (हिन्दी)", TranslateLanguage.HINDI);
            
            companion object {
                fun fromPosition(position: Int): Language {
                    return values().getOrNull(position) ?: NONE
                }
            }
        }
    }
    
    private var currentTranslator: Translator? = null
    private var currentTargetLanguage: Language = Language.NONE
    private var currentSourceLanguage: Language = Language.ENGLISH
    private var isModelDownloaded = false
    
    /**
     * Initialize translator for a specific source and target language.
     * Downloads the model if not already available.
     * 
     * @param targetLanguage The target language to translate to
     * @param sourceLanguage The source language to translate from (default: ENGLISH)
     * @param onProgress Callback for download progress and status
     */
    suspend fun initializeTranslator(
        targetLanguage: Language,
        sourceLanguage: Language = Language.ENGLISH,
        onProgress: (String) -> Unit = {}
    ): Result<Unit> {
        return try {
            // If NONE selected, just clear and return
            if (targetLanguage == Language.NONE || targetLanguage.code.isEmpty()) {
                currentTargetLanguage = Language.NONE
                return Result.success(Unit)
            }
            
            // If same languages, no need to reinitialize
            if (targetLanguage == currentTargetLanguage && 
                sourceLanguage == currentSourceLanguage && 
                currentTranslator != null && 
                isModelDownloaded) {
                Log.d(TAG, "Translator already initialized for ${sourceLanguage.displayName} -> ${targetLanguage.displayName}")
                return Result.success(Unit)
            }
            
            // Close existing translator
            currentTranslator?.close()
            currentTranslator = null
            isModelDownloaded = false
            
            // If source and target are the same, no translation needed
            if (sourceLanguage == targetLanguage) {
                currentTargetLanguage = targetLanguage
                currentSourceLanguage = sourceLanguage
                Log.d(TAG, "Source and target languages are the same (${sourceLanguage.displayName}), no translation needed")
                return Result.success(Unit)
            }
            
            Log.d(TAG, "Initializing translator for ${sourceLanguage.displayName} -> ${targetLanguage.displayName}")
            onProgress("Initializing translator...")
            
            // Create translator options (source language to target language)
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage.code)
                .setTargetLanguage(targetLanguage.code)
                .build()
            
            val translator = Translation.getClient(options)
            
            // Check if model is already downloaded
            val modelManager = RemoteModelManager.getInstance()
            val targetModel = TranslateRemoteModel.Builder(targetLanguage.code).build()
            
            val downloadedModels = modelManager.getDownloadedModels(TranslateRemoteModel::class.java).await()
            val isDownloaded = downloadedModels.any { it.language == targetLanguage.code }
            
            if (!isDownloaded) {
                Log.d(TAG, "Model not downloaded, downloading...")
                onProgress("Downloading translation model...")
                
                // Download the model
                val conditions = DownloadConditions.Builder()
                    .requireWifi()
                    .build()
                
                translator.downloadModelIfNeeded(conditions).await()
                Log.d(TAG, "Model downloaded successfully")
            } else {
                Log.d(TAG, "Model already downloaded")
            }
            
            currentTranslator = translator
            currentTargetLanguage = targetLanguage
            currentSourceLanguage = sourceLanguage
            isModelDownloaded = true
            
            onProgress("Translation ready")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing translator", e)
            currentTranslator?.close()
            currentTranslator = null
            currentTargetLanguage = Language.NONE
            currentSourceLanguage = Language.ENGLISH
            isModelDownloaded = false
            Result.failure(e)
        }
    }
    
    /**
     * Translate text to the current target language.
     * 
     * @param text The text to translate (in English)
     * @return Translated text, or original text if translation fails or is disabled
     */
    suspend fun translate(text: String): String {
        if (text.isEmpty()) return text
        
        // If no translator or NONE selected, return original
        if (currentTranslator == null || currentTargetLanguage == Language.NONE) {
            return text
        }
        
        return try {
            val translatedText = currentTranslator!!.translate(text).await()
            Log.d(TAG, "Translated: '$text' -> '$translatedText'")
            translatedText
        } catch (e: Exception) {
            Log.e(TAG, "Translation error", e)
            // Return original text on error
            text
        }
    }
    
    /**
     * Check if translation is currently enabled.
     */
    fun isTranslationEnabled(): Boolean {
        return currentTranslator != null && currentTargetLanguage != Language.NONE && isModelDownloaded
    }
    
    /**
     * Get the current target language.
     */
    fun getCurrentLanguage(): Language {
        return currentTargetLanguage
    }
    
    /**
     * Close the translator and free resources.
     */
    fun close() {
        currentTranslator?.close()
        currentTranslator = null
        currentTargetLanguage = Language.NONE
        isModelDownloaded = false
        Log.d(TAG, "Translator closed")
    }
}
