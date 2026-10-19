package dev.pacer.core

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** GPX 1.1 writer. Each pause starts a new <trkseg> so Strava doesn't count the gap as moving. */
object Gpx {
    fun write(name: String, description: String, points: List<TrackPoint>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Pacer" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        points.firstOrNull()?.let {
            append("  <metadata><time>").append(time(it.wallTimeMs)).append("</time></metadata>\n")
        }
        append("  <trk>\n")
        append("    <name>").append(escape(name)).append("</name>\n")
        if (description.isNotEmpty()) append("    <desc>").append(escape(description)).append("</desc>\n")
        append("    <type>running</type>\n")
        if (points.isNotEmpty()) {
            // Strava wants elevation on every point; carry the last known value over fixes without one.
            var ele = points.firstNotNullOfOrNull { it.altitudeM }
            append("    <trkseg>\n")
            points.forEachIndexed { i, p ->
                if (i > 0 && p.segmentStart) append("    </trkseg>\n    <trkseg>\n")
                append("      <trkpt lat=\"").append("%.7f".format(Locale.ROOT, p.lat))
                append("\" lon=\"").append("%.7f".format(Locale.ROOT, p.lon)).append("\">")
                ele = p.altitudeM ?: ele
                ele?.let { append("<ele>").append("%.1f".format(Locale.ROOT, it)).append("</ele>") }
                append("<time>").append(time(p.wallTimeMs)).append("</time></trkpt>\n")
            }
            append("    </trkseg>\n")
        }
        append("  </trk>\n</gpx>\n")
    }

    private fun time(ms: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms))

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
