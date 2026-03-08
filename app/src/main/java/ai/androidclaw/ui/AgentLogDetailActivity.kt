package ai.androidclaw.ui

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import ai.androidclaw.databinding.ActivityAgentLogDetailBinding
import java.io.File

class AgentLogDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAgentLogDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAgentLogDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "日志详情"

        val path = intent.getStringExtra("path").orEmpty()
        val file = File(path)
        binding.tvContent.text = if (file.exists()) file.readText() else "日志文件不存在"
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            finish()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }
}
