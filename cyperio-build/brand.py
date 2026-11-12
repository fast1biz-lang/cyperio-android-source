#!/usr/bin/env python3
"""Apply the Cyperio branding to a clean v2rayNG checkout (idempotent: every
edit asserts that it found its anchor, so an upstream change fails loudly).

Run from the repo root:  python cyperio-build/brand.py
"""
import glob
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "V2rayNG" / "app"
MAIN = APP / "src" / "main"
SRC = MAIN / "java" / "com" / "v2ray" / "ang"
RES = MAIN / "res"

APP_NAME = "Cyperio"
APP_ID = "com.cyperio.app"
VERSION = "1.0.0"
SOURCE_URL = "https://github.com/fast1biz-lang/cyperio-android"


def edit(path, pairs):
    p = Path(path)
    s = p.read_text(encoding="utf-8")
    for old, new in pairs:
        if new in s and old not in s:
            continue   # already applied
        if s.count(old) != 1:
            sys.exit(f"{p}: expected exactly one match for:\n{old}")
        s = s.replace(old, new)
    p.write_text(s, encoding="utf-8", newline="\n")


def write(path, text):
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8", newline="\n")


# ---------------------------------------------------------------- gradle
edit(APP / "build.gradle.kts", [
    ('applicationId = "com.v2ray.ang"', f'applicationId = "{APP_ID}"'),
    ('versionName = "2.2.6"', f'versionName = "{VERSION}"'),
    ('"v2rayNG_${variant.versionName}-fdroid_${abi}.apk"', f'"{APP_NAME}_${{variant.versionName}}-fdroid_${{abi}}.apk"'),
    ('"v2rayNG_${variant.versionName}_${abi}.apk"', f'"{APP_NAME}_${{variant.versionName}}_${{abi}}.apk"'),
])

# ---------------------------------------------------------------- names / links
edit(RES / "values" / "strings.xml", [
    ('<string name="app_name" translatable="false">v2rayNG</string>',
     f'<string name="app_name" translatable="false">{APP_NAME}</string>\n'
     '    <string name="cyperio_welcome_title">Welcome to Cyperio</string>\n'
     '    <string name="cyperio_welcome_message">Paste your subscription link, or scan the QR code you received.</string>\n'
     '    <string name="cyperio_welcome_hint">Subscription link</string>\n'
     '    <string name="cyperio_welcome_activate">Activate</string>\n'
     '    <string name="cyperio_welcome_scan">Scan QR</string>\n'
     '    <string name="cyperio_welcome_later">Later</string>\n'
     '    <string name="cyperio_welcome_empty">Please enter your subscription link</string>\n'
     '    <string name="cyperio_activated">Activated. Tap the button below to connect.</string>'),
])
edit(RES / "values-fa" / "strings.xml", [
    ('<resources>',
     '<resources>\n'
     '    <string name="cyperio_welcome_title">به Cyperio خوش آمدید</string>\n'
     '    <string name="cyperio_welcome_message">لینک اشتراک خود را اینجا بچسبانید یا کد QR دریافتی را اسکن کنید.</string>\n'
     '    <string name="cyperio_welcome_hint">لینک اشتراک</string>\n'
     '    <string name="cyperio_welcome_activate">فعال‌سازی</string>\n'
     '    <string name="cyperio_welcome_scan">اسکن QR</string>\n'
     '    <string name="cyperio_welcome_later">بعداً</string>\n'
     '    <string name="cyperio_welcome_empty">لطفاً لینک اشتراک را وارد کنید</string>\n'
     '    <string name="cyperio_activated">فعال شد. برای اتصال دکمهٔ پایین را بزنید.</string>'),
])
edit(SRC / "AppConfig.kt", [
    ('const val APP_URL = "$GITHUB_URL/2dust/v2rayNG"',
     f'const val APP_URL = "{SOURCE_URL}"   // Cyperio: source of this fork (GPL-3.0)\n'
     '    const val UPSTREAM_URL = "$GITHUB_URL/2dust/v2rayNG"'),
    ('const val APP_ISSUES_URL = "$APP_URL/issues"', 'const val APP_ISSUES_URL = "$UPSTREAM_URL/issues"'),
    ('const val APP_WIKI_MODE = "$APP_URL/wiki/Mode"', 'const val APP_WIKI_MODE = "$UPSTREAM_URL/wiki/Mode"'),
])

