package com.infraspine.callsync.ui.callhistory

import android.Manifest
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import com.infraspine.callsync.CallSyncApplication
import com.infraspine.callsync.R
import com.infraspine.callsync.databinding.FragmentCallHistoryBinding
import com.infraspine.callsync.ui.common.PermissionHelper
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class CallHistoryFragment : Fragment() {

    private var _binding: FragmentCallHistoryBinding? = null
    private val binding get() = _binding!!

    private val container by lazy { (requireActivity().application as CallSyncApplication).container }

    private val viewModel: CallHistoryViewModel by viewModels {
        CallHistoryViewModel.Factory(container)
    }

    private val adapter = CallHistoryAdapter()
    private lateinit var limitAdapter: ArrayAdapter<String>
    private var hasCompletedFirstResume = false

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
        binding.buttonSelectDateRange.setOnClickListener { showDateRangePicker() }
        binding.buttonClearDateRange.setOnClickListener { viewModel.clearDateRange() }

        binding.editSearchHistory.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                viewModel.setSearchQuery(s?.toString().orEmpty())
            }
        })

        binding.swipeRefresh.setOnRefreshListener { requestAndLoad(forceRefresh = true) }

        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        requestAndLoad(forceRefresh = hasCompletedFirstResume)
        hasCompletedFirstResume = true
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

    private fun showDateRangePicker() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.call_history_select_date_range)
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val startUtc = selection.first ?: return@addOnPositiveButtonClickListener
            val endUtc = selection.second ?: return@addOnPositiveButtonClickListener
            viewModel.setDateRange(
                CallHistorySelectedDateRange(
                    startAtInclusive = utcPickerDayToLocalStartMillis(startUtc),
                    endAtInclusive = utcPickerDayToLocalEndMillis(endUtc)
                )
            )
        }
        picker.show(parentFragmentManager, "call_history_date_range")
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
            binding.textDateRangeSummary.text = state.selectedDateRange?.let {
                getString(
                    R.string.call_history_date_range_value,
                    DISPLAY_DATE.format(Instant.ofEpochMilli(it.startAtInclusive).atZone(ZoneId.systemDefault()).toLocalDate()),
                    DISPLAY_DATE.format(Instant.ofEpochMilli(it.endAtInclusive).atZone(ZoneId.systemDefault()).toLocalDate())
                )
            } ?: getString(R.string.call_history_date_range_any)
            binding.buttonClearDateRange.visibility = if (state.selectedDateRange == null) View.GONE else View.VISIBLE
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

    private fun utcPickerDayToLocalStartMillis(utcMillis: Long): Long {
        val localDate = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
        return localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun utcPickerDayToLocalEndMillis(utcMillis: Long): Long {
        val localDate = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
        return localDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1L
    }

    companion object {
        private val DISPLAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy")
    }
}
