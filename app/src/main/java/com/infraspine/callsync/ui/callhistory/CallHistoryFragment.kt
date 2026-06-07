package com.infraspine.callsync.ui.callhistory

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

        val layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerCallHistory.layoutManager = layoutManager
        binding.recyclerCallHistory.adapter = adapter
        binding.recyclerCallHistory.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val totalItemCount = layoutManager.itemCount
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (lastVisible >= totalItemCount - LOAD_MORE_THRESHOLD) {
                    viewModel.loadNextPage()
                }
            }
        })

        binding.swipeRefresh.setOnRefreshListener { requestAndLoad(forceRefresh = true) }

        observeViewModel()
        requestAndLoad(forceRefresh = false)
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
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            binding.progressBar.visibility = if (loading && binding.recyclerCallHistory.visibility != View.VISIBLE) {
                View.VISIBLE
            } else {
                View.GONE
            }
            if (!loading) binding.swipeRefresh.isRefreshing = false
        }

        viewModel.isLoadingMore.observe(viewLifecycleOwner) { loadingMore ->
            binding.progressLoadMore.visibility = if (loadingMore) View.VISIBLE else View.GONE
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

    companion object {
        /** Trigger the next page fetch when the user scrolls within this many items of the end. */
        private const val LOAD_MORE_THRESHOLD = 10
    }
}
