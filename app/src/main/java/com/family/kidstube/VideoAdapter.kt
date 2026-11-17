package com.family.kidstube

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.family.kidstube.databinding.ItemVideoBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VideoAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    private val items = mutableListOf<VideoItem>()
    private val thumbnailExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun submitList(videos: List<VideoItem>) {
        items.clear()
        items.addAll(videos)
        notifyDataSetChanged()
    }

    fun close() {
        thumbnailExecutor.shutdownNow()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VideoViewHolder(
        private val binding: ItemVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(pos)
            }
            // YouTube-like "pop" when the TV remote focuses a card
            binding.root.setOnFocusChangeListener { v, hasFocus ->
                v.animate().scaleX(if (hasFocus) 1.06f else 1.0f)
                    .scaleY(if (hasFocus) 1.06f else 1.0f)
                    .setDuration(120).start()
                binding.cardHighlight.visibility = if (hasFocus) View.VISIBLE else View.GONE
            }
        }

        fun bind(video: VideoItem) {
            binding.videoTitle.text = video.title
            binding.videoDuration.text = video.durationText
            binding.videoThumbnail.contentDescription = video.title
            binding.videoThumbnail.setImageDrawable(null)
            binding.videoThumbnail.tag = video.uri

            // Load a preview frame off the UI thread from this local content URI.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val requestedUri = video.uri
                thumbnailExecutor.execute {
                    val bitmap = runCatching {
                        binding.root.context.contentResolver.loadThumbnail(
                            requestedUri,
                            Size(640, 360),
                            null
                        )
                    }.getOrNull()
                    if (bitmap != null) {
                        mainHandler.post {
                            if (binding.videoThumbnail.tag == requestedUri) {
                                binding.videoThumbnail.setImageBitmap(bitmap)
                            } else {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        }
    }
}
