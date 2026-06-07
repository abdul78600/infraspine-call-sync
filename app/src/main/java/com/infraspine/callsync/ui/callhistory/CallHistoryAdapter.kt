package com.infraspine.callsync.ui.callhistory

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.infraspine.callsync.R
import com.infraspine.callsync.databinding.ItemCallHistoryBinding
import com.infraspine.callsync.domain.model.CallHistoryEntry
import com.infraspine.callsync.ui.common.displayLabel
import com.infraspine.callsync.ui.common.orUnmatched
import com.infraspine.callsync.ui.common.toDisplayDateTime
import com.infraspine.callsync.ui.common.toDisplayDuration

class CallHistoryAdapter : ListAdapter<CallHistoryEntry, CallHistoryAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCallHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemCallHistoryBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: CallHistoryEntry) {
            val context = binding.root.context

            binding.textPhoneNumber.text = entry.phoneNumber.orUnmatched(context)
            binding.textCallMeta.text = "${entry.callType.displayLabel(context)} • " +
                "${entry.startedAt.toDisplayDateTime()} • " +
                entry.durationSeconds.toDisplayDuration()

            val statusRes = if (entry.hasRecording) R.color.status_synced else R.color.status_unmatched
            val labelRes = if (entry.hasRecording) R.string.call_has_recording else R.string.call_no_recording
            val color = ContextCompat.getColor(context, statusRes)

            binding.textRecordingStatus.text = context.getString(labelRes)
            binding.textRecordingStatus.setTextColor(color)
            binding.textRecordingStatus.background.mutate().setTint(withAlpha(color, 30))
        }

        private fun withAlpha(color: Int, alphaPercent: Int): Int {
            val alpha = (255 * alphaPercent / 100)
            return (color and 0x00FFFFFF) or (alpha shl 24)
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<CallHistoryEntry>() {
            override fun areItemsTheSame(oldItem: CallHistoryEntry, newItem: CallHistoryEntry) =
                oldItem.startedAt == newItem.startedAt && oldItem.phoneNumber == newItem.phoneNumber

            override fun areContentsTheSame(oldItem: CallHistoryEntry, newItem: CallHistoryEntry) =
                oldItem == newItem
        }
    }
}
