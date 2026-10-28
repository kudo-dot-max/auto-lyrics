package com.autolyrics

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.autolyrics.lyrics.LrcLibClient
import com.autolyrics.lyrics.TrackMatcher
import com.autolyrics.media.MediaTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows the LRCLIB website (mobile layout) searched for the song that is playing.
 * When the user taps a result, the site navigates to lrclib.net/tracks/<id>; the app notices
 * that address, downloads that entry and uses its lyrics for the current song.
 */
class LyricsPickerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var handled = false
    private var trackTitle = ""
    private var trackArtist = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        trackTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        trackArtist = intent.getStringExtra(EXTRA_ARTIST).orEmpty()
        val tracker = MediaTracker.getInstance(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(8), dp(6))
        }
        val hint = TextView(this).apply {
            text = "Tap a result to use its lyrics for this song"
            setTextColor(Color.parseColor("#CCCCDD"))
            textSize = 13f
        }
        bar.addView(hint, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (tracker.isLyricsPinned()) {
            val reset = Button(this).apply {
                text = "Use automatic"
                isAllCaps = false
                textSize = 12f
                setOnClickListener {
                    tracker.resetLyricsChoice()
                    Toast.makeText(
                        this@LyricsPickerActivity, "Back to automatic lyrics", Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
            bar.addView(reset)
        }
        val close = Button(this).apply {
            text = "Close"
            isAllCaps = false
            textSize = 12f
            setOnClickListener { finish() }
        }
        bar.addView(close)

        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true // the LRCLIB site is a single-page app
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.setBackgroundColor(Color.parseColor("#121212"))
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (handleTrackUri(uri)) return true
                // Stay on the LRCLIB site; do not follow links to anywhere else.
                return !isLrclibHost(uri)
            }

            // The site changes pages without reloading, so watch the history updates too.
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (url != null) handleTrackUri(Uri.parse(url))
            }
        }

        root.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        val query = "${TrackMatcher.firstArtist(trackArtist)} ${TrackMatcher.stripFeat(trackTitle)}".trim()
        webView.loadUrl("https://lrclib.net/search/" + Uri.encode(query))
    }

    override fun onDestroy() {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private fun isLrclibHost(uri: Uri): Boolean =
        uri.host == "lrclib.net" || uri.host == "www.lrclib.net"

    /** Returns true if [uri] is a lyrics entry page (lrclib.net/tracks/<id>) and starts applying it. */
    private fun handleTrackUri(uri: Uri): Boolean {
        if (!isLrclibHost(uri)) return false
        val segments = uri.pathSegments
        if (segments.size < 2 || segments[0] != "tracks") return false
        val id = segments[1].toIntOrNull() ?: return false
        if (!handled) {
            handled = true
            applyEntry(id)
        }
        return true
    }

    private fun applyEntry(id: Int) {
        Toast.makeText(this, "Applying lyrics…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val entry = withContext(Dispatchers.IO) { LrcLibClient.getById(id) }
            val tracker = MediaTracker.getInstance(this@LyricsPickerActivity)
            val current = tracker.state.value.track

            when {
                entry == null -> {
                    toast("Could not load that entry. Check your connection and try again.")
                    retryAfterFailure()
                }
                current == null || current.title != trackTitle || current.artist != trackArtist -> {
                    toast("The song changed. Open Choose lyrics again.")
                    finish()
                }
                !tracker.applyLyricsCandidate(entry) -> {
                    toast("That entry has no usable lyrics. Pick another one.")
                    retryAfterFailure()
                }
                else -> {
                    toast("Lyrics applied")
                    finish()
                }
            }
        }
    }

    private fun retryAfterFailure() {
        handled = false
        if (webView.canGoBack()) webView.goBack()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
    }
}
