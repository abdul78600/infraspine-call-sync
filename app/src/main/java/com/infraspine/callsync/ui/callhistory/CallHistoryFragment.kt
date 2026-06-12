package com.infraspine.callsync.ui.callhistory

import android.Manifest
import android.os.Bundle
import android.widget.ArrayAdapter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.infraspine.callsync.CallSyncApplication
import com.infraspine.callsync.R
import com.infraspine.callsync.databinding.FragmentCallHistoryBinding
import com.infraspine.callsync.ui.common.PermissionHelper

class CallHistoryFragment : Fragment() {

    private var _binding: FragmentCallHistoryBinding? = null
    private val binding get() = _binding!!

    private val container by lazy { (requireActivity().application as CallSyncApplication).container }

    private val viewModel: CallHistoryViewModel by viewModels {
        CallHistoryViewModel.Factory(container)
    }

    private val adapter = CallHistoryAdapter()
    private lateinit var limitAdapter: ArrayAdapter<String>

    private val callLogPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.refresh()
        } else {
            viewModel.onPermissionDenied()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCallHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerCallHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerCallHistory.adapter = adapter
        setUpLimitFilter()

        binding.swipeRefresh.setOnRefreshListener { requestAndLoad(forceRefresh = true) }

        observeViewModel()
        requestAndLoad(forceRefresh = false)
    }

    override fun onResume() {
        super.onResume()
        requestAndLoad(forceRefresh = true)
    }

    private fun setUpLimitFilter() {
        limitAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_1,
            viewModel.limitOptions.map { it.label }
        )
        binding.editDisplayLimit.setAdapter(limitAdapter)
        binding.editDisplayLimit.setText(viewModel.selectedLimitLabel(), false)
        binding.editDisplayLimit.setOnItemClickListener { _, _, position, _ ->
            viewModel.setDisplayLimit(viewModel.limitOptions[position])
        }
    }

    private fun requestAndLoad(forceRefresh: Boolean) {
        if (!PermissionHelper.hasCallLogPermission(requireContext())) {
            binding.swipeRefresh.isRefreshing = false
            callLogPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
            return
        }

        if (forceRefresh) viewModel.refresh() else viewModel.loadIfNeeded()
    }

    private fun observeViewModel() {
        viewModel.calls.observe(viewLifecycleOwner) { calls ->
            adapter.submitList(calls)
            val empty = calls.isEmpty()
            binding.textEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
            binding.recyclerCallHistory.visibility = if (empty) View.GONE else View.VISIBLE
            binding.cardHistoryFilter.visibility = View.VISIBLE
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            binding.progressBar.visibility = if (loading && binding.recyclerCallHistory.visibility != View.VISIBLE) {
                View.VISIBLE
            } else {
                View.GONE
            }
            if (!loading) binding.swipeRefresh.isRefreshing = false
            binding.editDisplayLimit.isEnabled = !loading
        }

        viewModel.displayState.observe(viewLifecycleOwner) { state ->
            binding.editDisplayLimit.setText(state.selectedLimit.label, false)
            binding.textLoadedSummary.text = if (state.selectedLimit.limit == null) {
                getString(R.string.call_history_showing_all, state.loadedItems)
            } else {
                getString(R.string.call_history_showing_latest, state.loadedItems, state.selectedLimit.label)
            }
        }

        viewModel.message.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { message ->
                val text = when (message) {
                    CallHistoryMessage.PermissionDenied -> getString(R.string.error_call_log_permission)
                    is CallHistoryMessage.LoadError -> message.message
                }
                Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
