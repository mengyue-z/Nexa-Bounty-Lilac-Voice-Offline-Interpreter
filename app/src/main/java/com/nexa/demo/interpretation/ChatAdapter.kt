package com.nexa.demo.interpretation

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter for displaying messages in the ASR streaming demo.
 * Supports three message types: USER (audio input), ASSISTANT (transcription), and STATUS.
 */
class ChatAdapter(private val messages: List<Message>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemViewType(position: Int): Int {
        return messages[position].type.value
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (MessageType.from(viewType)) {
            MessageType.USER -> UserViewHolder(
                inflater.inflate(R.layout.item_user_message, parent, false)
            )
            MessageType.ASSISTANT -> AssistantViewHolder(
                inflater.inflate(R.layout.item_assistant_message, parent, false)
            )
            MessageType.STATUS -> StatusViewHolder(
                inflater.inflate(R.layout.item_status_message, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        when (holder) {
            is UserViewHolder -> holder.bind(message)
            is AssistantViewHolder -> holder.bind(message)
            is StatusViewHolder -> holder.bind(message)
        }
    }

    override fun getItemCount() = messages.size

    /**
     * ViewHolder for user messages (audio input indication).
     */
    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message)

        fun bind(message: Message) {
            tvMessage.text = message.content
        }
    }

    /**
     * ViewHolder for assistant messages (transcription results).
     */
    class AssistantViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message)

        fun bind(message: Message) {
            tvMessage.text = message.content
        }
    }

    /**
     * ViewHolder for status messages (info/system messages).
     */
    class StatusViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMessage: TextView = itemView.findViewById(R.id.tv_message)

        fun bind(message: Message) {
            tvMessage.text = message.content
        }
    }
}
