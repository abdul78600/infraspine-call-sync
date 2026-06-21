package com.infraspine.callsync.ui.synchistory

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.infraspine.callsync.CallSyncApplication
import com.infraspine.callsync.data.local.entity.SyncHistoryEntity
import com.infraspine.callsync.databinding.FragmentSyncHistoryBinding
import com.infraspine.callsync.databinding.ItemSyncHistoryBinding
import com.infraspine.callsync.ui.common.toDisplayDateTime

class SyncHistoryFragment : Fragment() {

    private var _binding: FragmentSyncHistoryBinding? = null
    private val binding get() = _binding!!

    private val container by lazy { (requireActivity().application as CallSyncApplication).container }

    private val viewModel: SyncHistoryViewModel by viewModels {
        SyncHistoryViewModel.Factory(container)
    }

    private val adapter = SyncHistoryAdapter()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSyncHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recyclerSyncHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerSyncHistory.adapter = adapter

        viewModel.history.observe(viewLifecycleOwner) { items ->
            adapter.submitList(items)
            binding.recyclerSyncHistory.isVisible = items.isNotEmpty()
            binding.textEmptyState.isVisible = items.isEmpty()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class SyncHistoryAdapter :
    ListAdapter<SyncHistoryEntity, SyncHistoryAdapter.VH>(DIFF) {

    inner class VH(private val b: ItemSyncHistoryBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: SyncHistoryEntity) {
            val dt = item.syncedAt.toDisplayDateTime()
            val parts = dt.split(" ", limit = 2)
            b.textSyncDate.text = parts.getOrElse(0) { dt }
            b.textSyncTime.text = parts.getOrElse(1) { "" }
            b.textRecordingsUploaded.text = item.recordingsUploaded.toString()
            b.textRecordingsFailed.text = item.recordingsFailed.toString()
            b.textCallLogsUploaded.text = item.callLogsUploaded.toString()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemSyncHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<SyncHistoryEntity>() {
            override fun areItemsTheSame(a: SyncHistoryEntity, b: SyncHistoryEntity) = a.id == b.id
            override fun areContentsTheSame(a: SyncHistoryEntity, b: SyncHistoryEntity) = a == b
        }
    }
}
