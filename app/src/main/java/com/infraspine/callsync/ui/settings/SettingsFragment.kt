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
import com.google.android.material.snackbar.Snackbar
import com.infraspine.callsync.CallSyncApplication
import com.infraspine.callsync.R
import com.infraspine.callsync.databinding.FragmentSettingsBinding
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

        binding.buttonCheckForUpdates.setOnClickListener {
            binding.buttonDownloadUpdate.visibility = View.GONE
            viewModel.checkForUpdates()
        }

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            // Avoid clobbering in-progress edits by only setting text when it actually differs.
            if (binding.editCrmUrl.text?.toString() != state.crmServerUrl) {
                binding.editCrmUrl.setText(state.crmServerUrl)
            }
            if (binding.editAgentToken.text?.toString() != state.agentToken) {
                binding.editAgentToken.setText(state.agentToken)
            }
            binding.textDeviceId.text = getString(R.string.device_id) + ": ${state.deviceId}"
            binding.switchWifiOnly.isChecked = state.syncOnWifiOnly
            binding.switchAutoSync.isChecked = state.autoSyncEnabled
            binding.switchDummyMode.isChecked = state.dummyTestMode
        }

        viewModel.saved.observe(viewLifecycleOwner) { saved ->
            if (saved == true) {
                Snackbar.make(binding.root, R.string.settings_saved, Snackbar.LENGTH_SHORT).show()
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
        viewModel.save(
            crmServerUrl = binding.editCrmUrl.text?.toString().orEmpty(),
            agentToken = binding.editAgentToken.text?.toString().orEmpty(),
            syncOnWifiOnly = binding.switchWifiOnly.isChecked,
            autoSyncEnabled = binding.switchAutoSync.isChecked,
            dummyTestMode = binding.switchDummyMode.isChecked
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
