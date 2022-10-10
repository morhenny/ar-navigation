package de.morhenn.ar_navigation.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import de.morhenn.ar_navigation.R
import de.morhenn.ar_navigation.databinding.ItemArNodesListBinding
import de.morhenn.ar_navigation.fragments.AugmentedRealityFragment
import de.morhenn.ar_navigation.model.ArPoint
import de.morhenn.ar_navigation.util.Utils

class MyListAdapter(private val list: List<ArPoint>) : RecyclerView.Adapter<MyListAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemArNodesListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(list[position], position)
    }

    override fun getItemCount(): Int {
        return list.size
    }

    class ViewHolder(private val binding: ItemArNodesListBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(node: ArPoint, position: Int) {
            when (node.modelName) {
                AugmentedRealityFragment.ModelName.ARROW_LEFT -> {
                    binding.nodeListItemIcon.setImageResource(R.drawable.ic_baseline_arrow_back_24)
                }
                AugmentedRealityFragment.ModelName.ARROW_FORWARD -> {
                    binding.nodeListItemIcon.setImageResource(R.drawable.ic_baseline_arrow_upward_24)
                }
                AugmentedRealityFragment.ModelName.ARROW_RIGHT -> {
                    binding.nodeListItemIcon.setImageResource(R.drawable.ic_baseline_arrow_forward_24)
                }
                AugmentedRealityFragment.ModelName.TARGET -> {
                    binding.nodeListItemIcon.setImageResource(R.drawable.ic_baseline_emoji_flags_24)
                }
                else -> {
                    binding.nodeListItemIcon.setImageResource(R.drawable.ic_baseline_error_outline_24)
                }
            }
            binding.nodeListItemIcon.setOnClickListener {
                Utils.toast("Node #$position was clicked")
            }
        }
    }

}