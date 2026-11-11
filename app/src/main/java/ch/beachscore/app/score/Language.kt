package ch.beachscore.app.score

/** UI and speech language. [tag] is the BCP 47 language tag used for resources and TTS. */
enum class Language(val tag: String) {
    DE("de"),
    EN("en");

    companion object {
        /** Spec: default to the device language if it is German, otherwise English. */
        fun defaultFor(deviceLanguage: String): Language =
            if (deviceLanguage.equals(DE.tag, ignoreCase = true)) DE else EN

        fun fromTag(tag: String?): Language? = entries.firstOrNull { it.tag == tag }
    }
}
