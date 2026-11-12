#!/usr/bin/env python3
"""One-off: resources and wiring for the simple home screen (HomeActivity).
Run once from the repo root on top of the branded tree; kept for the record."""
from pathlib import Path

MAIN = Path(__file__).resolve().parent.parent / "V2rayNG" / "app" / "src" / "main"
RES = MAIN / "res"


def write(path, text):
    Path(path).write_text(text, encoding="utf-8", newline="\n")


def add(path, anchor, text, after=True):
    s = Path(path).read_text(encoding="utf-8")
    assert s.count(anchor) == 1, (path, anchor)
    s = s.replace(anchor, anchor + text if after else text + anchor)
    write(path, s)


def swap(path, old, new):
    s = Path(path).read_text(encoding="utf-8")
    assert s.count(old) == 1, (path, old)
    write(path, s.replace(old, new))


def ring(halo, stroke):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item><shape android:shape="oval"><solid android:color="@color/{halo}" /></shape></item>
    <item android:bottom="14dp" android:left="14dp" android:right="14dp" android:top="14dp">
        <shape android:shape="oval">
            <solid android:color="@color/md_theme_surface" />
            <stroke android:width="3dp" android:color="@color/{stroke}" />
        </shape>
    </item>
</layer-list>
'''


write(RES / "drawable" / "bg_cyperio_power_off.xml", ring("cyperio_off_halo", "cyperio_off_ring"))
write(RES / "drawable" / "bg_cyperio_power_on.xml", ring("cyperio_on_halo", "cyperio_on"))
write(RES / "drawable" / "bg_cyperio_power_busy.xml", ring("cyperio_busy_halo", "cyperio_busy"))
write(RES / "drawable" / "bg_cyperio_card.xml", '''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/md_theme_surface" />
    <stroke android:width="1dp" android:color="@color/md_theme_outlineVariant" />
    <corners android:radius="12dp" />
</shape>
''')
write(RES / "drawable" / "ic_cyperio_power.xml", '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="64dp"
    android:height="64dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#00000000"
        android:pathData="M12,3 V12 M7.2,6.3 A7.5,7.5 0 1 0 16.8,6.3"
        android:strokeColor="#FFFFFFFF"
        android:strokeLineCap="round"
        android:strokeWidth="1.8" />
</vector>
''')

add(RES / "values" / "colors.xml", "<resources>", '''
    <color name="cyperio_on">#0F766E</color>
    <color name="cyperio_on_halo">#CCFBF1</color>
    <color name="cyperio_busy">#D97706</color>
    <color name="cyperio_busy_halo">#FEF3C7</color>
    <color name="cyperio_off">#64748B</color>
    <color name="cyperio_off_ring">#CBD5E1</color>
    <color name="cyperio_off_halo">#E2E8F0</color>''')
add(RES / "values-night" / "colors.xml", "<resources>", '''
    <color name="cyperio_on">#2DD4BF</color>
    <color name="cyperio_on_halo">#134E4A</color>
    <color name="cyperio_busy">#FBBF24</color>
    <color name="cyperio_busy_halo">#78350F</color>
    <color name="cyperio_off">#94A3B8</color>
    <color name="cyperio_off_ring">#475569</color>
    <color name="cyperio_off_halo">#1E293B</color>''')

