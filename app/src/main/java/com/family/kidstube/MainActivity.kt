package com.family.kidstube

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.family.kidstube.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: VideoAdapter
    private lateinit var updateManager: AppUpdateManager
    private var videos: List<VideoItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = VideoAdapter { position -> openPlayer(position) }
        updateManager = AppUpdateManager(this)
        binding.videoGrid.layoutManager = GridLayoutManager(this, 4)
        binding.videoGrid.adapter = adapter

        binding.checkUpdatesButton.setOnClickListener { updateManager.checkForUpdates() }
        binding.refreshButton.setOnClickListener { checkPermissionAndLoad() }
        binding.emptyText.setOnClickListener { checkPermissionAndLoad() }
        checkPermissionAndLoad()
    }

    override fun onResume() {
        super.onResume()
        if (::updateManager.isInitialized) updateManager.onResume()
        // Refresh in case new videos were copied while the app was open
        if (hasPermission()) loadVideos()
    }

    override fun onDestroy() {
        if (::adapter.isInitialized) adapter.close()
        if (::updateManager.isInitialized) updateManager.close()
        super.onDestroy()
    }

    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_VIDEO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkPermissionAndLoad() {
        if (hasPermission()) {
            loadVideos()
        } else {
            val permission = if (Build.VERSION.SDK_INT >= 33)
                Manifest.permission.READ_MEDIA_VIDEO
            else
                Manifest.permission.READ_EXTERNAL_STORAGE
            requestPermissions(arrayOf(permission), REQ_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                loadVideos()
            } else {
                binding.emptyText.setText(R.string.permission_needed)
                binding.emptyText.visibility = View.VISIBLE
            }
        }
    }

    private fun loadVideos() {
        Thread {
            val list = VideoStore.loadVideos(this)
            runOnUiThread {
                videos = list
                adapter.submitList(list)
                if (list.isEmpty()) {
                    binding.emptyText.setText(R.string.no_videos_hint)
                    binding.emptyText.visibility = View.VISIBLE
                } else {
                    binding.emptyText.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun openPlayer(position: Int) {
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
    }
}
