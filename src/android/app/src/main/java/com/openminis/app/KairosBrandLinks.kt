package com.openminis.app

/**
 * [Minis Next brand links] Single source of truth for the contact / community
 * entry points shown in About, the Feedback sheet and crash-report mail.
 *
 * Minis Next builds are an independent fork: every user-facing contact link
 * points at the Minis Next repository and maintainer, while
 * [UPSTREAM_REPOSITORY_URL] / [UPSTREAM_ISSUES_URL] keep the original
 * OpenMinis project credited.
 */
object KairosBrandLinks {
    const val MAINTAINER: String = "Minis Next"

    const val REPOSITORY_URL: String = "https://github.com/liyongshun114514/Minis-Next"
    const val ISSUES_URL: String = "https://github.com/liyongshun114514/Minis-Next/issues"
    const val NEW_ISSUE_URL: String = "https://github.com/liyongshun114514/Minis-Next/issues/new"
    const val RELEASES_URL: String = "https://github.com/liyongshun114514/Minis-Next/releases"

    const val EMAIL: String = "liyongshun114514@gmail.com"
    const val TELEGRAM_URL: String = "https://t.me/LYS114514"

    /** Original project — attribution must stay visible. */
    const val UPSTREAM_REPOSITORY_URL: String = "https://github.com/OpenMinis/OpenMinis"
    const val UPSTREAM_ISSUES_URL: String = "https://github.com/OpenMinis/OpenMinis/issues"
}
