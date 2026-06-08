package com.infraspine.callsync.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.infraspine.callsync.CallSyncApplication
import com.infraspine.callsync.R
import com.infraspine.callsync.databinding.FragmentPlayerBinding
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

    private var isUserSeeking = false

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

        binding.sliderProgress.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                isUserSeeking = false
                viewModel.seekTo(slider.value.toInt())
            }
        })

        viewModel.recording.observe(viewLifecycleOwner) { recording ->
            if (recording == null) return@observe

            binding.textFileName.text = recording.fileName
            binding.textPhoneNumber.text = recording.phoneNumber.orUnmatched(requireContext())

            binding.textCallMeta.text = if (recording.callStartedAt != null) {
                "${recording.callType.name.lowercase().replaceFirstChar { it.uppercase() }} • " +
                    "${recording.callStartedAt.toDisplayDateTime()} • " +
                    recording.durationSeconds.toDisplayDuration()
            } else {
                recording.lastModified.toDisplayDateTime()
            }
        }

        viewModel.isPlaying.observe(viewLifecycleOwner) { playing ->
            binding.buttonPlayPause.text = getString(if (playing) R.string.pause else R.string.play)
            binding.buttonPlayPause.setIconResource(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
        }

        viewModel.durationMs.observe(viewLifecycleOwner) { duration ->
            binding.sliderProgress.value = binding.sliderProgress.valueFrom
            binding.sliderProgress.valueTo = duration.coerceAtLeast(1).toFloat()
            binding.textDuration.text = (duration / 1000L).toDisplayDuration()
        }

        viewModel.positionMs.observe(viewLifecycleOwner) { position ->
            binding.textPosition.text = (position / 1000L).toDisplayDuration()
            if (!isUserSeeking) {
                val clamped = position.toFloat().coerceIn(0f, binding.sliderProgress.valueTo)
                binding.sliderProgress.value = clamped
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

        fun args(recordingId: Long): Bundle = bundleOf(ARG_RECORDING_ID to recordingId)
    }
}
