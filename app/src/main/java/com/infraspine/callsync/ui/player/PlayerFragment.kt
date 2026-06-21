package com.infraspine.callsync.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import com.infraspine.callsync.CallSyncApplication
import com.infraspine.callsync.R
import com.infraspine.callsync.databinding.FragmentPlayerBinding
import com.infraspine.callsync.domain.model.SyncStatus
import com.infraspine.callsync.ui.common.colorRes
import com.infraspine.callsync.ui.common.displayLabel
import com.infraspine.callsync.ui.common.orUnmatched
import com.infraspine.callsync.ui.common.toDisplayDateTime
import com.infraspine.callsync.ui.common.toDisplayDuration

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private val recordingId: Long by lazy { requireArguments().getLong(ARG_RECORDING_ID) }

    private val container by lazy { (requireActivity().application as CallSyncApplication).container }

    private val viewModel: PlayerViewModel by viewModels {
        PlayerViewModel.Factory(requireContext(), recordingId, container)
    }

    private var isDragging = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonPlayPause.setOnClickListener {
            viewModel.recording.value?.let { viewModel.togglePlayback(it.fileUri) }
        }

        binding.buttonSkipBack.setOnClickListener { viewModel.seekBy(-SKIP_INTERVAL_MS) }
        binding.buttonSkipForward.setOnClickListener { viewModel.seekBy(SKIP_INTERVAL_MS) }
        binding.buttonPlaybackSpeed.setOnClickListener { viewModel.cyclePlaybackSpeed() }

        binding.waveformView.onSeek = { fraction ->
            isDragging = true
            val durationMs = viewModel.durationMs.value ?: 0
            viewModel.seekTo((fraction * durationMs).toInt())
            isDragging = false
        }

        viewModel.waveform.observe(viewLifecycleOwner) { data ->
            binding.waveformView.setAmplitudes(data)
        }

        viewModel.recording.observe(viewLifecycleOwner) { recording ->
            if (recording == null) return@observe

            viewModel.loadWaveform(recording.fileUri)  // no-op if same URI already decoded

            binding.textFileName.text = recording.fileName
            binding.textPhoneNumber.text = recording.phoneNumber.orUnmatched(requireContext())

            binding.textCallMeta.text = if (recording.callStartedAt != null) {
                "${recording.callType.name.lowercase().replaceFirstChar { it.uppercase() }} • " +
                    "${recording.callStartedAt.toDisplayDateTime()} • " +
                    recording.durationSeconds.toDisplayDuration()
            } else {
                recording.lastModified.toDisplayDateTime()
            }

            val statusColor = ContextCompat.getColor(requireContext(), recording.syncStatus.colorRes())
            binding.textSyncStatusBadge.text = recording.syncStatus.displayLabel(requireContext())
            binding.textSyncStatusBadge.backgroundTintList =
                android.content.res.ColorStateList.valueOf(statusColor)

            val isFailed = recording.syncStatus == SyncStatus.FAILED
            binding.layoutErrorDetail.isVisible = isFailed
            if (isFailed) {
                binding.textSyncErrorDetail.text =
                    recording.errorMessage?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.error_upload_failed)
            }

            val isSynced = recording.syncStatus == SyncStatus.SYNCED
            binding.textSyncUploadedAt.isVisible = isSynced
            if (isSynced && recording.uploadedAt != null) {
                binding.textSyncUploadedAt.text =
                    "Uploaded on ${recording.uploadedAt.toDisplayDateTime()}"
            }
        }

        viewModel.isPlaying.observe(viewLifecycleOwner) { playing ->
            binding.buttonPlayPause.text = getString(if (playing) R.string.pause else R.string.play)
            binding.buttonPlayPause.setIconResource(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
        }

        viewModel.playbackSpeed.observe(viewLifecycleOwner) { speed ->
            binding.buttonPlaybackSpeed.text = getString(R.string.playback_speed_format, speed)
        }

        viewModel.durationMs.observe(viewLifecycleOwner) { duration ->
            binding.textDuration.text = (duration / 1000L).toDisplayDuration()
        }

        viewModel.positionMs.observe(viewLifecycleOwner) { position ->
            binding.textPosition.text = (position / 1000L).toDisplayDuration()
            if (!isDragging) {
                val duration = viewModel.durationMs.value?.takeIf { it > 0 } ?: return@observe
                binding.waveformView.setProgress(position.toFloat() / duration)
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { error ->
                val messageRes = when (error) {
                    PlaybackError.FailedToLoad -> R.string.player_load_failed
                    PlaybackError.PlaybackFailed -> R.string.player_playback_failed
                }
                Snackbar.make(binding.root, messageRes, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_RECORDING_ID = "recordingId"
        private const val SKIP_INTERVAL_MS = 10_000

        fun args(recordingId: Long): Bundle = bundleOf(ARG_RECORDING_ID to recordingId)
    }
}
