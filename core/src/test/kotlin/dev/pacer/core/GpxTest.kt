package dev.pacer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxTest {
    private fun pt(t: Long, alt: Double?, seg: Boolean = false) =
        TrackPoint(1_700_000_000_000 + t, 37.5, -122.25, alt, 5f, 0.0, t, seg)

    @Test fun writesValidGpxWithSegmentsAndElevation() {
        val gpx = Gpx.write("Run/Walk 8x1:00 <&>", "desc", listOf(pt(0, 10.0, true), pt(1000, null), pt(60_000, 12.0, true)))
        assertTrue(gpx.startsWith("<?xml"))
        assertTrue(gpx.contains("<name>Run/Walk 8x1:00 &lt;&amp;&gt;</name>"))
        assertEquals(2, Regex("<trkseg>").findAll(gpx).count())
        assertEquals(3, Regex("<ele>").findAll(gpx).count())
        assertTrue(gpx.contains("<trkpt lat=\"37.5000000\" lon=\"-122.2500000\"><ele>10.0</ele><time>2023-11-14T22:13:21Z</time></trkpt>"))
        // Parses as XML.
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(gpx.byteInputStream())
    }

    @Test fun emptyTrackIsStillValid() {
        val gpx = Gpx.write("x", "", emptyList())
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(gpx.byteInputStream())
    }
}
