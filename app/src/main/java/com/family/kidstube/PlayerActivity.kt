package com.family.kidstube

import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.family.kidstube.databinding.ActivityPlayerBinding
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future

class PlayerActivity : AppCompatActivity() {
    private data class LocalVideo(val uri: Uri, val title: String, val originalIndex: Int)
    private enum class Screen { PLAYING, ENDED, ERROR }

    private lateinit var binding: ActivityPlayerBinding
    private var videos: List<LocalVideo> = emptyList()
    private var currentIndex = 0
    private var currentPositionMs = 0L
    private var resumePlaying = true
    private var repeatOne = false
    private var autoplay = true
    private var screen = Screen.PLAYING
    private var foreground = false
    private var controlsVisible = true
    private var wakeKeyCode: Int? = null
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val thumbnailExecutor = Executors.newFixedThreadPool(2)
    private var thumbnailsClosed = false
    private lateinit var suggestionsAdapter: SuggestionsAdapter
    private var countdownDeadline = 0L
    private var countdownActive = false

    private val hideControls = Runnable { hideControls() }
    private val updateProgress = object : Runnable {
        override fun run() {
            if (!foreground || screen != Screen.PLAYING) return
            refreshProgress()
            handler.postDelayed(this, 500L)
        }
    }
    private val countdownTick = object : Runnable {
        override fun run() {
            if (!countdownActive || !foreground || screen != Screen.ENDED) return
            val remaining = countdownDeadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                cancelCountdown()
                if (autoplay && currentIndex + 1 < videos.size) playVideo(currentIndex + 1)
                return
            }
            val seconds = ((remaining + 999L) / 1000L).toInt()
            binding.endMessage.text = resources.getQuantityString(R.plurals.player_countdown, seconds, seconds)
            handler.postDelayed(this, minOf(1000L, remaining))
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            refreshProgress()
            if (playbackState == Player.STATE_ENDED && screen == Screen.PLAYING) {
                if (repeatOne) {
                    // REPEAT_MODE_ONE normally avoids STATE_ENDED; keep the same guarantee
                    // even if a source reports an end while being re-prepared.
                    player?.seekTo(0L)
                    if (foreground && resumePlaying) player?.play()
                } else {
                    resumePlaying = false
                    screen = Screen.ENDED
                    showEndScreen(startCountdown = foreground)
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlaybackButtons()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updatePlaybackButtons()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (screen != Screen.PLAYING) return
            resumePlaying = false
            screen = Screen.ERROR
            cancelCountdown()
            showEndScreen(startCountdown = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val rawUris = intent.getStringArrayExtra(EXTRA_VIDEO_URIS) ?: emptyArray()
        val titles = intent.getStringArrayExtra(EXTRA_VIDEO_TITLES) ?: emptyArray()
        // Never hand a network URI to Media3 or to the thumbnail loader. Keep the
        // original index so filtering a bad entry does not misalign the caller's titles.
        videos = rawUris.mapIndexedNotNull { index, value ->
            val uri = localUri(value) ?: return@mapIndexedNotNull null
            val title = titles.getOrNull(index)?.takeIf { it.isNotBlank() }
                ?: getString(R.string.player_untitled)
            LocalVideo(uri, title, index)
        }
        if (videos.isEmpty()) {
            finish()
            return
        }
        val requestedIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, rawUris.lastIndex)
        currentIndex = videos.indexOfFirst { it.originalIndex >= requestedIndex }
            .let { if (it < 0) videos.lastIndex else it }
        if (savedInstanceState != null) {
            currentIndex = savedInstanceState.getInt(STATE_INDEX, currentIndex).coerceIn(videos.indices)
            currentPositionMs = savedInstanceState.getLong(STATE_POSITION, 0L).coerceAtLeast(0L)
            resumePlaying = savedInstanceState.getBoolean(STATE_RESUME, true)
            repeatOne = savedInstanceState.getBoolean(STATE_REPEAT, false)
            screen = Screen.values().getOrNull(savedInstanceState.getInt(STATE_SCREEN, 0)) ?: Screen.PLAYING
        }
        autoplay = getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean(PREF_AUTOPLAY, true)

        suggestionsAdapter = SuggestionsAdapter()
        binding.suggestionsList.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.suggestionsList.adapter = suggestionsAdapter
        setupControls()
        updatePlaybackButtons()
        if (screen == Screen.PLAYING) {
            showControls(requestFocus = false)
        } else {
            // A countdown never survives leaving or recreating the activity.
            showEndScreen(startCountdown = false)
        }
    }

    private fun localUri(value: String): Uri? {
        if (value.isBlank()) return null
        return runCatching {
            val uri = Uri.parse(value)
            when {
                uri.scheme.equals("content", ignoreCase = true) -> uri
                uri.scheme.equals("file", ignoreCase = true) -> uri
                uri.scheme == null && value.startsWith("/") -> Uri.fromFile(File(value))
                else -> null
            }
        }.getOrNull()
    }

    private fun setupControls() {
        binding.previousButton.contentDescription = getString(R.string.player_previous_accessibility)
        binding.nextButton.contentDescription = getString(R.string.player_next_accessibility)
        binding.previousButton.setOnClickListener { playPrevious() }
        binding.nextButton.setOnClickListener { playNext() }
        binding.playPauseButton.setOnClickListener { togglePlayback() }
        binding.repeatButton.setOnClickListener {
            repeatOne = !repeatOne
            player?.repeatMode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            updatePlaybackButtons()
            scheduleControlsHide()
        }
        binding.autoplayButton.setOnClickListener { toggleAutoplay() }
        binding.endAutoplayButton.setOnClickListener { toggleAutoplay() }
        binding.replayButton.setOnClickListener { playVideo(currentIndex) }
        binding.cancelAutoplayButton.setOnClickListener {
            binding.replayButton.requestFocus()
            cancelCountdown()
            updateEndMessage()
        }
        binding.playerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val exo = player ?: return
                if (fromUser && exo.duration > 0L) {
                    exo.seekTo(exo.duration * progress / seekBar.max)
                    refreshProgress()
                    scheduleControlsHide()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { handler.removeCallbacks(hideControls) }
            override fun onStopTrackingTouch(seekBar: SeekBar) { scheduleControlsHide() }
        })
    }

    override fun onStart() {
        super.onStart()
        if (videos.isEmpty()) return
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        binding.playerView.player = exo
        exo.addListener(playerListener)
        exo.repeatMode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        exo.setMediaItem(MediaItem.fromUri(videos[currentIndex].uri))
        if (screen == Screen.PLAYING) {
            exo.seekTo(currentPositionMs)
            exo.prepare()
        }
    }

    override fun onResume() {
        super.onResume()
        if (videos.isEmpty()) return
        foreground = true
        if (screen == Screen.PLAYING) {
            player?.playWhenReady = resumePlaying
            handler.removeCallbacks(updateProgress)
            handler.post(updateProgress)
            showControls(requestFocus = true)
            binding.playPauseButton.post { binding.playPauseButton.requestFocus() }
        }
    }

    override fun onPause() {
        foreground = false
        if (screen == Screen.PLAYING) {
            player?.let {
                currentPositionMs = it.currentPosition.coerceAtLeast(0L)
                resumePlaying = it.playWhenReady
                it.pause()
            }
        }
        val wasCountingDown = countdownActive
        cancelCountdown()
        if (wasCountingDown && screen == Screen.ENDED) updateEndMessage()
        handler.removeCallbacks(updateProgress)
        handler.removeCallbacks(hideControls)
        super.onPause()
    }

    override fun onStop() {
        player?.let {
            if (screen == Screen.PLAYING) currentPositionMs = it.currentPosition.coerceAtLeast(0L)
            it.removeListener(playerListener)
            binding.playerView.player = null
            it.release()
        }
        player = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (screen == Screen.PLAYING) {
            player?.let {
                currentPositionMs = it.currentPosition.coerceAtLeast(0L)
                if (foreground) resumePlaying = it.playWhenReady
            }
        }
        outState.putInt(STATE_INDEX, currentIndex)
        outState.putLong(STATE_POSITION, currentPositionMs)
        outState.putBoolean(STATE_RESUME, resumePlaying)
        outState.putBoolean(STATE_REPEAT, repeatOne)
        outState.putInt(STATE_SCREEN, screen.ordinal)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        cancelCountdown()
        handler.removeCallbacksAndMessages(null)
        thumbnailsClosed = true
        thumbnailExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun playVideo(index: Int) {
        if (index !in videos.indices) return
        cancelCountdown()
        currentIndex = index
        currentPositionMs = 0L
        resumePlaying = true
        screen = Screen.PLAYING
        binding.endScreen.visibility = View.GONE
        player?.let {
            it.repeatMode = if (repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            it.setMediaItem(MediaItem.fromUri(videos[index].uri))
            it.prepare()
            it.playWhenReady = foreground
        }
        updatePlaybackButtons()
        handler.removeCallbacks(updateProgress)
        if (foreground) handler.post(updateProgress)
        showControls(requestFocus = true)
    }

    private fun playNext() {
        if (currentIndex + 1 < videos.size) playVideo(currentIndex + 1)
    }

    private fun playPrevious() {
        val position = player?.currentPosition ?: currentPositionMs
        if (screen == Screen.PLAYING && position > 3000L) {
            player?.seekTo(0L)
            showControls(requestFocus = false)
        } else if (currentIndex > 0) {
            playVideo(currentIndex - 1)
        } else {
            playVideo(currentIndex)
        }
    }

    private fun togglePlayback() {
        val exo = player ?: return
        if (exo.playWhenReady) exo.pause() else exo.play()
        resumePlaying = exo.playWhenReady
        updatePlaybackButtons()
        scheduleControlsHide()
    }

    private fun toggleAutoplay() {
        autoplay = !autoplay
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(PREF_AUTOPLAY, autoplay).apply()
        updatePlaybackButtons()
        if (screen == Screen.ENDED) {
            cancelCountdown()
            if (autoplay && currentIndex + 1 < videos.size && foreground) startCountdown()
            else updateEndMessage()
        } else {
            scheduleControlsHide()
        }
    }

    private fun updatePlaybackButtons() {
        if (videos.isEmpty()) return
        binding.videoTitleOverlay.text = videos[currentIndex].title
        val playing = player?.playWhenReady ?: resumePlaying
        binding.playPauseButton.setText(if (playing) R.string.player_pause else R.string.player_play)
        binding.nextButton.isEnabled = currentIndex + 1 < videos.size
        binding.nextButton.isFocusable = binding.nextButton.isEnabled
        binding.nextButton.alpha = if (binding.nextButton.isEnabled) 1f else 0.5f
        binding.repeatButton.setText(if (repeatOne) R.string.player_repeat_on else R.string.player_repeat_off)
        binding.repeatButton.contentDescription = getString(
            if (repeatOne) R.string.player_repeat_on_accessibility else R.string.player_repeat_off_accessibility
        )
        val autoText = if (autoplay) R.string.player_autoplay_on else R.string.player_autoplay_off
        val autoDescription = if (autoplay) R.string.player_autoplay_on_accessibility else R.string.player_autoplay_off_accessibility
        binding.autoplayButton.setText(autoText)
        binding.endAutoplayButton.setText(autoText)
        binding.autoplayButton.contentDescription = getString(autoDescription)
        binding.endAutoplayButton.contentDescription = getString(autoDescription)
    }

    private fun refreshProgress() {
        val exo = player
        val position = (exo?.currentPosition ?: currentPositionMs).coerceAtLeast(0L)
        val duration = exo?.duration ?: 0L
        binding.elapsedTime.text = DateUtils.formatElapsedTime(position / 1000L)
        binding.durationTime.text = DateUtils.formatElapsedTime(if (duration > 0L) duration / 1000L else 0L)
        binding.playerSeekBar.isEnabled = duration > 0L
        binding.playerSeekBar.progress = if (duration > 0L) {
            ((position.coerceAtMost(duration) * binding.playerSeekBar.max) / duration).toInt()
        } else 0
    }

    private fun showControls(requestFocus: Boolean) {
        if (screen != Screen.PLAYING) return
        controlsVisible = true
        binding.playerHeader.visibility = View.VISIBLE
        binding.playerControls.visibility = View.VISIBLE
        if (requestFocus) binding.playPauseButton.requestFocus()
        scheduleControlsHide()
    }

    private fun hideControls() {
        if (screen != Screen.PLAYING) return
        controlsVisible = false
        binding.playerHeader.visibility = View.GONE
        binding.playerControls.visibility = View.GONE
        binding.playerRoot.requestFocus()
    }

    private fun scheduleControlsHide() {
        handler.removeCallbacks(hideControls)
        if (foreground && screen == Screen.PLAYING && controlsVisible) {
            handler.postDelayed(hideControls, CONTROLS_HIDE_MS)
        }
    }

    private fun showEndScreen(startCountdown: Boolean) {
        handler.removeCallbacks(hideControls)
        handler.removeCallbacks(updateProgress)
        binding.playerHeader.visibility = View.GONE
        binding.playerControls.visibility = View.GONE
        controlsVisible = false
        binding.endScreen.visibility = View.VISIBLE
        val isError = screen == Screen.ERROR
        binding.endHeading.setText(if (isError) R.string.player_error_title else R.string.player_finished)
        binding.replayButton.setText(if (isError) R.string.player_retry else R.string.player_replay)
        binding.endAutoplayButton.visibility = if (isError) View.GONE else View.VISIBLE
        val suggestions = ((currentIndex + 1) until videos.size).toList() + (0 until currentIndex).toList()
        suggestionsAdapter.submit(suggestions)
        binding.suggestionsHeading.visibility = if (suggestions.isEmpty()) View.GONE else View.VISIBLE
        binding.suggestionsList.visibility = if (suggestions.isEmpty()) View.GONE else View.VISIBLE
        binding.suggestionsHeading.setText(
            if (isError) R.string.player_other_videos
            else if (currentIndex + 1 < videos.size) R.string.player_up_next
            else R.string.player_more_videos
        )
        updatePlaybackButtons()
        if (startCountdown && !isError && autoplay && currentIndex + 1 < videos.size) {
            startCountdown()
        } else {
            cancelCountdown()
            updateEndMessage()
        }
        binding.suggestionsList.post {
            if (screen == Screen.PLAYING || isFinishing || isDestroyed) return@post
            val firstCard = if (suggestions.isNotEmpty()) {
                binding.suggestionsList.findViewHolderForAdapterPosition(0)?.itemView
            } else null
            (firstCard ?: binding.replayButton).requestFocus()
        }
    }

    private fun startCountdown() {
        cancelCountdown()
        countdownActive = true
        countdownDeadline = SystemClock.elapsedRealtime() + AUTOPLAY_COUNTDOWN_MS
        binding.cancelAutoplayButton.visibility = View.VISIBLE
        handler.post(countdownTick)
    }

    private fun cancelCountdown() {
        countdownActive = false
        handler.removeCallbacks(countdownTick)
        if (::binding.isInitialized) {
            if (binding.cancelAutoplayButton.hasFocus()) binding.replayButton.requestFocus()
            binding.cancelAutoplayButton.visibility = View.GONE
        }
    }

    private fun updateEndMessage() {
        binding.endMessage.setText(
            when {
                screen == Screen.ERROR -> R.string.player_error_message
                videos.size == 1 -> R.string.player_only_video
                currentIndex + 1 >= videos.size -> R.string.player_end_of_list
                !autoplay -> R.string.player_autoplay_disabled
                else -> R.string.player_autoplay_canceled
            }
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == wakeKeyCode) {
            wakeKeyCode = null
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && videos.isNotEmpty()) {
            if (event.keyCode == wakeKeyCode) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> { if (event.repeatCount == 0) playNext(); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (event.repeatCount == 0) playPrevious(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (event.repeatCount == 0) {
                        if (screen == Screen.PLAYING) togglePlayback() else playVideo(currentIndex)
                    }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    if (screen == Screen.PLAYING) { player?.play(); resumePlaying = true }
                    else playVideo(currentIndex)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (screen == Screen.PLAYING) { player?.pause(); resumePlaying = false }
                    return true
                }
            }
            if (screen == Screen.PLAYING && event.keyCode in REMOTE_KEYS) {
                if (!controlsVisible) {
                    wakeKeyCode = event.keyCode
                    showControls(requestFocus = true)
                    return true
                }
                scheduleControlsHide()
            }
            if (screen == Screen.PLAYING && controlsVisible &&
                (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                window.currentFocus?.performClick()
                scheduleControlsHide()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (screen == Screen.PLAYING && controlsVisible) {
            handler.removeCallbacks(hideControls)
            hideControls()
        } else {
            cancelCountdown()
            super.onBackPressed()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private inner class SuggestionsAdapter : RecyclerView.Adapter<SuggestionsAdapter.CardHolder>() {
        private var indices: List<Int> = emptyList()

        fun submit(newIndices: List<Int>) {
            indices = newIndices
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = indices.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
            val card = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                background = getDrawable(R.drawable.player_card_background)
                isFocusable = true
                isClickable = true
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams = RecyclerView.LayoutParams(dp(208), dp(156)).apply { marginEnd = dp(12) }
            }
            val frame = FrameLayout(parent.context).apply {
                setBackgroundColor(Color.rgb(57, 57, 57))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112))
            }
            val image = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val placeholder = ImageView(parent.context).apply {
                setImageResource(R.drawable.player_thumbnail_placeholder)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(placeholder, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
            val title = TextView(parent.context).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(5), dp(4), dp(5), 0)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            card.addView(frame)
            card.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return CardHolder(card, image, placeholder, title)
        }

        override fun onBindViewHolder(holder: CardHolder, position: Int) {
            holder.bind(indices[position])
        }

        override fun onViewRecycled(holder: CardHolder) {
            holder.clear()
            super.onViewRecycled(holder)
        }

        inner class CardHolder(
            card: View,
            private val image: ImageView,
            private val placeholder: ImageView,
            private val title: TextView
        ) : RecyclerView.ViewHolder(card) {
            private var job: Future<*>? = null
            private var token: Any? = null

            fun bind(index: Int) {
                clear()
                val video = videos[index]
                title.text = video.title
                itemView.contentDescription = getString(R.string.player_play_video_accessibility, video.title)
                itemView.setOnClickListener { playVideo(index) }
                if (thumbnailsClosed) return
                val request = Any()
                token = request
                val appContext = applicationContext
                job = thumbnailExecutor.submit {
                    val bitmap = runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && video.uri.scheme == "content") {
                            runCatching { appContext.contentResolver.loadThumbnail(video.uri, Size(400, 225), null) }
                                .getOrNull()
                        } else null
                    }.getOrNull() ?: runCatching {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(appContext, video.uri)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                                retriever.getScaledFrameAtTime(
                                    1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 400, 225
                                )
                            } else {
                                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            }
                        } finally {
                            retriever.release()
                        }
                    }.getOrNull()
                    if (bitmap != null) handler.post {
                        if (!thumbnailsClosed && token === request && !isDestroyed) {
                            image.setImageBitmap(bitmap)
                            placeholder.visibility = View.GONE
                        }
                    }
                }
            }

            fun clear() {
                token = null
                job?.cancel(true)
                job = null
                image.setImageDrawable(null)
                placeholder.visibility = View.VISIBLE
            }
        }
    }

    companion object {
        const val EXTRA_START_INDEX = "extra_start_index"
        const val EXTRA_VIDEO_URIS = "extra_video_uris"
        const val EXTRA_VIDEO_TITLES = "extra_video_titles"
        private const val PREFERENCES = "player_preferences"
        private const val PREF_AUTOPLAY = "autoplay_next"
        private const val STATE_INDEX = "player_index"
        private const val STATE_POSITION = "player_position"
        private const val STATE_RESUME = "player_resume"
        private const val STATE_REPEAT = "player_repeat_one"
        private const val STATE_SCREEN = "player_screen"
        private const val AUTOPLAY_COUNTDOWN_MS = 10_000L
        private const val CONTROLS_HIDE_MS = 7_000L
        private val REMOTE_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER
        )
    }
}
