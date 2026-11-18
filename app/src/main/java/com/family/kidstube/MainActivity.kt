package com.family.kidstube

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.family.kidstube.databinding.ActivityMainBinding
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: VideoAdapter
    private lateinit var updateManager: AppUpdateManager
    private val mediaExecutor = Executors.newSingleThreadExecutor()
    private var mediaJob: Future<*>? = null
    private var loadGeneration = 0
    private var loading = false
    private var permissionWasDenied = false
    private var focusContentWhenReady = true
    private var videos: List<VideoItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissionWasDenied = savedInstanceState?.getBoolean(STATE_PERMISSION_DENIED) ?: false
        adapter = VideoAdapter { position -> openPlayer(position) }
        updateManager = AppUpdateManager(this)
        val widthDp = resources.configuration.screenWidthDp
        val columns = when {
            widthDp >= 840 -> 4
            widthDp >= 600 -> 3
            else -> 2
        }
        binding.videoGrid.layoutManager = GridLayoutManager(this, columns)
        binding.videoGrid.adapter = adapter

        binding.checkUpdatesButton.setOnClickListener { updateManager.checkForUpdates() }
        binding.refreshButton.setOnClickListener { checkPermissionAndLoad() }
        binding.stateActionButton.setOnClickListener {
            focusContentWhenReady = true
            checkPermissionAndLoad()
        }

        if (hasPermission()) {
            loadVideos()
        } else {
            showPermissionState()
            if (savedInstanceState == null && !permissionWasDenied) requestVideoPermission()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in setOf(
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER
            )) {
            // Do not move focus away from someone navigating while a query is running.
            focusContentWhenReady = false
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        if (::updateManager.isInitialized) updateManager.onResume()
        // Videos or permissions may have changed while the app was away.
        if (hasPermission()) {
            if (!loading) loadVideos()
        } else {
            showPermissionState()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PERMISSION_DENIED, permissionWasDenied)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        invalidateLoads()
        mediaExecutor.shutdownNow()
        if (::adapter.isInitialized) adapter.close()
        if (::updateManager.isInitialized) updateManager.close()
        super.onDestroy()
    }

    private fun videoPermission(): String = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    private fun hasPermission(): Boolean =
        checkSelfPermission(videoPermission()) == PackageManager.PERMISSION_GRANTED

    private fun checkPermissionAndLoad() {
        if (hasPermission()) {
            loadVideos()
        } else if (permissionWasDenied && !shouldShowRequestPermissionRationale(videoPermission())) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            })
        } else {
            requestVideoPermission()
        }
    }

    private fun requestVideoPermission() {
        requestPermissions(arrayOf(videoPermission()), REQ_PERMISSION)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                permissionWasDenied = false
                loadVideos()
            } else {
                permissionWasDenied = true
                showPermissionState()
                focusStateActionIfNeeded()
            }
        }
    }

    private fun invalidateLoads() {
        loadGeneration++
        mediaJob?.cancel(true)
        mediaJob = null
        loading = false
    }

    private fun loadVideos() {
        // Only the newest request may update the UI, even if an older query ignores interruption.
        invalidateLoads()
        val generation = loadGeneration
        loading = true
        binding.libraryStatus.setText(if (videos.isEmpty()) R.string.home_loading_status else R.string.home_refreshing_status)
        if (videos.isEmpty()) {
            showState(R.string.home_loading_title, R.string.home_loading_body, null, true)
        } else {
            binding.stateContainer.visibility = View.GONE
        }
        val context = applicationContext
        mediaJob = mediaExecutor.submit {
            val result = runCatching { VideoStore.loadVideos(context) }
            runOnUiThread {
                if (generation != loadGeneration || isFinishing || isDestroyed) return@runOnUiThread
                loading = false
                mediaJob = null
                if (!hasPermission()) {
                    showPermissionState()
                } else {
                    result.fold(
                        onSuccess = { showVideos(it) },
                        onFailure = { showLoadError() }
                    )
                }
            }
        }
    }

    private fun showVideos(list: List<VideoItem>) {
        if (list.isEmpty() && binding.videoGrid.hasFocus()) focusContentWhenReady = true
        videos = list
        adapter.submitList(list)
        if (list.isEmpty()) {
            binding.libraryStatus.setText(R.string.home_empty_status)
            showState(R.string.home_empty_title, R.string.no_videos_hint, R.string.refresh_videos)
            focusStateActionIfNeeded()
        } else {
            binding.libraryStatus.text = resources.getQuantityString(
                R.plurals.home_video_count, list.size, list.size
            )
            binding.stateContainer.visibility = View.GONE
            binding.videoGrid.post {
                if (focusContentWhenReady && videos.isNotEmpty()) {
                    val firstCard = binding.videoGrid.layoutManager?.findViewByPosition(0)
                    if (firstCard != null) firstCard.requestFocus() else binding.videoGrid.requestFocus()
                    focusContentWhenReady = false
                }
            }
        }
    }

    private fun showLoadError() {
        if (videos.isEmpty()) {
            binding.libraryStatus.setText(R.string.home_error_status)
            showState(R.string.home_error_title, R.string.home_error_body, R.string.home_retry_action)
            focusStateActionIfNeeded()
        } else {
            // Keep the last successful library usable if a refresh fails.
            binding.libraryStatus.setText(R.string.home_refresh_failed)
            binding.stateContainer.visibility = View.GONE
        }
    }

    private fun showPermissionState() {
        invalidateLoads()
        val hadGridFocus = binding.videoGrid.hasFocus()
        if (hadGridFocus) focusContentWhenReady = true
        if (videos.isNotEmpty()) {
            videos = emptyList()
            adapter.submitList(videos)
        }
        binding.libraryStatus.setText(R.string.home_permission_status)
        val needsSettings = permissionWasDenied &&
            !shouldShowRequestPermissionRationale(videoPermission())
        showState(
            R.string.home_permission_title,
            R.string.permission_needed,
            if (needsSettings) R.string.home_permission_settings_action else R.string.home_permission_action
        )
        if (hadGridFocus) focusStateActionIfNeeded()
    }

    private fun showState(title: Int, message: Int, action: Int?, showProgress: Boolean = false) {
        binding.stateTitle.setText(title)
        binding.stateMessage.setText(message)
        binding.stateProgress.visibility = if (showProgress) View.VISIBLE else View.GONE
        binding.stateActionButton.visibility = if (action == null) View.GONE else View.VISIBLE
        if (action != null) binding.stateActionButton.setText(action)
        binding.stateContainer.visibility = View.VISIBLE
    }

    private fun focusStateActionIfNeeded() {
        binding.stateActionButton.post {
            if (focusContentWhenReady && binding.stateContainer.visibility == View.VISIBLE &&
                binding.stateActionButton.visibility == View.VISIBLE
            ) {
                binding.stateActionButton.requestFocus()
                focusContentWhenReady = false
            }
        }
    }

    private fun openPlayer(position: Int) {
        if (position !in videos.indices) return
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_START_INDEX, position)
            putExtra(
                PlayerActivity.EXTRA_VIDEO_URIS,
                videos.map { it.uri.toString() }.toTypedArray()
            )
            putExtra(
                PlayerActivity.EXTRA_VIDEO_TITLES,
                videos.map { it.title }.toTypedArray()
            )
        }
        startActivity(intent)
    }

    companion object {
        private const val REQ_PERMISSION = 1001
        private const val STATE_PERMISSION_DENIED = "permission_was_denied"
    }
}
