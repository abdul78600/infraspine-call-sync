package com.infraspine.callsync.ui.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.infraspine.callsync.CallSyncApplication
import com.infraspine.callsync.R
import com.infraspine.callsync.databinding.FragmentLoginBinding

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val container by lazy { (requireActivity().application as CallSyncApplication).container }

    private val viewModel: LoginViewModel by viewModels {
        LoginViewModel.Factory(container)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.editCrmUrl.setText(container.settingsStore.crmServerUrl.orEmpty())

        binding.buttonLogin.setOnClickListener {
            viewModel.login(
                serverUrl = binding.editCrmUrl.text?.toString().orEmpty(),
                email = binding.editEmail.text?.toString().orEmpty(),
                password = binding.editPassword.text?.toString().orEmpty()
            )
        }

        viewModel.isLoggingIn.observe(viewLifecycleOwner) { loggingIn ->
            binding.buttonLogin.isEnabled = !loggingIn
            binding.buttonLogin.text = getString(if (loggingIn) R.string.login_in_progress else R.string.login_button)
        }

        viewModel.event.observe(viewLifecycleOwner) { event ->
            event.getContentIfNotHandled()?.let { handleEvent(it) }
        }
    }

    private fun handleEvent(event: LoginUiEvent) {
        when (event) {
            LoginUiEvent.Success -> {
                findNavController().navigate(
                    R.id.dashboardFragment,
                    null,
                    NavOptions.Builder()
                        .setPopUpTo(R.id.nav_graph, true)
                        .build()
                )
            }
            is LoginUiEvent.Error -> {
                Snackbar.make(binding.root, event.message, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
