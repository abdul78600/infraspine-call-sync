package com.infraspine.callsync.ui.recordings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.infraspine.callsync.CallSyncApplication
import com.infraspine.callsync.R
import com.infraspine.callsync.databinding.FragmentRecordingsBinding
import com.infraspine.callsync.ui.player.PlayerFragment

class RecordingsFragment : Fragment() {

    private var _binding: FragmentRecordingsBinding? = null
    private val binding get() = _binding!!

    private val container by lazy { (requireActivity().application as CallSyncApplication).container }

    private val viewModel: RecordingsViewModel by viewModels {
        RecordingsViewModel.Factory(container)
    }

    private val adapter = RecordingsAdapter { recording ->
        findNavController().navigate(
            R.id.action_recordings_to_player,
            PlayerFragment.args(recording.id)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecordingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerRecordings.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerRecordings.adapter = adapter

        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                binding.chipPending.id -> RecordingFilter.PENDING
                binding.chipSynced.id -> RecordingFilter.SYNCED
                binding.chipFailed.id -> RecordingFilter.FAILED
                binding.chipUnmatched.id -> RecordingFilter.UNMATCHED
                else -> RecordingFilter.ALL
            }
            viewModel.setFilter(filter)
        }

        viewModel.recordings.observe(viewLifecycleOwner) { recordings ->
            adapter.submitList(recordings)
            binding.textEmptyState.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerRecordings.visibility = if (recordings.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
