package com.digitalsignage.player.domain

import kotlin.math.round

/** Snap display rotation to 90° steps (matches web `displayRotation.js`). */
fun normalizeDisplayRotationQuadrant(deg: Int): Int {
    val n = (round(deg / 90.0).toInt() * 90) % 360
    return if (n < 0) n + 360 else n
}

fun advanceDisplayRotationQuadrant(prev: Int): Int =
    (normalizeDisplayRotationQuadrant(prev) + 90) % 360

fun displayRotationForOrientation(orientation: String?): Int =
    if (orientation?.lowercase() == "portrait") 90 else 0
