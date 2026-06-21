package com.infraspine.callsync.ui.dashboard

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.infraspine.callsync.CallSyncApplication
import com.infraspine.callsync.R
import com.infraspine.callsync.databinding.FragmentDashboardBinding
import com.infraspine.callsync.ui.common.PermissionHelper
import com.infraspine.callsync.ui.recordings.RecordingFilter
import com.infraspine.callsync.ui.recordings.RecordingsFragment

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val container by lazy { (requireActivity().application as CallSyncApplication).container }

    private val viewModel: DashboardViewModel by viewModels {
        DashboardViewModel.Factory(container)
    }

    private val callLogPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Snackbar.make(binding.root, R.string.error_call_log_permission, Snackbar.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        viewModel.scanNow()
    }

    private val syncCallLogPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Snackbar.make(binding.root, R.string.error_call_log_permission, Snackbar.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        viewModel.syncNow()
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

        binding.buttonScanNow.setOnClickListener { startScan() }

        binding.buttonSyncNow.setOnClickListener { startSync() }

        binding.buttonViewRecordings.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_recordings)
        }

        binding.buttonViewCallHistory.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_call_history)
        }

        binding.buttonSyncHistory.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_sync_history)
        }

        binding.buttonSettings.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_settings)
        }

        binding.swipeRefresh.setOnRefreshListener {
            startScan()
        }

        setUpStatCardNavigation()
        observeViewModel()
    }

    /** Tapping a summary card jumps to Recordings pre-filtered to that status. */
    private fun setUpStatCardNavigation() {
        val cardsToFilters = listOf(
            binding.cardTotal to RecordingFilter.ALL,
            binding.cardPending to RecordingFilter.PENDING,
            binding.cardSynced to RecordingFilter.SYNCED,
            binding.cardFailed to RecordingFilter.FAILED,
            binding.cardUnmatched to RecordingFilter.UNMATCHED
        )
        cardsToFilters.forEach { (card, filter) ->
            card.setOnClickListener {
                findNavController().navigate(
                    R.id.action_dashboard_to_recordings,
                    RecordingsFragment.args(filter)
                )
            }
        }
    }

    private fun startScan() {
        if (!container.folderManager.hasValidFolderSelection()) {
            Snackbar.make(binding.root, R.string.error_no_folder, Snackbar.LENGTH_LONG)
                .setAction(R.string.settings) {
                    findNavController().navigate(R.id.action_dashboard_to_settings)
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

    private fun startSync() {
        if (!PermissionHelper.hasCallLogPermission(requireContext())) {
            syncCallLogPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
            return
        }

        viewModel.syncNow()
    }

    private fun observeViewModel() {
        viewModel.counts.observe(viewLifecycleOwner) { counts ->
            binding.textTotalCount.text = counts.total.toString()
            binding.textPendingCount.text = counts.pending.toString()
            binding.textSyncedCount.text = counts.synced.toString()
            binding.textFailedCount.text = counts.failed.toString()
            binding.textUnmatchedCount.text = counts.unmatched.toString()

            binding.textSyncStatus.text = when {
                counts.pending > 0 -> getString(R.string.sync_status_pending_count, counts.pending)
                counts.total == 0 -> getString(R.string.sync_status_no_recordings)
                else -> getString(R.string.sync_status_all_synced)
            }
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
            if (!syncing) binding.cardSyncProgress.visibility = View.GONE
        }

        viewModel.syncProgress.observe(viewLifecycleOwner) { progress ->
            if (progress != null && progress.total > 0) {
                binding.cardSyncProgress.visibility = View.VISIBLE
                binding.textSyncProgressDetail.text =
                    "Uploading ${progress.current + 1} of ${progress.total} recordings…"
                binding.progressBarSync.max = progress.total
                binding.progressBarSync.progress = progress.current + 1
            } else if (viewModel.isSyncing.value == true) {
                binding.cardSyncProgress.visibility = View.VISIBLE
                binding.textSyncProgressDetail.text = "Preparing sync…"
                binding.progressBarSync.progress = 0
            } else {
                binding.cardSyncProgress.visibility = View.GONE
            }
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

            is DashboardMessage.SyncFinished -> {
                val summary = getString(
                    R.string.sync_complete,
                    message.uploaded,
                    message.skippedDuplicate,
                    message.failed,
                    message.callLogsUploaded,
                    message.callLogsSkippedDuplicate,
                    message.callLogsSkipped,
                    message.callLogsFailed
                )
                message.callLogsError?.takeIf { it.isNotBlank() }?.let { "$summary $it" } ?: summary
            }
            is DashboardMessage.SyncError -> message.message
            DashboardMessage.SyncNothingPending -> getString(R.string.status_pending) + ": 0"
            DashboardMessage.SyncNetworkUnavailable -> getString(R.string.error_network_unavailable)
            DashboardMessage.SyncWifiRequired -> getString(R.string.error_wifi_required)
            DashboardMessage.SyncApiNotConfigured -> getString(R.string.error_api_url_missing)
            DashboardMessage.SyncAuthRequired -> {
                showSessionExpiredDialog()
                return
            }
        }
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
    }

    private fun showSessionExpiredDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Session Expired")
            .setMessage("Your CRM server session has expired. Please login again to continue syncing recordings.")
            .setPositiveButton("Login Again") { _, _ ->
                findNavController().navigate(R.id.loginFragment)
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
