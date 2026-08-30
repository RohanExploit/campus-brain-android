package com.kriet.campusbrain

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.kriet.campusbrain.data.BrainRepository
import com.kriet.campusbrain.data.InitState
import com.kriet.campusbrain.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager
            .findFragmentById(R.id.navHost) as NavHostFragment
        val navController = navHost.navController
        binding.bottomNav.setupWithNavController(navController)

        // The self test is deliberately not a tab: it is a pre-demo check, not
        // a feature. Long-press the title to reach it.
        binding.toolbar.setOnLongClickListener {
            navController.navigate(R.id.selfTestFragment); true
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) { BrainRepository.init(applicationContext) }
            when (val s = BrainRepository.state.value) {
                is InitState.Failed -> {
                    binding.offlineBanner.text = s.message.lineSequence().first()
                    Toast.makeText(this@MainActivity, s.message, Toast.LENGTH_LONG).show()
                }
                is InitState.Ready -> {
                    val m = s.repo.db.meta
                    binding.offlineBanner.text = getString(R.string.offline_banner) +
                        "  ·  ${m["document_count"] ?: "?"} docs · ${m["chunk_count"]} chunks"
                    if (!s.repo.fts.available) {
                        // Not a crash, but the user should know retrieval is
                        // running on the degraded keyword path.
                        binding.offlineBanner.text =
                            "FTS5 unavailable — using LIKE fallback (results will be weaker)"
                    }
                }
                InitState.Loading -> Unit
            }
            binding.offlineBanner.visibility = View.VISIBLE
        }
    }
}
