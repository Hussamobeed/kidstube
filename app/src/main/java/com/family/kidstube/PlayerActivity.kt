package com.family.kidstube

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.family.kidstube.databinding.ActivityPlayerBinding

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var titles: Array<String> = emptyArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uris = intent.getStringArrayExtra(EXTRA_VIDEO_URIS) ?: emptyArray()
        titles = intent.getStringArrayExtra(EXTRA_VIDEO_TITLES) ?: emptyArray()
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)

        if (uris.isEmpty()) {
            finish()
            return
        }

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        binding.playerView.player = exo

        uris.forEach { exo.addMediaItem(MediaItem.fromUri(it)) }
        exo.seekTo(startIndex.coerceIn(0, uris.size - 1), 0)
        exo.repeatMode = Player.REPEAT_MODE_OFF
        exo.prepare()
        exo.playWhenReady = true

        updateTitle(startIndex)
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateTitle(exo.currentMediaItemIndex)
            }
        })
    }

    private fun updateTitle(index: Int) {
        binding.videoTitleOverlay.text = titles.getOrNull(index).orEmpty()
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_START_INDEX = "extra_start_index"
        const val EXTRA_VIDEO_URIS = "extra_video_uris"
        const val EXTRA_VIDEO_TITLES = "extra_video_titles"
    }
}
