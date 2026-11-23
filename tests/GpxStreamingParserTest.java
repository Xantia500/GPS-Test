package com.example.gpstest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public final class GpxStreamingParserTest {
    private static int ok;
    private static int ko;

    private static void check(String label, boolean condition) {
        if (condition) {
            ok++;
            System.out.println("  OK   " + label);
        } else {
            ko++;
            System.out.println("  FAIL " + label);
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\" "
                + "xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\" "
                + "xmlns:gpstest=\"https://example.org/gpstest/ext\" version=\"1.1\" creator=\"Test &amp; Co\">"
                + "<metadata><name>Nom metadata (ne doit pas remplacer le nom de trace)</name></metadata>"
                + "<wpt lat=\"48.1\" lon=\"2.1\"><name>Repère</name></wpt>"
                + "<rte><name>Route</name><rtept lat=\"48.2\" lon=\"2.2\"/></rte>"
                + "<trk><name>Randonnée &amp; vélo</name><trkseg>"
                + "<trkpt lat=\"48.0000000\" lon=\"2.0000000\"><ele>-12.5</ele>"
                + "<time>2026-11-22T12:23:45.500+01:00</time>"
                + "<extensions><gpstest:speed>3.25</gpstest:speed><gpstest:course>270.5</gpstest:course>"
                + "<gpstest:accuracy>4.5</gpstest:accuracy></extensions><hdop>0.8</hdop>"
                + "<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>123</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>"
                + "</trkpt><trkpt lat=\"48.0001\" lon=\"2.0001\"/></trkseg>"
                + "<trkseg><trkpt lat=\"48.1\" lon=\"2.1\"/></trkseg></trk></gpx>";

        AtomicInteger progressCalls = new AtomicInteger();
        GpxParser.Result parsed = GpxParser.parse(
                new ByteArrayInputStream(bytes(xml)), 1_000_000,
                (points, read) -> progressCalls.incrementAndGet(), () -> false);
        check("SAX accepte un GPX 1.1 avec namespace par défaut et extensions Garmin", !parsed.failed);
        check("3 points de trace extraits (rtept et wpt non mélangés)", parsed.pointCount() == 3);
        check("un waypoint et un point de route comptés séparément",
                parsed.waypoints == 1 && parsed.routePoints == 1);
        check("métadonnées racine creator/version préservées",
                "1.1".equals(parsed.version) && "Test & Co".equals(parsed.creator));
        check("nom de la première trace extrait (pas celui de metadata)",
                "Randonnée & vélo".equals(parsed.trackName));
        check("deux segments détectés", parsed.segmentCount == 2
                && parsed.points.get(2).segment == 1);
        check("altitude négative conservée", Math.abs(parsed.points.get(0).ele + 12.5) < 0.001);
        check("heure ISO avec fuseau et fraction conservée", parsed.points.get(0).timeIso != null
                && parsed.points.get(0).timeMs == GpxParser.parseIso8601("2026-11-22T12:23:45.500+01:00"));
        check("vitesse/course/HDOP/précision des extensions extraites",
                parsed.points.get(0).speedMps != null && Math.abs(parsed.points.get(0).speedMps - 3.25f) < 0.001f
                        && parsed.points.get(0).courseDeg != null
                        && Math.abs(parsed.points.get(0).courseDeg - 270.5f) < 0.001f
                        && parsed.points.get(0).hdop != null
                        && Math.abs(parsed.points.get(0).hdop - 0.8f) < 0.001f
                        && parsed.points.get(0).accuracyM != null
                        && Math.abs(parsed.points.get(0).accuracyM - 4.5f) < 0.001f);
        check("barre de progression signalée", progressCalls.get() > 0);

        String invalidCoords = "<gpx><trk><trkseg><trkpt lat=\"999\" lon=\"2\"/>"
                + "<trkpt lat=\"0\" lon=\"0\"/><trkpt lat=\"48\" lon=\"2\"/>"
                + "</trkseg></trk></gpx>";
        GpxParser.Result filtered = GpxParser.parse(new ByteArrayInputStream(bytes(invalidCoords)),
                100_000, null, null);
        check("coordonnées invalides ignorées sans rejeter le document", !filtered.failed
                && filtered.pointCount() == 1 && filtered.invalidTrackPoints == 2);

        String malformed = "<gpx><trk><trkseg><trkpt lat=\"48\" lon=\"2\"/></trkseg></trk>";
        GpxParser.Result broken = GpxParser.parse(new ByteArrayInputStream(bytes(malformed)),
                100_000, null, null);
        check("XML tronqué signalé comme erreur complète", broken.failed && broken.error != null);
        check("le point complet précédant la troncature reste diagnostiquable",
                broken.pointCount() == 1);

        String doctype = "<!DOCTYPE gpx [<!ENTITY leak SYSTEM \"file:///etc/passwd\">]>"
                + "<gpx><trk><trkseg><trkpt lat=\"48\" lon=\"2\"><name>&leak;</name>"
                + "</trkpt></trkseg></trk></gpx>";
        GpxParser.Result blocked = GpxParser.parse(new ByteArrayInputStream(bytes(doctype)),
                100_000, null, null);
        check("DOCTYPE et entités externes refusés", blocked.failed && blocked.error != null
                && blocked.error.toLowerCase().contains("doctype"));

        AtomicInteger cancellationChecks = new AtomicInteger();
        GpxParser.Result cancelled = GpxParser.parse(new ByteArrayInputStream(bytes(xml)),
                1_000_000, null, () -> cancellationChecks.incrementAndGet() >= 3);
        check("annulation consultée pendant le parsing", cancelled.cancelled && cancelled.failed);

        GpxParser.Result tooLarge = GpxParser.parse(new ByteArrayInputStream(bytes(xml)),
                32, null, null);
        check("limite stricte sur les octets réellement lus", tooLarge.failed
                && tooLarge.error != null && tooLarge.error.contains("volumineux"));

        String unboundPrefix = "<gpx><trk><trkseg><trkpt lat=\"48\" lon=\"2\">"
                + "<extensions><gpstest:speed>1</gpstest:speed></extensions>"
                + "</trkpt></trkseg></trk></gpx>";
        GpxParser.Result unbound = GpxParser.parse(unboundPrefix);
        check("préfixe XML non déclaré rejeté (document mal formé)", unbound.failed);

        String utf16 = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>"
                + "<gpx><trk><trkseg><trkpt lat=\"48\" lon=\"2\"/></trkseg></trk></gpx>";
        byte[] utf16Bytes = utf16.getBytes(Charset.forName("UTF-16"));
        GpxParser.Result utf16Parsed = GpxParser.parse(new ByteArrayInputStream(utf16Bytes),
                100_000, null, null);
        check("encodage XML UTF-16 détecté par le parseur SAX", !utf16Parsed.failed
                && utf16Parsed.pointCount() == 1);

        StringBuilder many = new StringBuilder(100_000);
        many.append("<gpx version=\"1.1\"><trk><trkseg>");
        for (int i = 0; i < 1_200; i++) {
            many.append("<trkpt lat=\"48.").append(i % 10000)
                    .append("\" lon=\"2.").append(i % 10000).append("\"/>");
        }
        many.append("</trkseg></trk></gpx>");
        AtomicInteger manyProgress = new AtomicInteger();
        GpxParser.Result manyParsed = GpxParser.parse(
                new ByteArrayInputStream(bytes(many.toString())), 1_000_000,
                (points, read) -> manyProgress.incrementAndGet(), () -> false);
        check("1200 points analysés en flux sans perte", !manyParsed.failed
                && manyParsed.pointCount() == 1_200);
        check("progression émise à plusieurs étapes pendant une longue lecture",
                manyProgress.get() >= 3);

        String tooDeep = "<gpx>" + "<x>".repeat(GpxParser.MAX_XML_DEPTH + 1)
                + "</x>".repeat(GpxParser.MAX_XML_DEPTH + 1) + "</gpx>";
        GpxParser.Result deep = GpxParser.parse(tooDeep);
        check("profondeur XML excessive refusée", deep.failed
                && deep.error != null && deep.error.contains("imbriqué"));

        System.out.println("GpxStreamingParserTest : " + ok + " réussis, " + ko + " échoués");
        if (ko != 0) throw new AssertionError("GpxStreamingParserTest failed");
    }
}
