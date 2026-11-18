package com.family.kidstube

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.family.kidstube.databinding.ItemVideoBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class VideoAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    private val items = mutableListOf<VideoItem>()
    private val thumbnailExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var closed = false

    fun submitList(videos: List<VideoItem>) {
        items.clear()
        items.addAll(videos)
        notifyDataSetChanged()
    }

    fun close() {
        closed = true
        thumbnailExecutor.shutdownNow()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onViewRecycled(holder: VideoViewHolder) {
        holder.clearThumbnail()
        holder.resetFocusVisuals()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    inner class VideoViewHolder(
        private val binding: ItemVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        private var thumbnailJob: Future<*>? = null
        private var thumbnailToken: Any? = null

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(pos)
            }
            binding.root.setOnFocusChangeListener { view, hasFocus ->
                view.animate().cancel()
                view.animate().scaleX(if (hasFocus) 1.035f else 1f)
                    .scaleY(if (hasFocus) 1.035f else 1f)
                    .setDuration(150).start()
                view.translationZ = if (hasFocus) 8f * view.resources.displayMetrics.density else 0f
                binding.cardHighlight.visibility = if (hasFocus) View.VISIBLE else View.GONE
            }
            // Keep artwork 16:9 at every grid width instead of assuming a fixed TV resolution.
            binding.thumbnailFrame.addOnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
                val width = right - left
                val targetHeight = width * 9 / 16
                if (width > 0 && view.layoutParams.height != targetHeight) {
                    view.layoutParams = view.layoutParams.apply { height = targetHeight }
                }
            }
        }

        fun bind(video: VideoItem) {
            clearThumbnail()
            val context = binding.root.context
            val title = video.title.ifBlank { context.getString(R.string.home_untitled_video) }
            binding.videoTitle.text = title
            val hasDuration = video.durationMs > 0
            binding.videoDuration.visibility = if (hasDuration) View.VISIBLE else View.GONE
            binding.videoDuration.text = if (hasDuration) video.durationText else ""
            binding.root.contentDescription = if (hasDuration) {
                context.getString(R.string.home_video_accessibility, title, video.durationText)
            } else {
                title
            }
            val hasFocus = binding.root.hasFocus()
            binding.root.animate().cancel()
            binding.root.scaleX = if (hasFocus) 1.035f else 1f
            binding.root.scaleY = if (hasFocus) 1.035f else 1f
            binding.root.translationZ = if (hasFocus) 8f * context.resources.displayMetrics.density else 0f
            binding.cardHighlight.visibility = if (hasFocus) View.VISIBLE else View.GONE

            if (closed) return
            val token = Any()
            thumbnailToken = token
            val resolver = context.applicationContext.contentResolver
            thumbnailJob = thumbnailExecutor.submit {
                val bitmap: Bitmap? = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        resolver.loadThumbnail(video.uri, Size(640, 360), null)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Video.Thumbnails.getThumbnail(
                            resolver, video.id, MediaStore.Video.Thumbnails.MINI_KIND, null
                        )
                    }
                }.getOrNull()
                if (bitmap != null) {
                    mainHandler.post {
                        if (!closed && thumbnailToken === token) {
                            binding.videoThumbnail.setImageBitmap(bitmap)
                        } else {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }

        fun clearThumbnail() {
            thumbnailToken = null
            thumbnailJob?.cancel(true)
            thumbnailJob = null
            binding.videoThumbnail.setImageDrawable(null)
        }

        fun resetFocusVisuals() {
            binding.root.animate().cancel()
            binding.root.scaleX = 1f
            binding.root.scaleY = 1f
            binding.root.translationZ = 0f
            binding.cardHighlight.visibility = View.GONE
        }
    }
}
