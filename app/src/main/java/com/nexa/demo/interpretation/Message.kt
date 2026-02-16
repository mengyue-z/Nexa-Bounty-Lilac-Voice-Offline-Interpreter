package com.nexa.demo.interpretation

/**
 * Represents the type of message in the conversation.
 */
enum class MessageType(val value: Int) {
    /** User's audio input (shows as "Recording...") */
    USER(0),
    /** ASR transcription result */
    ASSISTANT(1),
    /** Status/info message */
    STATUS(2);

    companion object {
        fun from(value: Int): MessageType =
            entries.firstOrNull { it.value == value } ?: STATUS
    }
}

/**
 * Data class representing a message in the conversation.
 * 
 * @param content The text content of the message
 * @param type The type of message (USER, ASSISTANT, or STATUS)
 * @param timestamp When the message was created
 */
data class Message(
    val content: String,
    val type: MessageType,
    val timestamp: Long = System.currentTimeMillis()
)