# deep-link scheme: don't hijack v2rayng:// links from the original app
edit(MAIN / "AndroidManifest.xml", [('<data android:scheme="v2rayng" />', '<data android:scheme="cyperio" />')])

# ---------------------------------------------------------------- drawer menu
edit(RES / "menu" / "menu_drawer.xml", [
    ('''        <item
            android:id="@+id/promotion"
            android:icon="@drawable/ic_promotion_24dp"
            android:title="@string/title_pref_promotion" />
''', ''),
    ('''        <item
            android:id="@+id/check_for_update"
            android:icon="@drawable/ic_check_update_24dp"
            android:title="@string/update_check_for_update" />
''', ''),
])

# ---------------------------------------------------------------- MainActivity
edit(SRC / "ui" / "MainActivity.kt", [
    ('            R.id.promotion -> Utils.openUri(this, "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}")\n', ''),
    ('            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))\n', ''),
    ('''        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }
''', '''        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
        showWelcomeIfEmpty()
    }

    /**
     * Cyperio: first run (nothing imported yet) -> ask for the subscription link or its QR code.
     */
    private fun showWelcomeIfEmpty() {
        val hasSub = MmkvManager.decodeSubscriptions().any { it.subscription.url.isNotBlank() }
        if (hasSub || MmkvManager.decodeAllServerList().isNotEmpty()) {
            return
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.cyperio_welcome_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            maxLines = 3
        }
        val box = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.cyperio_welcome_title)
            .setMessage(R.string.cyperio_welcome_message)
            .setView(box)
            .setPositiveButton(R.string.cyperio_welcome_activate, null)
            .setNeutralButton(R.string.cyperio_welcome_scan) { _, _ -> importQRcode() }
            .setNegativeButton(R.string.cyperio_welcome_later, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val link = input.text.toString().trim()
                if (link.isEmpty()) {
                    toast(R.string.cyperio_welcome_empty)
                } else {
                    dialog.dismiss()
                    importBatchConfig(link)
                }
            }
        }
        dialog.show()
    }
'''),
    ('''                        countSub > 0 -> setupGroupTab()
''', '''                        countSub > 0 -> {
                            setupGroupTab()
                            mainViewModel.reloadServerList()
                            SubscriptionUpdater.sync()
                            toast(R.string.cyperio_activated)
                        }
'''),
])

# ---------------------------------------------------------------- defaults
edit(SRC / "handler" / "AngConfigManager.kt", [
    ('''        subItem.remarks = uri.fragment ?: "import sub"
        subItem.url = url
''', '''        subItem.remarks = uri.fragment ?: "Cyperio"
        subItem.url = url
        // Cyperio: keep the server list fresh without the customer doing anything
        subItem.autoUpdate = true
        subItem.updateInterval = 180
'''),
])
edit(SRC / "handler" / "SettingsManager.kt", [
    ('            val rulesetList = getPresetRoutingRulesets(context)\n',
     '            // Cyperio: default to the Iran preset (Iranian sites and IPs go direct)\n'
     '            val rulesetList = getPresetRoutingRulesets(context, 3)\n'),
])

# ---------------------------------------------------------------- about screen
edit(SRC / "ui" / "AboutActivity.kt", [
    ('''        binding.layoutFeedback.setOnClickListener {
            Utils.openUri(this, AppConfig.APP_ISSUES_URL)
        }
''', '''        // Cyperio: upstream support channels are not ours
        binding.layoutFeedback.visibility = android.view.View.GONE
        binding.layoutTgChannel.visibility = android.view.View.GONE
        binding.layoutPrivacyPolicy.visibility = android.view.View.GONE
'''),
])

