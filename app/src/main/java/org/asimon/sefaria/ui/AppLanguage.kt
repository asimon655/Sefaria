package org.asimon.sefaria.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The in-app interface language. "Device" follows the system.
 *
 * Android 13+: the system's per-app language (LocaleManager), so the choice also shows in
 * system settings and the system recreates the activity. Older versions: the choice is kept in
 * SharedPreferences (read synchronously before the first frame) and applied in
 * [wrap] from Activity.attachBaseContext. KISS alternative to AppCompat's
 * setApplicationLocales, which would need AppCompatActivity and its theme.
 */
enum class AppLanguage(val tag: String) {
    Device(""), English("en"), Hebrew("iw");

    companion object {
        private const val PREFS = "app_language"
        private const val KEY = "tag"

        fun current(context: Context): AppLanguage {
            val tag = if (Build.VERSION.SDK_INT >= 33) {
                context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
            } else {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            }
            return fromTag(tag)
        }

        fun set(activity: Activity, lang: AppLanguage) {
            if (Build.VERSION.SDK_INT >= 33) {
                activity.getSystemService(LocaleManager::class.java).applicationLocales =
                    LocaleList.forLanguageTags(lang.tag) // the system recreates the activity
            } else {
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang.tag).commit()
                activity.recreate()
            }
        }

        /** Applies the stored language below Android 13; call from attachBaseContext. */
        fun wrap(base: Context): Context {
            if (Build.VERSION.SDK_INT >= 33) return base
            val lang = current(base)
            if (lang == Device) return base
            val config = Configuration(base.resources.configuration)
            config.setLocale(Locale.forLanguageTag(lang.tag)) // also sets the layout direction
            return base.createConfigurationContext(config)
        }

        private fun fromTag(tag: String): AppLanguage {
            val lang = tag.substringBefore(',').substringBefore('-')
            return when (lang) {
                "" -> Device
                "iw", "he" -> Hebrew
                "en" -> English
                else -> Device
            }
        }
    }
}
