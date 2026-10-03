package com.dilinkauto.protocol

/**
 * Cross-module SharedPreferences contract.
 *
 * Holds the shared prefs file name and the keys that are read/written from
 * more than one file. A typo in a key string silently breaks the link
 * between writer and reader (e.g. IME restore reading a key that was never
 * written); collecting them here makes the contract explicit.
 *
 * Module-local keys that never cross a file boundary stay where they are.
 */
object AppPrefs {
    /** Shared prefs file name used by both the phone and car apps. */
    const val FILE_NAME = "dilinkauto"

    // Phone-side IME save/restore (ConnectionService writes, PhoneDisplayRestorer reads)
    const val SAVED_DEFAULT_IME = "saved_default_ime"

    // Phone-side log toggle (SettingsScreen toggles, FileLog + ConnectionService read)
    const val LOG_ENABLED = "log_enabled"
    const val LOG_ENABLED_USER_SET = "log_enabled_user_set"
}
