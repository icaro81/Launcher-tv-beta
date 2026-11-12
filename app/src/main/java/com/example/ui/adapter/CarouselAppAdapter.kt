package com.example.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.model.AppItem

/**
 * High-performance Leanback Carousel adapter for Silvo Horizon favorite apps.
 * Implements smooth 1.15x focus scaling, neon glow highlight, and callbacks.
 */
class CarouselAppAdapter(
    private val onItemClick: (AppItem) -> Unit,
    private val onItemLongClick: (AppItem) -> Boolean,
    private val onItemFocused: (AppItem, View) -> Unit
) : ListAdapter<AppItem, CarouselAppAdapter.ViewHolder>(DiffCallback) {

    private var currentlyFocusedPosition: Int = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_carousel_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIconImageView: ImageView = itemView.findViewById(R.id.appIconImageView)
        val cardBackground: View = itemView.findViewById(R.id.cardBackground)

        init {
            itemView.isFocusable = true
            itemView.isClickable = true

            itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    currentlyFocusedPosition = adapterPosition
                    // Scale up to 1.15x with smooth acceleration
                    view.animate()
                        .scaleX(1.15f)
                        .scaleY(1.15f)
                        .translationZ(12f)
                        .setDuration(160)
                        .start()

                    val position = adapterPosition
                    if (position != RecyclerView.NO_POSITION && position < itemCount) {
                        onItemFocused(getItem(position), view)
                    }
                } else {
                    // Scale back down to 1.0x
                    view.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationZ(0f)
                        .setDuration(140)
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
            if (item.icon != null) {
                appIconImageView.setImageDrawable(item.icon)
            } else {
                appIconImageView.setImageResource(R.drawable.ic_apps_grid)
            }

            itemView.contentDescription = item.label
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<AppItem>() {
        override fun areItemsTheSame(oldItem: AppItem, newItem: AppItem): Boolean {
            return oldItem.uniqueId == newItem.uniqueId
        }

        override fun areContentsTheSame(oldItem: AppItem, newItem: AppItem): Boolean {
            return oldItem.label == newItem.label &&
                    oldItem.isFavorite == newItem.isFavorite &&
                    oldItem.isDrawerShortcut == newItem.isDrawerShortcut
        }
    }
}