# ---------------------------------------------------------------- colours
TEAL, TEAL_DARK, NAVY = "#14B8A6", "#0F766E", "#0F172A"
edit(RES / "values" / "colors.xml", [
    ('<color name="color_fab_active">#f97910</color>', f'<color name="color_fab_active">{TEAL}</color>'),
    ('<color name="colorConfigType">#f97910</color>', f'<color name="colorConfigType">{TEAL_DARK}</color>'),
    ('<color name="md_theme_primary">#000000</color>', f'<color name="md_theme_primary">{NAVY}</color>'),
    ('<color name="md_theme_secondary">#f97910</color>', f'<color name="md_theme_secondary">{TEAL_DARK}</color>'),
    ('<color name="md_theme_secondaryContainer">#FFE8D6</color>', '<color name="md_theme_secondaryContainer">#CCFBF1</color>'),
    ('<color name="md_theme_onSecondaryContainer">#2B1700</color>', '<color name="md_theme_onSecondaryContainer">#042F2E</color>'),
])
edit(RES / "values-night" / "colors.xml", [
    ('<color name="color_fab_active">#f97910</color>', f'<color name="color_fab_active">{TEAL}</color>'),
    ('<color name="md_theme_secondary">#FFB687</color>', '<color name="md_theme_secondary">#5EEAD4</color>'),
    ('<color name="md_theme_onSecondary">#4E2600</color>', '<color name="md_theme_onSecondary">#042F2E</color>'),
    ('<color name="md_theme_secondaryContainer">#6F3800</color>', '<color name="md_theme_secondaryContainer">#115E59</color>'),
    ('<color name="md_theme_onSecondaryContainer">#FFE8D6</color>', '<color name="md_theme_onSecondaryContainer">#CCFBF1</color>'),
])

# ---------------------------------------------------------------- icons (all vector)
for pat in ("mipmap-*dpi/ic_launcher*.png", "mipmap-*dpi/ic_banner*.png", "mipmap-*dpi/ic_launcher*.webp",
            "drawable-*dpi/ic_stat_name.png", "drawable/nav_header_bg.png"):
    for f in glob.glob(str(RES / pat)):
        if "anydpi" not in f:
            os.remove(f)

SHIELD = "M54,22 L80,32 V55 C80,71 69,82 54,88 C39,82 28,71 28,55 V32 Z"
C_ARC = "M63,45 A12.5,12.5 0 1 0 63,65"


def vector(shield, arc, size=108):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size}dp"
    android:height="{size}dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="{shield}"
        android:pathData="{SHIELD}" />
    <path
        android:fillColor="#00000000"
        android:pathData="{C_ARC}"
        android:strokeColor="{arc}"
        android:strokeLineCap="round"
        android:strokeWidth="6.5" />
</vector>
'''


write(RES / "drawable" / "ic_cyperio_foreground.xml", vector("#FFFFFF", TEAL_DARK))
# notification / quick-settings icon: single colour, the C is cut out of the shield
write(RES / "drawable" / "ic_stat_name.xml", f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFF"
        android:fillType="evenOdd"
        android:pathData="M54,8 L92,22 V55 C92,78 76,94 54,102 C32,94 16,78 16,55 V22 Z M70,38 A22,22 0 1 0 70,72 L63,64 A12,12 0 1 1 63,46 Z" />
</vector>
''')
write(RES / "drawable" / "ic_cyperio_background.xml", f'''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient
        android:angle="315"
        android:endColor="{TEAL_DARK}"
        android:startColor="{NAVY}" />
</shape>
''')
write(RES / "drawable" / "nav_header_bg.xml", f'''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient
        android:angle="315"
        android:endColor="{TEAL}"
        android:startColor="{NAVY}" />
</shape>
''')
ADAPTIVE = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_cyperio_background" />
    <foreground android:drawable="@drawable/ic_cyperio_foreground" />
    <monochrome android:drawable="@drawable/ic_cyperio_foreground" />
</adaptive-icon>
'''
for n in ("ic_launcher", "ic_launcher_round", "ic_banner"):
    write(RES / "mipmap-anydpi-v26" / f"{n}.xml", ADAPTIVE)
# Android 7 (no adaptive icons): rounded gradient tile with the same mark
LEGACY = f'''<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item>
        <shape android:shape="rectangle">
            <corners android:radius="12dp" />
            <gradient
                android:angle="315"
                android:endColor="{TEAL_DARK}"
                android:startColor="{NAVY}" />
        </shape>
    </item>
    <item android:drawable="@drawable/ic_cyperio_foreground" />
</layer-list>
'''
for n in ("ic_launcher", "ic_launcher_round", "ic_banner"):
    write(RES / "mipmap-anydpi" / f"{n}.xml", LEGACY)

# white title on the gradient drawer header
edit(RES / "layout" / "nav_header.xml", [
    ('''        android:text="@string/app_name"
''', '''        android:text="@string/app_name"
        android:textColor="#FFFFFF"
'''),
])
print("Cyperio branding applied")
