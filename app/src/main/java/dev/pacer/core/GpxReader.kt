package dev.pacer.core

import java.time.Instant

/** Minimal GPX reader for the debug mock-location replay: track points with optional ele/time. */
object GpxReader {
    data class Pt(val lat: Double, val lon: Double, val ele: Double?, val timeMs: Long?)

    private val trkpt = Regex("""<trkpt\b([^>]*?)(?:/>|>(.*?)</trkpt>)""", RegexOption.DOT_MATCHES_ALL)
    private val latRe = Regex("""lat\s*=\s*["']([-0-9.eE+]+)["']""")
    private val lonRe = Regex("""lon\s*=\s*["']([-0-9.eE+]+)["']""")
    private val eleRe = Regex("""<ele>\s*([-0-9.eE+]+)\s*</ele>""")
    private val timeRe = Regex("""<time>\s*([^<]+?)\s*</time>""")

    fun parse(xml: String): List<Pt> = trkpt.findAll(xml).mapNotNull { m ->
        val attrs = m.groupValues[1]
        val body = m.groupValues[2]
        val lat = latRe.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
        val lon = lonRe.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
        Pt(
            lat, lon,
            eleRe.find(body)?.groupValues?.get(1)?.toDoubleOrNull(),
            timeRe.find(body)?.groupValues?.get(1)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
        )
    }.toList()
}
