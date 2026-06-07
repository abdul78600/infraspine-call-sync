package com.infraspine.callsync.ui.dashboard

import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.infraspine.callsync.CallSyncApplication
import com.infraspine.callsync.R
import com.infraspine.callsync.databinding.FragmentDashboardBinding
import com.infraspine.callsync.ui.common.PermissionHelper

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val container by lazy { (requireActivity().application as CallSyncApplication).container }

    private val viewModel: DashboardViewModel by viewModels {
        DashboardViewModel.Factory(container)
    }

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            container.folderManager.persistFolderSelection(uri)
            refreshFolderLabel()
            Snackbar.make(binding.root, getString(R.string.select_folder) + " ✓", Snackbar.LENGTH_SHORT).show()
        }
    }

    private val callLogPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Snackbar.make(binding.root, R.string.error_call_log_permission, Snackbar.LENGTH_LONG).show()
        }
        viewModel.scanNow()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonSelectFolder.setOnClickListener {
            openFolderLauncher.launch(container.folderManager.currentFolderUri())
        }

        binding.buttonScanNow.setOnClickListener { startScan() }

        binding.buttonSyncNow.setOnClickListener { viewModel.syncNow() }

        binding.buttonViewRecordings.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_recordings)
        }

        binding.buttonViewCallHistory.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_call_history)
        }

        binding.buttonSettings.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_settings)
        }

        binding.swipeRefresh.setOnRefreshListener {
            startScan()
        }

        observeViewModel()
        refreshFolderLabel()
    }

    override fun onResume() {
        super.onResume()
        refreshFolderLabel()
    }

    private fun startScan() {
        if (!container.folderManager.hasValidFolderSelection()) {
            Snackbar.make(binding.root, R.string.error_no_folder, Snackbar.LENGTH_LONG)
                .setAction(R.string.select_folder) {
                    openFolderLauncher.launch(null)
                }.show()
            binding.swipeRefresh.isRefreshing = false
            return
        }

        if (!PermissionHelper.hasCallLogPermission(requireContext())) {
            callLogPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
            return
        }

        viewModel.scanNow()
    }

    private fun refreshFolderLabel() {
        val label = viewModel.currentFolderLabel()
        binding.textSelectedFolder.text = label ?: getString(R.string.no_folder_selected)
        binding.buttonSelectFolder.text =
            if (label != null) getString(R.string.change_folder) else getString(R.string.select_folder)
    }

    private fun observeViewModel() {
        viewModel.counts.observe(viewLifecycleOwner) { counts ->
            binding.textTotalCount.text = counts.total.toString()
            binding.textPendingCount.text = counts.pending.toString()
            binding.textSyncedCount.text = counts.synced.toString()
            binding.textFailedCount.text = counts.failed.toString()
        }

        viewModel.isScanning.observe(viewLifecycleOwner) { scanning ->
            binding.progressBar.visibility = if (scanning) View.VISIBLE else View.GONE
            binding.buttonScanNow.isEnabled = !scanning
            binding.swipeRefresh.isRefreshing = scanning && binding.swipeRefresh.isRefreshing
            if (!scanning) binding.swipeRefresh.isRefreshing = false
        }

        viewModel.isSyncing.observe(viewLifecycleOwner) { syncing ->
            binding.buttonSyncNow.isEnabled = !syncing
            binding.buttonSyncNow.text = getString(if (syncing) R.string.sync_in_progress else R.string.sync_now)
        }

        viewModel.message.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { showMessage(it) }
        }
    }

    private fun showMessage(message: DashboardMessage) {
        val text = when (message) {
            is DashboardMessage.ScanFinished -> getString(R.string.scan_complete, message.newCount)
            DashboardMessage.ScanNoFolder -> getString(R.string.error_no_folder)
            DashboardMessage.ScanNoRecordings -> getString(R.string.error_no_recordings)
            DashboardMessage.ScanCallLogPermissionDenied -> getString(R.string.error_call_log_permission)
            is DashboardMessage.ScanError -> message.message

            is DashboardMessage.SyncFinished -> getString(R.string.sync_complete, message.uploaded, message.failed)
            DashboardMessage.SyncNothingPending -> getString(R.string.status_pending) + ": 0"
            DashboardMessage.SyncNetworkUnavailable -> getString(R.string.error_network_unavailable)
            DashboardMessage.SyncWifiRequired -> getString(R.string.error_wifi_required)
            DashboardMessage.SyncApiNotConfigured -> getString(R.string.error_api_url_missing)
        }
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
