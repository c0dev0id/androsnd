package com.androsnd

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.androsnd.model.PlaylistFolder
import com.androsnd.model.Song

class PlaylistAdapter(
    private val onFolderClick: (Int) -> Unit,
    private val onSongClick: (Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_FOLDER = 0
        const val TYPE_SONG = 1
    }

    private data class ListItem(
        val type: Int,
        val folderIndex: Int = -1,
        val songIndex: Int = -1,
        val displayName: String = ""
    )

    private val items = mutableListOf<ListItem>()
    private var currentSongIndex = -1

    fun submitData(folders: List<PlaylistFolder>, songs: List<Song>, currentIdx: Int) {
        val newItems = mutableListOf<ListItem>()
        for ((fi, folder) in folders.withIndex()) {
            newItems.add(ListItem(TYPE_FOLDER, folderIndex = fi, displayName = folder.name))
            for (si in folder.songs) {
                newItems.add(ListItem(TYPE_SONG, songIndex = si, displayName = songs[si].displayName))
            }
        }
        val oldItems = items.toList()
        val oldSongIndex = currentSongIndex
        currentSongIndex = currentIdx
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                val old = oldItems[oldPos]
                val new = newItems[newPos]
                return old.type == new.type &&
                    old.folderIndex == new.folderIndex &&
                    old.songIndex == new.songIndex
            }
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val old = oldItems[oldPos]
                val new = newItems[newPos]
                val selectedChanged = old.type == TYPE_SONG &&
                    (old.songIndex == oldSongIndex) != (new.songIndex == currentSongIndex)
                return old == new && !selectedChanged
            }
        })
        items.clear()
        items.addAll(newItems)
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int) = items[position].type
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_FOLDER) {
            val view = inflater.inflate(R.layout.item_playlist_folder, parent, false)
            FolderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_playlist_song, parent, false)
            SongViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is FolderViewHolder -> {
                holder.name.text = item.displayName
                holder.itemView.setOnClickListener { onFolderClick(item.folderIndex) }
            }
            is SongViewHolder -> {
                holder.name.text = item.displayName
                holder.itemView.isSelected = item.songIndex == currentSongIndex
                holder.itemView.setOnClickListener { onSongClick(item.songIndex) }
            }
        }
    }

    class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.folder_name)
    }

    class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.song_name)
    }
}
