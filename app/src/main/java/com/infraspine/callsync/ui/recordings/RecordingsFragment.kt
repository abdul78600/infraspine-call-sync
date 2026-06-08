package com.infraspine.callsync.ui.recordings

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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

        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                viewModel.setSearchQuery(s?.toString().orEmpty())
            }
        })

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

            val empty = recordings.isEmpty()
            binding.recyclerRecordings.visibility = if (empty) View.GONE else View.VISIBLE
            binding.textEmptyState.visibility = if (empty) View.VISIBLE else View.GONE

            if (empty) {
                val query = viewModel.searchQuery.value.orEmpty()
                binding.textEmptyState.text = if (query.isNotBlank()) {
                    getString(R.string.no_search_results, query)
                } else {
                    getString(R.string.no_recordings_found)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
