package com.example.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import com.example.data.model.AppItem

/**
 * Infinite-loop centered Leanback Carousel adapter for Silvo Horizon favorite apps.
 * Keeps the selected item dead-center while scrolling infinitely left or right.
 */
class CarouselAppAdapter(
    private val onItemClick: (AppItem, Int) -> Unit,
    private val onItemLongClick: (AppItem) -> Boolean,
    private val onItemFocused: (AppItem, Int, View) -> Unit
) : RecyclerView.Adapter<CarouselAppAdapter.ViewHolder>() {

    private var realItems: List<AppItem> = emptyList()

    companion object {
        const val VIRTUAL_LOOP_COUNT = 100_000
    }

    init {
        setHasStableIds(true)
    }

    /**
     * Updates the underlying items. Returns true if the dataset structure changed.
     */
    fun submitItems(newItems: List<AppItem>): Boolean {
        val changed = realItems.size != newItems.size ||
            realItems.zip(newItems).any { (old, new) ->
                old.uniqueId != new.uniqueId ||
                    old.label != new.label ||
                    old.isFavorite != new.isFavorite
            }
        if (!changed) return false
        realItems = newItems
        notifyDataSetChanged()
        return true
    }

    fun getRealItemCount(): Int = realItems.size

    fun getRealItem(virtualPosition: Int): AppItem? {
        if (realItems.isEmpty() || virtualPosition < 0) return null
        return realItems[virtualPosition % realItems.size]
    }

    /**
     * Returns a starting virtual position right in the middle of the infinite range,
     * aligned to real item index 0 (or matching targetUniqueId if provided).
     */
    fun getCenterStartPosition(targetUniqueId: String? = null): Int {
        val count = realItems.size
        if (count == 0) return 0
        val mid = VIRTUAL_LOOP_COUNT / 2
        val baseAligned = mid - (mid % count)
        if (targetUniqueId != null) {
            val targetIndex = realItems.indexOfFirst { it.uniqueId == targetUniqueId }
            if (targetIndex >= 0) {
                return baseAligned + targetIndex
            }
        }
        return baseAligned
    }

    override fun getItemCount(): Int {
        return if (realItems.isEmpty()) 0 else VIRTUAL_LOOP_COUNT
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_carousel_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getRealItem(position) ?: return
        holder.bind(item)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIconImageView: ImageView = itemView.findViewById(R.id.appIconImageView)
        val cardBackground: View = itemView.findViewById(R.id.cardBackground)

        init {
            itemView.isFocusable = true
            itemView.isClickable = true
            itemView.scaleX = 0.92f
            itemView.scaleY = 0.92f
            itemView.alpha = 0.78f

            itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate()
                        .scaleX(1.15f)
                        .scaleY(1.15f)
                        .alpha(1.0f)
                        .translationZ(14f)
                        .setDuration(170)
                        .start()

                    val position = adapterPosition
                    val item = getRealItem(position)
                    if (position != RecyclerView.NO_POSITION && item != null) {
                        onItemFocused(item, position, view)
                    }
                } else {
                    view.animate()
                        .scaleX(0.92f)
                        .scaleY(0.92f)
                        .alpha(0.78f)
                        .translationZ(0f)
                        .setDuration(150)
                        .start()
                }
            }

            itemView.setOnClickListener {
                val position = adapterPosition
                val item = getRealItem(position)
                if (position != RecyclerView.NO_POSITION && item != null) {
                    onItemClick(item, position)
                }
            }

            itemView.setOnLongClickListener {
                val position = adapterPosition
                val item = getRealItem(position)
                if (position != RecyclerView.NO_POSITION && item != null) {
                    onItemLongClick(item)
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

            if (itemView.hasFocus()) {
                itemView.scaleX = 1.15f
                itemView.scaleY = 1.15f
                itemView.alpha = 1.0f
                itemView.translationZ = 14f
            } else {
                itemView.scaleX = 0.92f
                itemView.scaleY = 0.92f
                itemView.alpha = 0.78f
                itemView.translationZ = 0f
            }

            itemView.contentDescription = item.label
        }
    }
}
