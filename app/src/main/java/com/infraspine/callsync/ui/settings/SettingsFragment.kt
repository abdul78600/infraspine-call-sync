package com.infraspine.callsync.ui.settings

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

        refreshFolderLabel()
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
