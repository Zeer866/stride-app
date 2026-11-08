package com.stride.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var addr: EditText
    private lateinit var prog: ProgressBar
    private lateinit var prefs: SharedPreferences
    private lateinit var defaultUA: String
    private var blocked = 0

    private val blue = Color.parseColor("#0A6CFF")
    private val sky = Color.parseColor("#EAF4FF")
    private val ink = Color.parseColor("#0B1F44")
    private val trk = Regex("^(utm_|fbclid|gclid|msclkid|igshid|yclid)")
    private val desktopUA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val engines = linkedMapOf(
        "Google" to "https://www.google.com/search?q=",
        "Bing" to "https://www.bing.com/search?q=",
        "DuckDuckGo" to "https://duckduckgo.com/?q=",
        "Brave" to "https://search.brave.com/search?q=",
        "Ecosia" to "https://www.ecosia.org/search?q="
    )

    private val adHosts = ("doubleclick.net googlesyndication.com googleadservices.com adservice.google.com " +
        "google-analytics.com googletagmanager.com googletagservices.com connect.facebook.net ads.twitter.com " +
        "analytics.twitter.com adnxs.com taboola.com outbrain.com criteo.com criteo.net pubmatic.com " +
        "rubiconproject.com openx.net adsrvr.org scorecardresearch.com quantserve.com hotjar.com mixpanel.com " +
        "popads.net propellerads.com exoclick.com adsterra.com mgid.com revcontent.com amazon-adsystem.com " +
        "moatads.com adform.net smartadserver.com yieldmo.com 33across.com bidswitch.net casalemedia.com " +
        "clickadu.com trafficjunky.com juicyads.com onclickads.net adcash.com zedo.com ads.yahoo.com " +
        "advertising.com").split(" ")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun flag(k: String, d: Boolean = true) = prefs.getBoolean(k, d)
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun isAd(host: String?): Boolean =
        host != null && adHosts.any { host == it || host.endsWith(".$it") }

    private fun btn(t: String, a: () -> Unit): TextView = TextView(this).apply {
        text = t
        textSize = 24f
        setTextColor(ink)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        setBackgroundResource(tv.resourceId)
        setOnClickListener { a() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        prefs = getSharedPreferences("stride", MODE_PRIVATE)
        window.statusBarColor = sky
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        web = WebView(this)
        defaultUA = web.settings.userAgentString
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSafeBrowsingEnabled(true)
            userAgentString = if (flag("desktop", false)) desktopUA else defaultUA
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView?, r: WebResourceRequest?): WebResourceResponse? {
                if (r != null && flag("ads") && isAd(r.url.host)) {
                    blocked++
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                val u = r?.url ?: return true
                return when (u.scheme) {
                    "https" -> false
                    "http" -> {
                        v?.loadUrl(u.buildUpon().scheme("https").build().toString())
                        true
                    }
                    "tel", "mailto" -> {
                        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                        true
                    }
                    else -> true
                }
            }

            override fun onPageStarted(v: WebView?, url: String?, f: Bitmap?) {
                prog.visibility = View.VISIBLE
                if (url != null && !addr.hasFocus()) addr.setText(url)
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                prog.visibility = View.GONE
                if (url != null && !addr.hasFocus()) addr.setText(url)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, p: Int) {
                prog.progress = p
            }
        }
        web.setDownloadListener { url, ua, cd, mime, _ ->
            try {
                val req = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mime)
                    .addRequestHeader("User-Agent", ua)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, cd, mime)
                    )
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                toast("Descargando…")
            } catch (_: Exception) {
                toast("No se pudo descargar")
            }
        }

        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(sky)
        }
        prog = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(blue)
        }

        addr = EditText(this).apply {
            hint = "Busca o escribe una dirección"
            setSingleLine()
            textSize = 16f
            setTextColor(ink)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            setPadding(dp(18), 0, dp(18), 0)
            background = GradientDrawable().apply {
                setColor(sky)
                cornerRadius = dp(24).toFloat()
            }
            setOnEditorActionListener { _, id, ev ->
                if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                    go(text.toString())
                    hideKb()
                    clearFocus()
                    true
                } else false
            }
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("‹") { if (web.canGoBack()) web.goBack() })
        row.addView(btn("›") { if (web.canGoForward()) web.goForward() })
        row.addView(btn("⌂") { load(home()) })
        row.addView(btn("↻") { web.reload() })
        row.addView(btn("⋮") { }.also { b -> b.setOnClickListener { menu(b) } })

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(4))
            elevation = dp(8).toFloat()
        }
        bar.addView(addr, LinearLayout.LayoutParams(mp, dp(46)))
        bar.addView(row, LinearLayout.LayoutParams(mp, dp(50)))

        root.addView(prog, LinearLayout.LayoutParams(mp, dp(3)))
        root.addView(web, LinearLayout.LayoutParams(mp, 0, 1f))
        root.addView(bar, LinearLayout.LayoutParams(mp, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        val data = intent?.data
        if (data != null) load(data.toString()) else load(home())
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.let { load(it.toString()) }
    }

    private fun engineName() = prefs.getString("engine", "Google") ?: "Google"

    private fun home(): String {
        val u = Uri.parse(engines[engineName()] ?: engines["Google"]!!)
        return "${u.scheme}://${u.host}"
    }

    private fun go(input: String) {
        val q = input.trim()
        if (q.isEmpty()) return
        if (Regex("^(javascript|data|file|blob|content):", RegexOption.IGNORE_CASE).containsMatchIn(q)) {
            toast("Dirección no permitida")
            return
        }
        val url = when {
            Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(q) -> q
            !q.contains(' ') && Regex("^[^\\s/]+\\.[a-zA-Z]{2,}(:\\d+)?([/?#].*)?$").matches(q) -> "https://$q"
            else -> (engines[engineName()] ?: engines["Google"]!!) + Uri.encode(q)
        }
        load(url)
    }

    private fun load(url: String) {
        var u = url
        if (u.startsWith("http://", true)) u = "https://" + u.substring(7)
        web.loadUrl(clean(u))
    }

    private fun clean(u: String): String {
        val x = Uri.parse(u)
        if (x.isOpaque) return u
        val names = x.queryParameterNames
        if (names.none { trk.containsMatchIn(it) }) return u
        val b = x.buildUpon().clearQuery()
        names.filter { !trk.containsMatchIn(it) }.forEach { n ->
            x.getQueryParameters(n).forEach { b.appendQueryParameter(n, it) }
        }
        return b.build().toString()
    }

    private fun hideKb() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(addr.windowToken, 0)
    }

    private fun menu(anchor: View) {
        val ads = flag("ads")
        val desk = flag("desktop", false)
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Buscador: ${engineName()}")
        m.menu.add(0, 2, 1, (if (ads) "✓ " else "") + "Bloquear anuncios ($blocked)")
        m.menu.add(0, 3, 2, (if (desk) "✓ " else "") + "Sitio de escritorio")
        m.menu.add(0, 4, 3, "Compartir enlace")
        m.menu.add(0, 5, 4, "Borrar datos de navegación")
        m.setOnMenuItemClickListener { i ->
            when (i.itemId) {
                1 -> {
                    val names = engines.keys.toTypedArray()
                    AlertDialog.Builder(this).setTitle("Buscador")
                        .setItems(names) { _, k ->
                            prefs.edit().putString("engine", names[k]).apply()
                            toast("Buscador: ${names[k]}")
                        }.show()
                }
                2 -> {
                    prefs.edit().putBoolean("ads", !ads).apply()
                    web.reload()
                }
                3 -> {
                    prefs.edit().putBoolean("desktop", !desk).apply()
                    web.settings.userAgentString = if (!desk) desktopUA else defaultUA
                    web.reload()
                }
                4 -> {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, web.url ?: "")
                    }
                    startActivity(Intent.createChooser(send, "Compartir"))
                }
                5 -> {
                    web.clearCache(true)
                    web.clearHistory()
                    WebStorage.getInstance().deleteAllData()
                    CookieManager.getInstance().removeAllCookies(null)
                    toast("Datos borrados")
                }
            }
            true
        }
        m.show()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
