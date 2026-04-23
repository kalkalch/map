// AboutActivity.kt
package com.map.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.map.BuildConfig
import com.map.R
import com.map.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAboutBinding
    
    companion object {
        private const val WEBSITE_URL = "https://mooqle.com"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupViews()
    }
    
    private fun setupViews() {
        binding.tvAppName.text = getString(R.string.app_name)
        binding.tvAppVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
        
        binding.btnWebsite.text = getString(R.string.about_website)
        binding.btnWebsite.setOnClickListener {
            openWebsite()
        }
        
        binding.btnCheckUpdates.text = getString(R.string.about_check_updates)
        binding.btnCheckUpdates.setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_ACTION_CHECK_UPDATES, true)
                }
            )
        }
    }
    
    private fun openWebsite() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL))
            startActivity(intent)
        } catch (e: Exception) {
            showMessage("Не удалось открыть браузер")
        }
    }
    
    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
