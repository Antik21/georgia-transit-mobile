package com.denis.georgiatransit.android

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.chuckerteam.chucker.api.Chucker
import com.denis.georgiatransit.shared.data.network.createTransitHttpClient
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DebugNetworkInspectorActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var resultView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_network_inspector)

        resultView = findViewById(R.id.debug_network_inspector_result)
        findViewById<Button>(R.id.debug_network_inspector_open_history).setOnClickListener {
            startActivity(Chucker.getLaunchIntent(this))
        }
        findViewById<Button>(R.id.debug_network_inspector_run_success).setOnClickListener {
            runSmokeRequest(
                title = "Successful HTTPS smoke",
                url = "https://example.com/",
            )
        }
        findViewById<Button>(R.id.debug_network_inspector_run_failure).setOnClickListener {
            runSmokeRequest(
                title = "Controlled local failure",
                url = "https://127.0.0.1:1/den-77-controlled-failure",
            )
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    private fun runSmokeRequest(
        title: String,
        url: String,
    ) {
        resultView.text = "$title is running…"
        activityScope.launch {
            val client = createTransitHttpClient()
            try {
                val response = client.get(url)
                resultView.text = "$title completed with HTTP ${response.status.value}."
            } catch (error: Exception) {
                resultView.text = "$title recorded ${error::class.simpleName}."
            } finally {
                client.close()
            }
        }
    }
}
