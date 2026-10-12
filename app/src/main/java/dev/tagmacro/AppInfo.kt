package dev.tagmacro

/**
 * Links shown on the About card in Settings. Fill these in before publishing.
 * A link left empty is hidden, so the app never shows a broken button.
 */
object AppInfo {
    /** Your donation page, e.g. a Liberapay, Ko-fi, GitHub Sponsors or Open Collective link. */
    const val DONATE_URL = "https://ko-fi.com/unpiloted0852"

    /** Where the source code lives, e.g. https://codeberg.org/you/tagmacro */
    const val SOURCE_URL = "https://github.com/Unpiloted0852/TagMacro"

    /** Where people report bugs, e.g. https://codeberg.org/you/tagmacro/issues */
    const val ISSUES_URL = "https://github.com/Unpiloted0852/TagMacro/issues"

    /** The licence name shown in the app, e.g. "GPL-3.0-or-later". Must match your LICENSE file. */
    const val LICENSE = "GPL-3.0-or-later"
}
