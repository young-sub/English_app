package com.example.bookhelper.tts

enum class TtsEnginePreference {
    SYSTEM_DEFAULT,
    GOOGLE,
    SAMSUNG,
    ON_DEVICE,
    ;

    companion object {
        fun fromStored(value: String?): TtsEnginePreference {
            return entries.firstOrNull { it.name == value } ?: SYSTEM_DEFAULT
        }
    }
}

class TtsEngineResolver {
    fun resolve(
        preference: TtsEnginePreference,
        installedEngines: Set<String>,
        defaultEngine: String?,
    ): String? {
        return when (preference) {
            TtsEnginePreference.SYSTEM_DEFAULT -> defaultEngine
            TtsEnginePreference.GOOGLE -> {
                if (GOOGLE_ENGINE in installedEngines) GOOGLE_ENGINE else defaultEngine
            }

            TtsEnginePreference.SAMSUNG -> {
                if (SAMSUNG_ENGINE in installedEngines) SAMSUNG_ENGINE else defaultEngine
            }

            TtsEnginePreference.ON_DEVICE -> defaultEngine
        }
    }

    private companion object {
        const val GOOGLE_ENGINE = "com.google.android.tts"
        const val SAMSUNG_ENGINE = "com.samsung.SMT"
    }
}

fun TtsEnginePreference.requestsOnDeviceTts(): Boolean {
    return this == TtsEnginePreference.ON_DEVICE
}
