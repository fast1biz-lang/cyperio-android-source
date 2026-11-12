package com.v2ray.ang.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.Language
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.math.ceil
import kotlin.math.max

/**
 * Cyperio: the simple home screen. One connect button, a country picker and the
 * time / data left. It drives the same engine as [MainActivity] (subscriptions,
 * server list, VPN service) - the customer just never has to see that screen.
 */
class HomeActivity : HelperBaseActivity() {

    private enum class State { OFF, CONNECTING, ON }

    private data class Candidate(
        val guid: String,
        val country: String,
        val viaIran: Boolean,
        val host: String,
        val port: Int,
        var latencyMs: Long = -1
    )

    private val mainViewModel: MainViewModel by viewModels()
    private var state = State.OFF
    private var busy = false
    private var countries: List<String> = emptyList()
    private var lastUsageFetch = 0L
    private var cachedCandidates: List<Candidate>? = null
    private var healthJob: Job? = null
    private var healthFails = 0

    private lateinit var mainPane: View
    private lateinit var setupPane: View
    private lateinit var power: View
    private lateinit var powerIcon: ImageView
    private lateinit var statusText: TextView
    private lateinit var hintText: TextView
    private lateinit var halo: View
    private lateinit var core: View
    private lateinit var spin: View
    private lateinit var pillText: TextView
    private lateinit var flagImage: ImageView
    private lateinit var countryNameText: TextView
    private lateinit var countrySubText: TextView
    private lateinit var daysBar: ProgressBar
    private lateinit var dataBar: ProgressBar
    private var pulse: ObjectAnimator? = null
    private var clockJob: Job? = null
    private var exitIp = ""
    private lateinit var daysText: TextView
    private lateinit var dataText: TextView
    private lateinit var linkInput: EditText
    private lateinit var setupError: TextView
    private lateinit var setupCancel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var updateText: TextView
    private lateinit var userText: TextView
    private lateinit var warnText: TextView
    private var titleTaps = 0
    private var titleTapAt = 0L

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            lifecycleScope.launch { connect() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        // the screen is always dark, so the system bars are too
        val navy = ContextCompat.getColor(this, R.color.cy_navy)
        window.statusBarColor = navy
        window.navigationBarColor = navy
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        mainPane = findViewById(R.id.home_main)
        setupPane = findViewById(R.id.home_setup)
        power = findViewById(R.id.home_power)
        powerIcon = findViewById(R.id.home_power_icon)
        statusText = findViewById(R.id.home_status)
        hintText = findViewById(R.id.home_hint)
        halo = findViewById(R.id.home_halo)
        core = findViewById(R.id.home_core)
        spin = findViewById(R.id.home_spin)
        pillText = findViewById(R.id.home_pill)
        flagImage = findViewById(R.id.home_flag)
        flagImage.clipToOutline = true   // rounded corners come from its background shape
        countryNameText = findViewById(R.id.home_country_name)
        countrySubText = findViewById(R.id.home_country_sub)
        daysBar = findViewById(R.id.home_days_bar)
        dataBar = findViewById(R.id.home_data_bar)
        findViewById<View>(R.id.home_country_card).setOnClickListener { showCountries(it) }
        daysText = findViewById(R.id.home_days)
        dataText = findViewById(R.id.home_data)
        linkInput = findViewById(R.id.home_link)
        setupError = findViewById(R.id.home_setup_error)
        setupCancel = findViewById(R.id.home_setup_cancel)
        progress = findViewById(R.id.home_progress)
        updateText = findViewById(R.id.home_update)
        userText = findViewById(R.id.home_user)
        warnText = findViewById(R.id.home_warn)
        warnText.setOnClickListener { if (buyUrl().isNotEmpty()) openBuy() else openSupport() }
        updateText.setOnClickListener { openUpdate() }

        power.setOnClickListener { toggle() }
        findViewById<ImageButton>(R.id.home_gear).setOnClickListener { showMenu(it) }
        // advanced settings (the full v2rayNG screen) are hidden from customers: 5 quick taps on the title open them
        findViewById<View>(R.id.home_title).setOnClickListener {
            val now = System.currentTimeMillis()
            titleTaps = if (now - titleTapAt < 1500) titleTaps + 1 else 1
            titleTapAt = now
            if (titleTaps >= 5) {
                titleTaps = 0
                startActivity(Intent(this, MainActivity::class.java))
            }
        }
        findViewById<View>(R.id.home_activate).setOnClickListener { activate(linkInput.text.toString()) }
        findViewById<View>(R.id.home_paste).setOnClickListener {
            linkInput.setText(Utils.getClipboard(this).takeIf { it != "null" }.orEmpty().trim())
        }
        findViewById<View>(R.id.home_scan).setOnClickListener {
            launchQRCodeScanner { result -> if (!result.isNullOrBlank()) activate(result) }
        }
        setupCancel.setOnClickListener { showSetup(false) }

        mainViewModel.isRunning.observe(this) { running ->
            if (!busy) {
                val was = state
                state = if (running == true) State.ON else State.OFF
                render()
                if (was == State.ON && state == State.OFF) refreshUsageSoon(force = true)   // show what this session used
            }
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
        SubscriptionUpdater.sync()
        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
        if (!BuildConfig.STORE_BUILD) ContextCompat.registerReceiver(this, downloadDone,
            android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        handleUpdateTap(intent)

        fillCountries()
        showUsage()
        render()
        if (currentSub() == null) {
            showSetup(true)
        } else {
            lifecycleScope.launch { refresh(announce = false) }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUsageSoon()   // customer looks at the screen: show current numbers
        healthJob?.cancel()
        healthJob = lifecycleScope.launch {
            while (isActive) {
                delay(60_000)
                refreshUsageSoon()
                checkHealth()
            }
        }
    }

    override fun onPause() {
        healthJob?.cancel()
        super.onPause()
    }

    /**
     * While this screen is open: does the tunnel still pass traffic? After two failures in a row
     * take the dead tunnel down, ask the panel why, then either show the reason (data used up,
     * subscription ended) or connect again through whichever server works now.
     */
    private suspend fun checkHealth() {
        if (busy || state != State.ON) {
            healthFails = 0
            return
        }
        if (tunnelWorks()) {
            healthFails = 0
            return
        }
        if (++healthFails < 2 || busy || state != State.ON) return
        healthFails = 0
        LogUtil.w(AppConfig.TAG, "Cyperio: the tunnel passes no traffic any more")
        busy = true
        CoreServiceManager.stopVService(this)
        waitRunning(false, 5000)
        state = State.OFF
        render(hint = getString(R.string.cyperio_dropped))
        busy = false
        currentSub()?.subscription?.url?.let { url -> withContext(Dispatchers.IO) { fetchUsage(url) } }
        showUsage()
        if (blockedText() != null) {
            render()
            return
        }
        connect()
    }

    /** Fresh time / data numbers, at most once a minute (the panel counts every 5 minutes). Cheap: headers only. */
    private fun refreshUsageSoon(force: Boolean = false) {
        val url = currentSub()?.subscription?.url ?: return
        val now = System.currentTimeMillis()
        if (busy || (!force && now - lastUsageFetch < 60_000)) return
        lastUsageFetch = now
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { fetchUsage(url) }
            showUsage()
            if (state == State.OFF && !busy) render()   // e.g. "data used up" appeared or went away
            // data ran out / subscription ended / paused while connected: say so instead of a dead "connected"
            if (state == State.ON && !busy && blockedText() != null) CoreServiceManager.stopVService(this@HomeActivity)
        }
    }

    // ---------------------------------------------------------------- data
    private fun currentSub(): SubscriptionCache? =
        MmkvManager.decodeSubscriptions().firstOrNull { it.subscription.url.isNotBlank() }

    /** VLESS servers of the customer's subscription, with the country read from the server name. */
    private fun candidates(): List<Candidate> =
        cachedCandidates ?: loadCandidates().also { cachedCandidates = it }   // dropped in fillCountries(), i.e. after every list change

    private fun loadCandidates(): List<Candidate> {
        val sub = currentSub() ?: return emptyList()
        return MmkvManager.decodeServerList(sub.guid).mapNotNull { guid ->
            val p = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
            if (p.configType != EConfigType.VLESS) return@mapNotNull null
            val host = p.server ?: return@mapNotNull null
            val port = p.serverPort?.toIntOrNull() ?: return@mapNotNull null
            val label = p.remarks.substringAfter(" - ", p.remarks)
            val m = Regex("^Iran to (.+?)(\\s*\\(|$)", RegexOption.IGNORE_CASE).find(label)
            val place = m?.groupValues?.get(1) ?: label
            Candidate(guid, COUNTRY_KEYS.firstOrNull { it.second.containsMatchIn(place) }?.first ?: place.trim(), m != null, host, port)
        }
    }

    private fun countryName(country: String): String = when (country) {
        "Sweden" -> getString(R.string.cyperio_country_sweden)
        "France" -> getString(R.string.cyperio_country_france)
        "Poland" -> getString(R.string.cyperio_country_poland)
        "Bulgaria" -> getString(R.string.cyperio_country_bulgaria)
        "USA" -> getString(R.string.cyperio_country_usa)
        "Germany" -> getString(R.string.cyperio_country_germany)
        "Iran" -> getString(R.string.cyperio_country_iran)
        else -> country
    }

    /** Called after every change of the server list. */
    private fun fillCountries() {
        cachedCandidates = null
        countries = listOf("") + candidates().map { it.country }.distinct()
        val saved = MmkvManager.decodeSettingsString(KEY_COUNTRY, "").orEmpty()
        if (saved.isNotEmpty() && saved !in countries) MmkvManager.encodeSettings(KEY_COUNTRY, "")   // no longer offered
        showCountry()
    }

    private fun flagRes(country: String): Int = when (country) {
        "Sweden" -> R.drawable.cy_flag_sweden
        "France" -> R.drawable.cy_flag_france
        "Poland" -> R.drawable.cy_flag_poland
        "Bulgaria" -> R.drawable.cy_flag_bulgaria
        "USA" -> R.drawable.cy_flag_usa
        "Germany" -> R.drawable.cy_flag_germany
        "Iran" -> R.drawable.cy_flag_iran
        else -> R.drawable.cy_flag_globe
    }

    /** The country card: the connected country while connected, otherwise the customer's choice. */
    private fun showCountry() {
        val connected = if (state == State.ON) {
            MmkvManager.getSelectServer()?.let { sel -> candidates().firstOrNull { it.guid == sel } }?.country
        } else {
            null
        }
        val country = connected ?: MmkvManager.decodeSettingsString(KEY_COUNTRY, "").orEmpty()
        flagImage.setImageResource(flagRes(country))
        countryNameText.text = if (country.isEmpty()) getString(R.string.cyperio_auto_short) else countryName(country)
        countrySubText.setText(
            when {
                state == State.ON -> R.string.cyperio_all_apps
                country.isEmpty() -> R.string.cyperio_auto_sub
                else -> R.string.cyperio_picked_sub
            }
        )
    }

    private fun showCountries(anchor: View) {
        if (state == State.CONNECTING || busy) return
        val saved = MmkvManager.decodeSettingsString(KEY_COUNTRY, "").orEmpty()
        // a card with a flag per country, opening above the country card (same look as the Windows app)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val list = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(6), px(6), px(6), px(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@HomeActivity, R.color.cy_card))
                setStroke(px(1), ContextCompat.getColor(this@HomeActivity, R.color.cy_line))
                cornerRadius = 14 * dp
            }
        }
        val popup = android.widget.PopupWindow(list, anchor.width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            elevation = 12 * dp
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        countries.forEachIndexed { i, c ->
            val picked = c == saved
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(px(12), px(11), px(12), px(11))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (picked) ContextCompat.getColor(this@HomeActivity, R.color.cy_teal_bg) else android.graphics.Color.TRANSPARENT)
                    cornerRadius = 10 * dp
                }
                isClickable = true
                setOnClickListener { popup.dismiss(); onCountryPicked(i) }
            }
            row.addView(ImageView(this).apply {
                setImageResource(flagRes(c))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, android.widget.LinearLayout.LayoutParams(px(34), px(22)))
            row.addView(TextView(this).apply {
                text = if (c.isEmpty()) getString(R.string.cyperio_auto) else countryName(c)
                setTextColor(ContextCompat.getColor(this@HomeActivity, if (picked) R.color.cy_teal_soft else R.color.cy_text))
                textSize = 15f
                if (picked) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(14) })
            if (picked) row.addView(TextView(this).apply {
                text = "✓"
                setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.cy_teal))
                textSize = 16f
            })
            list.addView(row)
        }
        list.measure(
            View.MeasureSpec.makeMeasureSpec(anchor.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        popup.showAsDropDown(anchor, 0, -(anchor.height + list.measuredHeight + px(8)))
    }

    private fun setBar(bar: ProgressBar, fraction: Double, known: Boolean) {
        val f = fraction.coerceIn(0.0, 1.0)
        bar.progress = (f * 1000).toInt()
        val color = if (!known) R.color.cy_line else if (f <= 0.10) R.color.cy_red else if (f <= 0.25) R.color.cy_amber else R.color.cy_teal
        bar.progressTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))
    }

    /** The customer's name, from the link (.../sub/NAME/TOKEN); empty if the link has another shape. */
    private fun userName(): String {
        val url = currentSub()?.subscription?.url ?: return ""
        val i = url.indexOf("/sub/")
        if (i < 0) return ""
        val name = url.substring(i + 5).split('/', '?', '#')[0]
        return try { java.net.URLDecoder.decode(name, "UTF-8") } catch (e: Exception) { name }
    }

    private fun showUsage() {
        val user = userName()
        userText.visibility = if (user.isEmpty()) View.GONE else View.VISIBLE
        userText.text = user
        val expire = MmkvManager.decodeSettingsLong(KEY_EXPIRE, 0)
        val total = MmkvManager.decodeSettingsLong(KEY_TOTAL, 0)
        val used = MmkvManager.decodeSettingsLong(KEY_USED, 0)
        daysText.text = if (expire <= 0) {
            setBar(daysBar, 1.0, false)
            getString(R.string.cyperio_unlimited)
        } else {
            val left = (expire - System.currentTimeMillis() / 1000) / 86400.0
            setBar(daysBar, left / 30.0, true)   // a full bar = a month or more
            if (left <= 0) getString(R.string.cyperio_expired) else getString(R.string.cyperio_days, max(1, ceil(left).toInt()))
        }
        dataText.text = if (total <= 0) {
            setBar(dataBar, 1.0, false)
            getString(R.string.cyperio_unlimited)
        } else {
            val gb = max(0L, total - used) / 1073741824.0
            setBar(dataBar, max(0L, total - used).toDouble() / total, true)
            getString(R.string.cyperio_gb, if (gb >= 10) "%.0f".format(gb) else "%.1f".format(gb))
        }
        showWarning(expire, total, used)
        showUpdate()
        if (currentSub() != null) askNotificationsOnce()   // customers who activated before 1.4.1
    }

    /** Android 13+: notifications need a permission; asked once, after a subscription is in place. */
    private fun askNotificationsOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33 || MmkvManager.decodeSettingsBool(KEY_NOTIF_ASKED, false)) return
        MmkvManager.encodeSettings(KEY_NOTIF_ASKED, true)
        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) { }
    }

    private fun support(): String = MmkvManager.decodeSettingsString(KEY_SUPPORT, "").orEmpty()

    /** The customer's purchase page (.../my/NAME/TOKEN/plan), only when the panel says they can buy there (X-Cyperio-Buy). */
    private fun buyUrl(): String {
        if (BuildConfig.STORE_BUILD) return ""   // Google Play build: no purchase page at all
        if (!MmkvManager.decodeSettingsBool(KEY_BUY, false)) return ""
        val url = currentSub()?.subscription?.url ?: return ""
        val i = url.indexOf("/sub/")
        if (i < 0 || !url.startsWith("https://")) return ""
        val rest = url.substring(i + 5).split('?', '#')[0].trimEnd('/')
        if (rest.split('/').size != 2) return ""
        return url.substring(0, i) + "/my/" + rest + "/plan"
    }

    /** Opens the purchase page in the phone's browser; if no browser takes it, shows the link to copy instead. */
    private fun openBuy() {
        val url = buyUrl()
        if (url.isEmpty()) {
            toast(R.string.cyperio_buy_failed)
            return
        }
        try {   // inside the app: some phones have no browser that opens links from other apps
            // by name: BuyActivity exists only in the APK build (src/direct), not in the Google Play build
            startActivity(Intent().setClassName(this, "com.v2ray.ang.ui.BuyActivity").putExtra("url", url))
            return
        } catch (e: Exception) {
            android.util.Log.e("Cyperio", "open buy page: $e")
        }
        Utils.setClipboard(this, url)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.cyperio_menu_buy)
            .setMessage(getString(R.string.cyperio_buy_copy) + "\n\n" + url)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** "Your subscription ends in 2 days" / "only 3 GB left", so customers renew before the connection stops. */
    private fun showWarning(expire: Long, total: Long, used: Long) {
        val active = MmkvManager.decodeSettingsString(KEY_STATUS, "active") == "active"
        val daysLeft = if (expire > 0) (expire - System.currentTimeMillis() / 1000) / 86400.0 else Double.MAX_VALUE
        val left = max(0L, total - used)
        val status = MmkvManager.decodeSettingsString(KEY_STATUS, "active")
        val canBuy = buyUrl().isNotEmpty()
        if (canBuy && (status == "quota" || status == "expired")) {   // cut off: the bar only offers the purchase page
            warnText.visibility = View.VISIBLE
            warnText.text = getString(R.string.cyperio_blocked_buy)
            return
        }
        val text = when {
            !active -> null
            daysLeft > 0 && daysLeft <= 3 -> getString(R.string.cyperio_warn_days, getString(R.string.cyperio_days, max(1, ceil(daysLeft).toInt())))
            total > 0 && left < minOf(5L * 1073741824, total / 10) -> {
                val gb = left / 1073741824.0
                getString(R.string.cyperio_warn_data, getString(R.string.cyperio_gb, if (gb >= 10) "%.0f".format(gb) else "%.1f".format(gb)))
            }
            else -> null
        }
        warnText.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) warnText.text = text + if (canBuy) getString(R.string.cyperio_warn_buy) else if (support().isNotEmpty()) getString(R.string.cyperio_warn_tap) else ""
    }

    /** Opens the seller's chat with the connection details on the clipboard (WhatsApp also gets them typed in). */
    private fun openSupport() {
        val link = support()
        if (link.isEmpty()) return
        val details = detailText()
        Utils.setClipboard(this, details)
        val url = if (link.startsWith("https://wa.me/")) link + "?text=" + java.net.URLEncoder.encode(details, "UTF-8").replace("+", "%20") else link
        Utils.openUri(this, url)
        toast(R.string.cyperio_support_copied)
    }

    /** Why the customer cannot connect right now (from the panel), or null if nothing is wrong. */
    private fun blockedText(): String? = when (MmkvManager.decodeSettingsString(KEY_STATUS, "active")) {
        "quota" -> getString(R.string.cyperio_st_quota)
        "expired" -> getString(R.string.cyperio_st_expired)
        "paused" -> getString(R.string.cyperio_st_paused)
        "invalid" -> getString(R.string.cyperio_st_invalid)
        else -> null
    }

    private fun updateAvailable(): Boolean {
        if (BuildConfig.STORE_BUILD) return false   // Google Play updates the app
        val latest = MmkvManager.decodeSettingsString(KEY_LATEST, "").orEmpty()
        if (latest.isEmpty() || MmkvManager.decodeSettingsString(KEY_LATEST_FILE, "").isNullOrEmpty()) return false
        val a = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val b = BuildConfig.VERSION_NAME.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until max(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun showUpdate() {
        val has = updateAvailable()
        updateText.visibility = if (has) View.VISIBLE else View.GONE
        if (has) updateText.text = getString(R.string.cyperio_update, MmkvManager.decodeSettingsString(KEY_LATEST, ""))
    }

    /** Downloads the new version with the system download manager; the installer opens when it is done. */
    private fun openUpdate() {
        val url = currentSub()?.subscription?.url ?: return
        val i = url.indexOf("/sub/")
        if (i < 0 || !updateAvailable()) return
        if (com.v2ray.ang.handler.CyperioUpdate.download(this, url)) {
            toast(R.string.cyperio_update_downloading)
            return
        }
        Utils.openUri(this, url.substring(0, i) + "/dl/" + MmkvManager.decodeSettingsString(KEY_LATEST_FILE, ""))
    }

    /** Menu "update the app": ask the panel now, then download or say this is the newest version. */
    private fun checkAppUpdate() {
        lifecycleScope.launch {
            refresh(announce = true)
            if (updateAvailable()) openUpdate()
            else toast(getString(R.string.cyperio_up_to_date, BuildConfig.VERSION_NAME))
        }
    }

    private val downloadDone = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            val id = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            com.v2ray.ang.handler.CyperioUpdate.install(this@HomeActivity, id)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateTap(intent)
    }

    /** The "new version" notification was tapped. */
    private fun handleUpdateTap(intent: Intent?) {
        if (intent?.getBooleanExtra(com.v2ray.ang.handler.CyperioUpdate.EXTRA_UPDATE, false) != true) return
        intent.removeExtra(com.v2ray.ang.handler.CyperioUpdate.EXTRA_UPDATE)
        if (updateAvailable()) openUpdate() else checkAppUpdate()
    }

    override fun onDestroy() {
        try { unregisterReceiver(downloadDone) } catch (e: Exception) { }
        super.onDestroy()
    }

    /** Reads how much time / data is left from the subscription's Subscription-Userinfo header. */
    private fun fetchUsage(url: String) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Cyperio-Android/${BuildConfig.VERSION_NAME}")
            conn.requestMethod = "GET"
            if (conn.responseCode == 404) {   // the panel no longer knows this link: replaced ("new link") or removed
                conn.disconnect()
                MmkvManager.encodeSettings(KEY_STATUS, "invalid")
                return
            }
            val info = conn.getHeaderField("Subscription-Userinfo").orEmpty()
            val status = conn.getHeaderField("X-Cyperio-Status").orEmpty().trim().lowercase().ifEmpty { "active" }
            // prefer the small APK built for this phone's processor; "android=" is the big one that fits every phone
            val offered = conn.getHeaderField("X-Cyperio-Update").orEmpty()
            val prefer = conn.getHeaderField("X-Cyperio-Prefer").orEmpty().trim()
            val preferOut = conn.getHeaderField("X-Cyperio-Prefer-Out").orEmpty().trim()
            val notice = conn.getHeaderField("X-Cyperio-Notice").orEmpty().trim()
            // the seller's support chat; only t.me / wa.me links are ever opened
            val support = conn.getHeaderField("X-Cyperio-Support").orEmpty().trim()
                .takeIf { Regex("https://(t\\.me|wa\\.me)/[A-Za-z0-9_+]+").matches(it) }.orEmpty()
            val buy = conn.getHeaderField("X-Cyperio-Buy").orEmpty().trim() == "1"
            conn.disconnect()
            MmkvManager.encodeSettings(KEY_STATUS, status)
            com.v2ray.ang.handler.CyperioUpdate.offer(this, offered)   // remembers it; a newer version is announced once
            MmkvManager.encodeSettings(KEY_PREFER, prefer)
            MmkvManager.encodeSettings(KEY_PREFER_OUT, preferOut)
            com.v2ray.ang.handler.CyperioNotices.handle(this, notice)
            MmkvManager.encodeSettings(KEY_SUPPORT, support)
            MmkvManager.encodeSettings(KEY_BUY, buy)
            var used = 0L
            var total = 0L
            var expire = 0L
            Regex("(\\w+)\\s*=\\s*(\\d+)").findAll(info).forEach {
                val v = it.groupValues[2].toLongOrNull() ?: 0L
                when (it.groupValues[1].lowercase()) {
                    "upload", "download" -> used += v
                    "total" -> total = v
                    "expire" -> expire = v
                }
            }
            MmkvManager.encodeSettings(KEY_USED, used)
            MmkvManager.encodeSettings(KEY_TOTAL, total)
            MmkvManager.encodeSettings(KEY_EXPIRE, expire)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Cyperio: usage fetch failed", e)
        }
    }

    // ---------------------------------------------------------------- screens
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun pill(text: Int, bg: Int, fg: Int) {
        pillText.setText(text)
        pillText.setTextColor(color(fg))
        pillText.backgroundTintList = ColorStateList.valueOf(color(bg))
    }

    private fun render(failed: Boolean = false, hint: String? = null) {
        when (state) {
            State.ON -> {
                halo.backgroundTintList = ColorStateList.valueOf(color(R.color.cy_teal_bg))
                core.setBackgroundResource(R.drawable.cy_core_on)
                powerIcon.setColorFilter(color(R.color.cy_text))
                statusText.setText(R.string.cyperio_connected)
                pill(R.string.cyperio_pill_on, R.color.cy_teal_bg, R.color.cy_teal_soft)
            }

            State.CONNECTING -> {
                halo.backgroundTintList = ColorStateList.valueOf(color(R.color.cy_amber_bg))
                core.setBackgroundResource(R.drawable.cy_core_busy)
                powerIcon.setColorFilter(color(R.color.cy_amber))
                statusText.setText(R.string.cyperio_connecting)
                hintText.textDirection = View.TEXT_DIRECTION_INHERIT
                hintText.text = hint.orEmpty()
                pill(R.string.cyperio_pill_connecting, R.color.cy_amber_bg, R.color.cy_amber)
            }

            State.OFF -> {
                halo.backgroundTintList = ColorStateList.valueOf(color(R.color.cy_halo_off))
                core.setBackgroundResource(R.drawable.cy_core_off)
                powerIcon.setColorFilter(color(R.color.cy_muted))
                hintText.textDirection = View.TEXT_DIRECTION_INHERIT
                val blocked = if (hint == null) blockedText() else null
                if (blocked != null) {
                    statusText.text = blocked
                    val st = MmkvManager.decodeSettingsString(KEY_STATUS, "active")
                    hintText.setText(when {
                        st == "invalid" -> R.string.cyperio_st_invalid_help
                        (st == "quota" || st == "expired") && buyUrl().isNotEmpty() -> R.string.cyperio_st_buy_help
                        else -> R.string.cyperio_st_help
                    })
                    pill(R.string.cyperio_pill_blocked, R.color.cy_red_bg, R.color.cy_red)
                } else {
                    statusText.setText(if (failed) R.string.cyperio_failed else R.string.cyperio_disconnected)
                    hintText.text = hint ?: getString(if (failed) R.string.cyperio_failed_hint else R.string.cyperio_tap_to_connect)
                    pill(R.string.cyperio_pill_off, R.color.cy_card, R.color.cy_muted)
                }
            }
        }
        spin.visibility = if (state == State.CONNECTING) View.VISIBLE else View.GONE
        // a slow breath while connected: "alive"
        if (state == State.ON) {
            if (pulse == null) {
                pulse = ObjectAnimator.ofPropertyValuesHolder(
                    halo, PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.07f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.07f)
                ).apply {
                    duration = 1600
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    start()
                }
            }
        } else {
            pulse?.cancel()
            pulse = null
            halo.scaleX = 1f
            halo.scaleY = 1f
        }
        showCountry()
        runClock()
    }

    /** While connected: session time and the address the internet sees, refreshed every second. */
    private fun runClock() {
        if (state != State.ON) {
            clockJob?.cancel()
            clockJob = null
            exitIp = ""
            if (state == State.OFF) MmkvManager.encodeSettings(KEY_CONNECTED_AT, 0L)
            return
        }
        if (MmkvManager.decodeSettingsLong(KEY_CONNECTED_AT, 0) == 0L) MmkvManager.encodeSettings(KEY_CONNECTED_AT, System.currentTimeMillis())
        if (clockJob?.isActive == true) return
        clockJob = lifecycleScope.launch {
            launch {
                val ip = withContext(Dispatchers.IO) {
                    try {
                        HttpUtil.getUrlContentWithUserAgent(
                            UrlContentRequest(
                                url = "https://ipv4.icanhazip.com", timeout = 8000, httpPort = SettingsManager.getHttpPort(),
                                proxyUsername = SettingsManager.getSocksUsername(), proxyPassword = SettingsManager.getSocksPassword()
                            )
                        ).trim()
                    } catch (e: Exception) {
                        ""
                    }
                }
                if (state == State.ON && Regex("^[0-9.]{7,15}$").matches(ip)) exitIp = ip
            }
            while (isActive && state == State.ON) {
                val secs = max(0L, (System.currentTimeMillis() - MmkvManager.decodeSettingsLong(KEY_CONNECTED_AT, 0)) / 1000)
                val clock = String.format(java.util.Locale.US, "%02d:%02d:%02d", secs / 3600, secs / 60 % 60, secs % 60)
                hintText.textDirection = View.TEXT_DIRECTION_LTR
                hintText.text = clock
                delay(1000)
            }
        }
    }

    private fun showSetup(show: Boolean) {
        setupPane.visibility = if (show) View.VISIBLE else View.GONE
        mainPane.visibility = if (show) View.GONE else View.VISIBLE
        setupCancel.visibility = if (show && currentSub() != null) View.VISIBLE else View.GONE
        setupError.text = ""
        if (show) linkInput.setText("")
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, R.string.cyperio_menu_refresh)
        menu.menu.add(0, 2, 1, R.string.cyperio_menu_change_link)
        menu.menu.add(0, 3, 2, R.string.cyperio_menu_language)
        menu.menu.add(0, 5, 1, R.string.cyperio_menu_details)
        if (buyUrl().isNotEmpty()) menu.menu.add(0, 7, 0, R.string.cyperio_menu_buy)
        if (!BuildConfig.STORE_BUILD) menu.menu.add(0, 8, 2, R.string.cyperio_menu_app_update)
        if (support().isNotEmpty()) menu.menu.add(0, 6, 1, R.string.cyperio_menu_support)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> lifecycleScope.launch { refresh(announce = true) }
                2 -> showSetup(true)
                3 -> {
                    val fa = SettingsManager.getLocale().language == "fa"
                    MmkvManager.encodeSettings(AppConfig.PREF_LANGUAGE, if (fa) Language.ENGLISH.code else Language.PERSIAN.code)
                    recreate()
                }

                4 -> startActivity(Intent(this, MainActivity::class.java))
                5 -> showDetails()
                6 -> openSupport()
                7 -> openBuy()
                8 -> checkAppUpdate()
            }
            true
        }
        menu.show()
    }

    /**
     * What support needs when a customer says "it does not work": which server, which exit address,
     * which version. Kept out of the main screen on purpose (it means nothing to most customers and
     * would end up in every screenshot).
     */
    private fun detailText(): String {
        val on = state == State.ON
        val server = if (on) MmkvManager.getSelectServer()?.let { MmkvManager.decodeServerConfig(it)?.remarks } else null
        val text = listOf(
            getString(R.string.cyperio_d_user) to userName().ifEmpty { "-" },
            getString(R.string.cyperio_d_state) to statusText.text.toString(),
            getString(R.string.cyperio_d_server) to (server ?: "-"),
            getString(R.string.cyperio_d_ip) to (if (on && exitIp.isNotEmpty()) exitIp else "-"),
            getString(R.string.cyperio_d_version) to "Cyperio Android ${BuildConfig.VERSION_NAME}",
        ).joinToString("\n") { "${it.first}: ${it.second}" }
        return text
    }

    private fun showDetails() {
        val text = detailText()
        val dlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.cyperio_menu_details)
            .setMessage(text + "\n\n" + getString(R.string.cyperio_d_help))
            .setPositiveButton(R.string.cyperio_d_copy) { _, _ -> Utils.setClipboard(this, text) }
            .setNegativeButton(android.R.string.cancel, null)
        if (support().isNotEmpty()) dlg.setNeutralButton(R.string.cyperio_d_support) { _, _ -> openSupport() }
        dlg.show()
    }

    // ---------------------------------------------------------------- subscription
    private fun activate(text: String) {
        if (busy) return
        val url = Regex("https://[^\\s\"'<>]+").find(text)?.value?.trimEnd('.', ',', ')')
        if (url == null) {
            setupError.setText(R.string.cyperio_bad_link)
            return
        }
        busy = true
        progress.visibility = View.VISIBLE
        setupError.setText(R.string.cyperio_checking)
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                try {
                    // one customer = one subscription: replace whatever was there
                    MmkvManager.decodeSubscriptions().filter { it.subscription.url.isNotBlank() && it.subscription.url != url }
                        .forEach { MmkvManager.removeSubscription(it.guid) }
                    val known = MmkvManager.decodeSubscriptions().firstOrNull { it.subscription.url == url }
                    if (known == null) {
                        AngConfigManager.importBatchConfig(url, "", true)
                    } else {
                        AngConfigManager.updateConfigViaSub(known)
                    }
                    // the server list may have been cached (empty) before this link was imported: read it afresh,
                    // or a first activation says "no servers" until the app is restarted (seen 2026-10-17)
                    cachedCandidates = null
                    val n = candidates().size
                    if (n > 0) fetchUsage(url)
                    n
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Cyperio: activation failed", e)
                    0
                }
            }
            progress.visibility = View.GONE
            busy = false
            if (count > 0) {
                askNotificationsOnce()
                MmkvManager.encodeSettings(KEY_COUNTRY, "")
                SubscriptionUpdater.sync()
                fillCountries()
                showUsage()
                showSetup(false)
                render()
            } else {
                setupError.setText(R.string.cyperio_fetch_failed)
            }
        }
    }

    private suspend fun refresh(announce: Boolean) {
        val sub = currentSub() ?: return
        if (announce) progress.visibility = View.VISIBLE
        val ok = withContext(Dispatchers.IO) {
            try {
                val r = AngConfigManager.updateConfigViaSub(sub)
                fetchUsage(sub.subscription.url)
                r.successCount > 0
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Cyperio: refresh failed", e)
                false
            }
        }
        progress.visibility = View.GONE
        fillCountries()
        showUsage()
        if (announce) hintText.setText(if (ok) R.string.cyperio_refreshed else R.string.cyperio_fetch_failed)
    }

    // ---------------------------------------------------------------- connect / disconnect
    private fun toggle() {
        if (busy) return
        if (state == State.OFF) {
            val intent = VpnService.prepare(this)
            if (intent == null) lifecycleScope.launch { connect() } else requestVpnPermission.launch(intent)
        } else {
            CoreServiceManager.stopVService(this)
        }
    }

    private fun onCountryPicked(position: Int) {
        val picked = countries.getOrNull(position) ?: return
        if (picked == MmkvManager.decodeSettingsString(KEY_COUNTRY, "").orEmpty()) return
        MmkvManager.encodeSettings(KEY_COUNTRY, picked)
        showCountry()
        if (state == State.ON && !busy) lifecycleScope.launch { connect() }
    }

    /** TCP connect time to every candidate; unreachable ones are dropped, the best one comes first. */
    private suspend fun rank(country: String): List<Candidate> = coroutineScope {
        // "automatic" never picks the Iranian-IP exit: that one is only for whoever asks for it by name
        val all = candidates()
        val pool = all.filter { if (country.isEmpty()) it.country != "Iran" else it.country == country }.ifEmpty { all }
        pool.map { c ->
            async(Dispatchers.IO) {
                c.latencyMs = try {
                    val t0 = System.currentTimeMillis()
                    Socket().use { it.connect(InetSocketAddress(c.host, c.port), 2500) }
                    max(1L, System.currentTimeMillis() - t0)
                } catch (_: Exception) {
                    -1
                }
            }
        }.awaitAll()
        // Latency in 100 ms steps: entries that answer about equally fast count as equal, and then
        // the subscription order decides (the panel lists the entry with the faster line abroad first;
        // sortedBy is stable and `pool` is in subscription order). From Iran the exit country matters
        // more than a few ms to the entry server.
        // exit countries, best first: the panel's order (X-Cyperio-Prefer) if it sent one, else the built-in one
        val order = MmkvManager.decodeSettingsString(KEY_PREFER, "").orEmpty().split(",").map { it.trim() }
            .filter { it.isNotEmpty() }.ifEmpty { IRAN_PREFERENCE }
        // direct servers (customers outside Iran): the panel's order, unless a preferred one answers ~100 ms slower
        val outOrder = MmkvManager.decodeSettingsString(KEY_PREFER_OUT, "").orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        pool.filter { it.latencyMs > 0 }.sortedBy { c ->
            val pref = order.indexOf(c.country).let { if (it < 0) order.size else it }
            if (!c.viaIran && country.isEmpty() && outOrder.isNotEmpty()) {
                c.latencyMs / 100 * 100 + outOrder.indexOf(c.country).let { if (it < 0) outOrder.size else it } * 50
            } else {
                c.latencyMs / 100 * 100 + if (c.viaIran && country.isEmpty()) pref * 400 else 0
            }
        }
    }

    private suspend fun waitRunning(want: Boolean, timeoutMs: Long): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if ((mainViewModel.isRunning.value == true) == want) return true
            delay(200)
            waited += 200
        }
        return false
    }

    /** True if a request through the tunnel's local proxy reaches the internet. */
    private suspend fun tunnelWorks(): Boolean = withContext(Dispatchers.IO) {
        try {
            HttpUtil.getUrlContentWithUserAgent(
                UrlContentRequest(
                    url = "https://www.gstatic.com/generate_204",
                    timeout = 8000,
                    httpPort = SettingsManager.getHttpPort(),
                    proxyUsername = SettingsManager.getSocksUsername(),
                    proxyPassword = SettingsManager.getSocksPassword()
                )
            )
            true
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Cyperio: tunnel test failed: ${e.message}")
            false
        }
    }

    private suspend fun connect() {
        if (busy) return
        busy = true
        state = State.CONNECTING
        render()
        var connected = false
        try {
            if (mainViewModel.isRunning.value == true) {
                CoreServiceManager.stopVService(this)
                waitRunning(false, 5000)
            }
            if (candidates().isEmpty() || blockedText() != null) refresh(announce = false)   // maybe it was renewed a minute ago
            if (blockedText() != null) {
                busy = false
                state = State.OFF
                render()
                return
            }
            val ranked = rank(MmkvManager.decodeSettingsString(KEY_COUNTRY, "").orEmpty())
            for (c in ranked.take(4)) {
                hintText.text = getString(R.string.cyperio_trying, countryName(c.country))
                // Inside Iran (an "Iran to ..." server) Iranian sites go direct. Outside Iran everything goes
                // through the tunnel: with the Iranian-IP server a .ir bank must see the Iranian address.
                SettingsManager.resetRoutingRulesetsFromPresets(this, if (c.viaIran) 3 else 2)
                CoreServiceManager.startVService(this, c.guid)
                if (waitRunning(true, 10000) && tunnelWorks()) {
                    connected = true
                    break
                }
                CoreServiceManager.stopVService(this)
                waitRunning(false, 5000)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Cyperio: connect failed", e)
        }
        if (!connected) refresh(announce = false)   // the usual reason: data used up or subscription ended meanwhile
        busy = false
        state = if (connected) State.ON else State.OFF
        render(failed = !connected)
    }

    companion object {
        private const val KEY_COUNTRY = "cyperio_country"
        private const val KEY_USED = "cyperio_used"
        private const val KEY_TOTAL = "cyperio_total"
        private const val KEY_EXPIRE = "cyperio_expire"
        private const val KEY_STATUS = "cyperio_status"
        private const val KEY_CONNECTED_AT = "cyperio_connected_at"
        private const val KEY_LATEST = "cyperio_latest"
        private const val KEY_LATEST_FILE = "cyperio_latest_file"
        private const val KEY_PREFER = "cyperio_prefer"
        private const val KEY_PREFER_OUT = "cyperio_prefer_out"
        private const val KEY_SUPPORT = "cyperio_support"
        private const val KEY_BUY = "cyperio_buy"
        private const val KEY_NOTIF_ASKED = "cyperio_notif_asked"
        private val IRAN_PREFERENCE = listOf("Bulgaria", "Sweden", "France", "Poland")
        private val COUNTRY_KEYS = listOf(
            "Sweden" to Regex("sweden", RegexOption.IGNORE_CASE),
            "France" to Regex("france|finland", RegexOption.IGNORE_CASE),   // old "Finland" label exits in France
            "Poland" to Regex("poland|canada", RegexOption.IGNORE_CASE),    // old "Canada" label exits in Poland
            "USA" to Regex("\\busa?\\b|united states|america", RegexOption.IGNORE_CASE),
            "Bulgaria" to Regex("bulgaria", RegexOption.IGNORE_CASE),
            "Germany" to Regex("germany", RegexOption.IGNORE_CASE),
            "Iran" to Regex("iran", RegexOption.IGNORE_CASE),
        )
    }
}
