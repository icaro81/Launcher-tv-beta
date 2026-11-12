package com.example.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.model.AppItem

/**
 * Grid adapter for the secondary All Apps drawer in Silvo Horizon.
 */
class AllAppsGridAdapter(
    private val onItemClick: (AppItem) -> Unit,
    private val onItemLongClick: (AppItem) -> Boolean
) : ListAdapter<AppItem, AllAppsGridAdapter.ViewHolder>(DiffCallback) {

    private var originalList: List<AppItem> = emptyList()

    fun submitOriginalList(list: List<AppItem>) {
        originalList = list
        submitList(list)
    }

    fun filter(query: String) {
        if (query.isBlank()) {
            submitList(originalList)
        } else {
            val filtered = originalList.filter {
                it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
            submitList(filtered)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_grid_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val appIconImageView: ImageView = itemView.findViewById(R.id.appIconImageView)
        private val appNameTextView: TextView = itemView.findViewById(R.id.appNameTextView)
        private val favoriteIndicator: ImageView = itemView.findViewById(R.id.favoriteIndicator)

        init {
            itemView.isFocusable = true
            itemView.isClickable = true

            itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate()
                        .scaleX(1.08f)
                        .scaleY(1.08f)
                        .translationZ(8f)
                        .setDuration(140)
                        .start()
                } else {
                    view.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationZ(0f)
                        .setDuration(120)
                        .start()
                }
            }

            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION && position < itemCount) {
                    onItemClick(getItem(position))
                }
            }

            itemView.setOnLongClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION && position < itemCount) {
                    onItemLongClick(getItem(position))
                } else {
                    false
                }
            }
        }

        fun bind(item: AppItem) {
            appNameTextView.text = item.label
            if (item.icon != null) {
                appIconImageView.setImageDrawable(item.icon)
            } else {
                appIconImageView.setImageResource(R.drawable.ic_apps_grid)
            }

            favoriteIndicator.visibility = if (item.isFavorite) View.VISIBLE else View.GONE
            itemView.contentDescription = item.label
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<AppItem>() {
        override fun areItemsTheSame(oldItem: AppItem, newItem: AppItem): Boolean {
            return oldItem.uniqueId == newItem.uniqueId
        }

        override fun areContentsTheSame(oldItem: AppItem, newItem: AppItem): Boolean {
            return oldItem.label == newItem.label &&
                    oldItem.isFavorite == newItem.isFavorite
        }
    }
}