EN = {
    "cyperio_connect": "Connect", "cyperio_connected": "Connected", "cyperio_disconnected": "Disconnected",
    "cyperio_connecting": "Connecting…", "cyperio_tap_to_connect": "Tap the button to connect",
    "cyperio_trying": "Trying %1$s", "cyperio_failed": "Could not connect",
    "cyperio_failed_hint": "Check your internet and try again", "cyperio_auto": "Best server (automatic)",
    "cyperio_time_left": "Time left", "cyperio_data_left": "Data left", "cyperio_unlimited": "Unlimited",
    "cyperio_expired": "Expired", "cyperio_days": "%1$d days", "cyperio_gb": "%1$s GB",
    "cyperio_paste": "Paste from clipboard",
    "cyperio_bad_link": "That is not a subscription link (it starts with https).",
    "cyperio_checking": "Checking the link…",
    "cyperio_fetch_failed": "Could not load the servers. Check your internet and the link.",
    "cyperio_refreshed": "Servers refreshed", "cyperio_menu_refresh": "Refresh servers",
    "cyperio_menu_change_link": "Change subscription link", "cyperio_menu_language": "فارسی / English",
    "cyperio_menu_advanced": "Advanced settings",
    "cyperio_country_sweden": "Sweden", "cyperio_country_france": "France", "cyperio_country_poland": "Poland",
    "cyperio_country_usa": "USA", "cyperio_country_germany": "Germany", "cyperio_country_iran": "Iran",
}
FA = {
    "cyperio_connect": "اتصال", "cyperio_connected": "وصل است", "cyperio_disconnected": "قطع است",
    "cyperio_connecting": "در حال اتصال…", "cyperio_tap_to_connect": "برای اتصال دکمه را بزنید",
    "cyperio_trying": "در حال امتحان %1$s", "cyperio_failed": "اتصال برقرار نشد",
    "cyperio_failed_hint": "اینترنت را بررسی کنید و دوباره امتحان کنید", "cyperio_auto": "بهترین سرور (خودکار)",
    "cyperio_time_left": "اعتبار", "cyperio_data_left": "حجم مانده", "cyperio_unlimited": "نامحدود",
    "cyperio_expired": "تمام شده", "cyperio_days": "%1$d روز", "cyperio_gb": "%1$s گیگ",
    "cyperio_paste": "چسباندن از کلیپ‌بورد",
    "cyperio_bad_link": "این متن لینک اشتراک نیست. لینک با https شروع می‌شود.",
    "cyperio_checking": "در حال بررسی لینک…",
    "cyperio_fetch_failed": "سرورها دریافت نشد. اینترنت و لینک را بررسی کنید.",
    "cyperio_refreshed": "سرورها به‌روز شد", "cyperio_menu_refresh": "به‌روزرسانی سرورها",
    "cyperio_menu_change_link": "تغییر لینک اشتراک", "cyperio_menu_language": "فارسی / English",
    "cyperio_menu_advanced": "تنظیمات پیشرفته",
    "cyperio_country_sweden": "سوئد", "cyperio_country_france": "فرانسه", "cyperio_country_poland": "لهستان",
    "cyperio_country_usa": "آمریکا", "cyperio_country_germany": "آلمان", "cyperio_country_iran": "ایران",
}
for path, d in ((RES / "values" / "strings.xml", EN), (RES / "values-fa" / "strings.xml", FA)):
    add(path, '    <string name="cyperio_activated">',
        "".join(f'    <string name="{k}">{v}</string>\n' for k, v in d.items()), after=False)

# HomeActivity becomes the launcher; MainActivity stays reachable as "advanced settings"
swap(MAIN / "AndroidManifest.xml", '''        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
            <intent-filter>''', '''        <activity
            android:name=".ui.HomeActivity"
            android:exported="true"
            android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
        </activity>
        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:launchMode="singleTask">
            <intent-filter>''')
SRC = MAIN / "java" / "com" / "v2ray" / "ang"
swap(SRC / "handler" / "NotificationManager.kt",
     "val startMainIntent = Intent(service, MainActivity::class.java)",
     "val startMainIntent = Intent(service, com.v2ray.ang.ui.HomeActivity::class.java)")
add(SRC / "handler" / "SettingsManager.kt", "        ensureDefaultValue(AppConfig.PREF_MODE, VPN)\n",
    '        ensureDefaultValue(AppConfig.PREF_LANGUAGE, "fa")   // Cyperio: Persian by default (switchable from the home menu)\n')
swap(MAIN.parent.parent / "build.gradle.kts", 'versionName = "1.0.1"', 'versionName = "1.1.0"')
swap(MAIN.parent.parent / "build.gradle.kts", "versionCode = 737", "versionCode = 738")
print("simple home wired in")
