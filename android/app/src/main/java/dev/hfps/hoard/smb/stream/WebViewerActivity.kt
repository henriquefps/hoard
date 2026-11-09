package dev.hfps.hoard.smb.stream

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import dev.hfps.hoard.R

/**
 * Full-screen viewer for an HTML page served by [LocalWebServer]: a native WebView of its own (the
 * app's Capacitor WebView is `https://localhost`, where an `http://127.0.0.1` iframe would be blocked
 * as mixed content). Links within the served folder stay here; links to other sites and downloads go
 * to the browser. The toolbar's "Open in browser" hands the same URL to Chrome (or the default
 * browser). While the viewer is open it holds the token, so it cannot expire under the page.
 */
class WebViewerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var token: String = ""
    private var origin: Uri? = null
    private var held = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StreamRegistry.init(this)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        origin = Uri.parse(url)
        held = StreamRegistry.hold(token)
        if (!held) {
            // Process restarted (server gone) or the token ended: nothing to show.
            Toast.makeText(this, R.string.web_viewer_expired, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val toolbar = Toolbar(this).apply {
            title = intent.getStringExtra(EXTRA_TITLE)
            setNavigationIcon(R.drawable.ic_stream_close)
            setNavigationContentDescription(R.string.web_viewer_close)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.web_viewer)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.web_viewer_open_in_browser -> openInBrowser(webView.url ?: url)
                    R.id.web_viewer_reload -> webView.reload()
                }
                true
            }
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = Client()
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.isVisible = newProgress < 100
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    if (!title.isNullOrBlank() && !title.startsWith("http://127.0.0.1")) toolbar.title = title
                }
            }
            setDownloadListener { downloadUrl, _, _, _, _ -> openInBrowser(downloadUrl) }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // Edge-to-edge (enforced from Android 15): keep the toolbar and the page off the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        // Dark status/navigation bar icons on the light theme, light ones on the dark theme.
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) webView.loadUrl(url)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::webView.isInitialized) webView.saveState(outState)
    }

    override fun onDestroy() {
        if (held) StreamRegistry.release(token)
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun isServed(uri: Uri): Boolean {
        val base = origin ?: return false
        return uri.scheme == "http" && uri.host == base.host && uri.port == base.port &&
            uri.pathSegments.firstOrNull() == token
    }

    private fun openInBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.web_viewer_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (isServed(uri)) return false
            // Another site, mailto:, tel:, intent: … → the system decides.
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: ActivityNotFoundException) {
                // Nothing handles it: ignore the click.
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            progressBar.isVisible = true
        }
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, url: String, token: String, title: String): Intent =
            Intent(context, WebViewerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_TITLE, title)
    }
}
