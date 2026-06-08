package com.infraspine.callsync.ui.recordings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.infraspine.callsync.R
import com.infraspine.callsync.data.local.entity.RecordingEntity
import com.infraspine.callsync.databinding.ItemRecordingBinding
import com.infraspine.callsync.domain.model.SyncStatus
import com.infraspine.callsync.ui.common.colorRes
import com.infraspine.callsync.ui.common.displayLabel
import com.infraspine.callsync.ui.common.orUnmatched
import com.infraspine.callsync.ui.common.toDisplayDateTime
import com.infraspine.callsync.ui.common.toDisplayDuration

class RecordingsAdapter(
    private val onRecordingClick: (RecordingEntity) -> Unit
) : ListAdapter<RecordingEntity, RecordingsAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, onRecordingClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val binding: ItemRecordingBinding,
        private val onRecordingClick: (RecordingEntity) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(recording: RecordingEntity) {
            val context = binding.root.context

            binding.root.setOnClickListener { onRecordingClick(recording) }

            binding.textFileName.text = recording.fileName
            binding.textPhoneNumber.text = recording.phoneNumber.orUnmatched(context)

            val callTypeLabel = if (recording.callStartedAt != null) {
                "${recording.callType.name.lowercase().replaceFirstChar { it.uppercase() }} • " +
                    "${recording.callStartedAt.toDisplayDateTime()} • " +
                    recording.durationSeconds.toDisplayDuration()
            } else {
                recording.lastModified.toDisplayDateTime()
            }
            binding.textCallMeta.text = callTypeLabel

            binding.textStatus.text = recording.syncStatus.displayLabel(context)
            val color = ContextCompat.getColor(context, recording.syncStatus.colorRes())
            binding.textStatus.setTextColor(color)
            binding.textStatus.background.mutate().setTint(withAlpha(color, 30))

            if (recording.syncStatus == SyncStatus.FAILED && !recording.errorMessage.isNullOrBlank()) {
                binding.textErrorMessage.visibility = View.VISIBLE
                binding.textErrorMessage.text =
                    context.getString(R.string.error_upload_failed) + ": ${recording.errorMessage}"
            } else {
                binding.textErrorMessage.visibility = View.GONE
            }
        }

        private fun withAlpha(color: Int, alphaPercent: Int): Int {
            val alpha = (255 * alphaPercent / 100)
            return (color and 0x00FFFFFF) or (alpha shl 24)
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<RecordingEntity>() {
            override fun areItemsTheSame(oldItem: RecordingEntity, newItem: RecordingEntity) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: RecordingEntity, newItem: RecordingEntity) =
                oldItem == newItem
        }
    }
}
