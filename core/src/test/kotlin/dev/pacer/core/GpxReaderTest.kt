package dev.pacer.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GpxReaderTest {
    @Test fun readsWhatTheWriterWrites() {
        val pts = listOf(
            TrackPoint(1_700_000_000_000, 37.5, -122.25, 10.0, 5f, 0.0, 0, true),
            TrackPoint(1_700_000_001_000, 37.50001, -122.25001, null, 5f, 1.0, 1000, false),
        )
        val back = GpxReader.parse(Gpx.write("x", "", pts))
        assertEquals(2, back.size)
        assertEquals(37.50001, back[1].lat, 1e-9)
        assertEquals(10.0, back[1].ele!!, 1e-9) // carried forward by the writer
        assertEquals(1_700_000_001_000, back[1].timeMs)
    }

    @Test fun toleratesOtherAppsFormatting() {
        val xml = """<gpx><trk><trkseg><trkpt lon='-1.5' lat='50.25'/><trkpt lat="50.3" lon="-1.6"><time>2024-05-01T10:00:00.500Z</time></trkpt></trkseg></trk></gpx>"""
        val p = GpxReader.parse(xml)
        assertEquals(2, p.size)
        assertEquals(50.25, p[0].lat, 0.0)
        assertEquals(null, p[0].timeMs)
        assertEquals(1714557600500, p[1].timeMs)
    }
}
