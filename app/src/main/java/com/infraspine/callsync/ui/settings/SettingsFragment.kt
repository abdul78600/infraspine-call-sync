package com.infraspine.callsync.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.infraspine.callsync.CallSyncApplication
import com.infraspine.callsync.R
import com.infraspine.callsync.databinding.FragmentSettingsBinding
import com.infraspine.callsync.domain.sync.CallLogInitialSyncMode
import com.infraspine.callsync.update.UpdateCheckResult

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val container by lazy { (requireActivity().application as CallSyncApplication).container }

    private val viewModel: SettingsViewModel by viewModels {
        SettingsViewModel.Factory(requireContext(), container)
    }

    private val openFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            container.folderManager.persistFolderSelection(uri)
            refreshFolderLabel()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonChangeFolder.setOnClickListener {
            openFolderLauncher.launch(container.folderManager.currentFolderUri())
        }

        binding.buttonSaveSettings.setOnClickListener { saveSettings() }
        binding.buttonLogout.setOnClickListener { viewModel.logout() }

        binding.buttonCheckForUpdates.setOnClickListener {
            binding.buttonDownloadUpdate.visibility = View.GONE
            viewModel.checkForUpdates()
        }

        binding.buttonResetSyncHistory.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.reset_sync_history_confirm_title)
                .setMessage(R.string.reset_sync_history_confirm_message)
                .setPositiveButton(R.string.reset_sync_history) { _, _ -> viewModel.resetSyncHistory() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            // Avoid clobbering in-progress edits by only setting text when it actually differs.
            if (binding.editCrmUrl.text?.toString() != state.crmServerUrl) {
                binding.editCrmUrl.setText(state.crmServerUrl)
            }
            binding.textLoggedInUser.text = getString(
                R.string.logged_in_as,
                state.loggedInUser.ifBlank { getString(R.string.login_unknown_user) }
            )
            binding.textDeviceId.text = getString(R.string.device_id) + ": ${state.deviceId}"
            binding.switchWifiOnly.isChecked = state.syncOnWifiOnly
            binding.switchAutoSync.isChecked = state.autoSyncEnabled
            binding.switchDummyMode.isChecked = state.dummyTestMode
            when (state.callLogInitialSyncMode) {
                CallLogInitialSyncMode.FROM_NOW -> binding.radioSyncFromNow.isChecked = true
                CallLogInitialSyncMode.FULL_HISTORY -> binding.radioUploadFullHistory.isChecked = true
            }
        }

        viewModel.saved.observe(viewLifecycleOwner) { saved ->
            if (saved == true) {
                Snackbar.make(binding.root, R.string.settings_saved, Snackbar.LENGTH_SHORT).show()
            }
        }

        viewModel.loggedOut.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                findNavController().navigate(
                    R.id.loginFragment,
                    null,
                    NavOptions.Builder()
                        .setPopUpTo(R.id.nav_graph, true)
                        .build()
                )
            }
        }

        viewModel.isCheckingForUpdate.observe(viewLifecycleOwner) { checking ->
            binding.buttonCheckForUpdates.isEnabled = !checking
            binding.buttonCheckForUpdates.text =
                getString(if (checking) R.string.checking_for_updates else R.string.check_for_updates)
        }

        viewModel.updateResult.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { handleUpdateResult(it) }
        }

        viewModel.resetSyncHistoryDone.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let {
                Snackbar.make(binding.root, R.string.reset_sync_history_done, Snackbar.LENGTH_SHORT).show()
            }
        }

        refreshFolderLabel()
    }

    private fun handleUpdateResult(result: UpdateCheckResult) {
        when (result) {
            is UpdateCheckResult.UpToDate -> {
                binding.textUpdateStatus.visibility = View.VISIBLE
                binding.textUpdateStatus.text = getString(R.string.update_up_to_date)
                binding.buttonDownloadUpdate.visibility = View.GONE
            }

            is UpdateCheckResult.UpdateAvailable -> {
                binding.textUpdateStatus.visibility = View.VISIBLE
                binding.textUpdateStatus.text = getString(R.string.update_available_title) +
                    " — " + getString(R.string.update_available_message)
                binding.buttonDownloadUpdate.visibility = View.VISIBLE
                binding.buttonDownloadUpdate.setOnClickListener {
                    openDownloadPage(result.downloadUrl)
                }
            }

            is UpdateCheckResult.Error -> {
                binding.textUpdateStatus.visibility = View.GONE
                binding.buttonDownloadUpdate.visibility = View.GONE
                Snackbar.make(
                    binding.root,
                    getString(R.string.update_check_failed, result.message),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openDownloadPage(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Snackbar.make(binding.root, getString(R.string.update_check_failed, url), Snackbar.LENGTH_LONG).show()
        }
    }

    private fun saveSettings() {
        val selectedMode = when (binding.radioGroupInitialSync.checkedRadioButtonId) {
            R.id.radioUploadFullHistory -> CallLogInitialSyncMode.FULL_HISTORY
            else -> CallLogInitialSyncMode.FROM_NOW
        }
        if (selectedMode == CallLogInitialSyncMode.FULL_HISTORY &&
            viewModel.uiState.value?.callLogInitialSyncMode != CallLogInitialSyncMode.FULL_HISTORY
        ) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.call_log_upload_full_history_confirm_title)
                .setMessage(R.string.call_log_upload_full_history_confirm_message)
                .setPositiveButton(R.string.call_log_upload_full_history) { _, _ ->
                    persistSettings(selectedMode)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        persistSettings(selectedMode)
    }

    private fun persistSettings(callLogInitialSyncMode: CallLogInitialSyncMode) {
        viewModel.save(
            crmServerUrl = binding.editCrmUrl.text?.toString().orEmpty(),
            syncOnWifiOnly = binding.switchWifiOnly.isChecked,
            autoSyncEnabled = binding.switchAutoSync.isChecked,
            dummyTestMode = binding.switchDummyMode.isChecked,
            callLogInitialSyncMode = callLogInitialSyncMode
        )
    }

    private fun refreshFolderLabel() {
        binding.textCurrentFolder.text =
            container.folderManager.currentFolderDisplayPath() ?: getString(R.string.no_folder_selected)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
