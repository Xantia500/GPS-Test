package com.example.gpstest;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public final class GpxPreservationTest {
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

    public static void main(String[] args) throws Exception {
        String source = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\" "
                + "xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\" "
                + "xmlns:vendor=\"https://vendor.example/gpx/ext\" "
                + "xmlns:rootvendor=\"https://root.example/ns\" "
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                + "rootvendor:source=\"original\" "
                + "xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 https://schema.example/custom-gpx.xsd\" "
                + "version=\"1.1\" creator=\"Recorder &amp; Co\">"
                + "<metadata><name>Document title</name><desc>Metadata description</desc>"
                + "<author><name>Author</name><email id=\"a\" domain=\"example.org\"/></author>"
                + "<link href=\"https://example.org/track\"><text>Source link</text></link>"
                + "<keywords>trail,hiking</keywords><bounds minlat=\"48\" minlon=\"2\" maxlat=\"49\" maxlon=\"3\"/>"
                + "<extensions><vendor:documentFlag value=\"keep\"/></extensions></metadata>"
                + "<wpt lat=\"48.0000000001\" lon=\"2.0000000001\"><ele>75.2</ele><name>Parking</name>"
                + "<sym>Parking Area</sym><extensions><vendor:marker code=\"P1\"/></extensions></wpt>"
                + "<rte><name>Approach route</name><rtept lat=\"48.1\" lon=\"2.1\"><name>Start</name></rtept>"
                + "<rtept lat=\"48.2\" lon=\"2.2\"/></rte>"
                + "<trk><name>Morning trail</name><cmt>Original track comment</cmt><src>Watch</src>"
                + "<type>hiking</type><extensions><vendor:trackMeta flag=\"alpha\"/></extensions>"
                + "<trkseg><trkpt lat=\"48.123456789123\" lon=\"2.123456789123\">"
                + "<ele>-12.7500000000</ele><time>2026-11-23T06:30:00.123456Z</time><magvar>1.2</magvar>"
                + "<geoidheight>42.2</geoidheight><name>First point</name><fix>3d</fix><sat>12</sat>"
                + "<hdop>0.7</hdop><vdop>1.1</vdop><pdop>1.3</pdop>"
                + "<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>132</gpxtpx:hr>"
                + "<gpxtpx:cad>80</gpxtpx:cad></gpxtpx:TrackPointExtension>"
                + "<vendor:pointFlag code=\"Q7\"/><local:scoped xmlns:local=\"https://local.example/ns\">scoped</local:scoped>"
                + "</extensions></trkpt>"
                + "<trkpt lat=\"48.123556789123\" lon=\"2.123556789123\"><ele>13.4</ele>"
                + "<time>2026-11-23T06:30:01.123456Z</time><extensions><vendor:quality>good</vendor:quality>"
                + "</extensions></trkpt><extensions><vendor:segmentMeta>seg-A</vendor:segmentMeta></extensions>"
                + "</trkseg></trk>"
                + "<trk><name>Afternoon trail</name><number>2</number><type>cycling</type>"
                + "<extensions><vendor:trackMeta flag=\"beta\"/></extensions>"
                + "<trkseg><trkpt lat=\"47.987654321123\" lon=\"1.987654321123\"><ele>105</ele>"
                + "<time>2026-11-23T14:00:00.987654+01:00</time><extensions>"
                + "<vendor:pointFlag code=\"Z9\"/></extensions></trkpt>"
                + "<trkpt lat=\"47.987754321123\" lon=\"1.987754321123\"><ele>106</ele>"
                + "<time>2026-11-23T14:00:01.987654+01:00</time></trkpt></trkseg></trk>"
                + "<extensions><vendor:rootTail value=\"tail\"/></extensions></gpx>";

        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        GpxPreservation.Document preserved = GpxPreservation.parse(
                new ByteArrayInputStream(bytes), 2_000_000L);
        check("les métadonnées GPX complètes sont capturées", preserved.metadataXml != null
                && preserved.metadataXml.contains("Document title")
                && preserved.metadataXml.contains("documentFlag")
                && preserved.metadataXml.contains("https://example.org/track"));
        check("waypoints et routes sont conservés comme éléments racine",
                preserved.rootElementsXml.size() == 3
                        && preserved.rootElementsXml.get(0).contains("Parking")
                        && preserved.rootElementsXml.get(1).contains("Approach route")
                        && preserved.rootElementsXml.get(2).contains("rootTail"));
        check("deux traces restent séparées", preserved.tracks.size() == 2
                && preserved.tracks.get(0).metadataXml.contains("Morning trail")
                && preserved.tracks.get(1).metadataXml.contains("Afternoon trail"));
        check("métadonnées de segments conservées", preserved.segments.size() == 2
                && preserved.segments.get(0).extraXml.contains("segmentMeta"));
        check("extensions et champs standard de tous les points préservés",
                preserved.points.size() == 4
                        && preserved.points.get(0).childrenXml.contains("TrackPointExtension")
                        && preserved.points.get(0).childrenXml.contains("vdop")
                        && preserved.points.get(0).childrenXml.contains("pointFlag")
                        && preserved.points.get(0).childrenXml.contains("local:scoped")
                        && preserved.points.get(1).childrenXml.contains("quality"));
        check("attributs de coordonnées source conservés à 12 décimales",
                "48.123456789123".equals(preserved.points.get(0).latitudeText)
                        && "2.123456789123".equals(preserved.points.get(0).longitudeText));

        File dir = Files.createTempDirectory("gpstest-lot6-").toFile();
        File input = new File(dir, "multi-track.gpx");
        Files.write(input.toPath(), bytes);
        byte[] originalBeforeClean = Files.readAllBytes(input.toPath());
        TrackCleaner.Outcome outcome = TrackCleaner.clean(input, 0.0, Double.NaN,
                GpxWriter.Version.V10, null); // la version demandée ne doit pas convertir la source 1.1.
        check("nettoyage non destructif réussi", outcome.success && outcome.file != null && input.exists());
        check("le fichier GPX original reste strictement inchangé",
                java.util.Arrays.equals(originalBeforeClean, Files.readAllBytes(input.toPath())));
        check("les index trace/segment restent associés aux bons points",
                preserved.points.get(0).trackIndex == 0 && preserved.points.get(1).trackIndex == 0
                        && preserved.points.get(2).trackIndex == 1 && preserved.points.get(3).trackIndex == 1);
        String cleaned = new String(Files.readAllBytes(outcome.file.toPath()), StandardCharsets.UTF_8);
        check("version GPX source 1.1 conservée malgré préférence demandée 1.0",
                cleaned.contains("<gpx version=\"1.1\""));
        check("métadonnées, waypoints et routes conservés dans la sortie",
                cleaned.contains("Document title") && cleaned.contains("Metadata description")
                        && cleaned.contains("Parking") && cleaned.contains("Approach route"));
        check("attributs et namespaces personnalisés de la racine conservés",
                cleaned.contains("rootvendor:source=\"original\"")
                        && cleaned.contains("xmlns:rootvendor=\"https://root.example/ns\"")
                        && cleaned.contains("https://schema.example/custom-gpx.xsd"));
        check("ordre des enfants racine préservé, extension finale après les traces",
                cleaned.indexOf("Approach route") < cleaned.indexOf("Morning trail")
                        && cleaned.indexOf("Afternoon trail") < cleaned.indexOf("rootTail"));
        check("deux éléments <trk> séparés et leurs noms respectés",
                occurrences(cleaned, "<trk>") == 2 && cleaned.contains("Morning trail")
                        && cleaned.contains("Afternoon trail"));
        check("extensions fournisseur conservées à tous les niveaux",
                cleaned.contains("documentFlag") && cleaned.contains("trackMeta")
                        && cleaned.contains("segmentMeta") && cleaned.contains("pointFlag")
                        && cleaned.contains("TrackPointExtension") && cleaned.contains("quality")
                        && cleaned.contains("https://local.example/ns")
                        && cleaned.contains("rootTail"));
        check("fraction de seconde et fuseau d'origine conservés exactement",
                cleaned.contains("2026-11-23T06:30:00.123456Z")
                        && cleaned.contains("2026-11-23T14:00:00.987654+01:00"));
        check("coordonnées originales sans arrondi destructif", cleaned.contains("48.123456789123")
                && cleaned.contains("2.123456789123"));
        check("écriture décimale de l'altitude source préservée",
                cleaned.contains("<ele>-12.7500000000</ele>"));
        check("champs GPX supplémentaires non interprétés conservés",
                cleaned.contains("<magvar") && cleaned.contains("<geoidheight")
                        && cleaned.contains("<vdop") && cleaned.contains("<pdop")
                        && cleaned.contains("<fix") && cleaned.contains("<sat"));
        check("les champs reconstruits ele/time ne sont pas dupliqués",
                occurrences(cleaned, "<ele>") == 5 && occurrences(cleaned, "<time>") == 4);

        try (FileInputStream in = new FileInputStream(outcome.file)) {
            GpxParser.Result reparsed = GpxParser.parse(in, 2_000_000L, null, null);
            check("fichier nettoyé relisible par le parseur principal",
                    !reparsed.failed && reparsed.pointCount() == 4
                            && reparsed.waypoints == 1 && reparsed.routePoints == 2);
        }

        String lookalike = "<gpx version=\"1.1\" xmlns=\"http://www.topografix.com/GPX/1/1\" "
                + "xmlns:v=\"https://vendor.example/ext\"><trk><trkseg>"
                + "<trkpt lat=\"10\" lon=\"20\"><time>2020-01-01T00:00:00Z</time>"
                + "<extensions><v:trkpt lat=\"0\" lon=\"0\"><v:ele>999</v:ele></v:trkpt></extensions></trkpt>"
                + "<trkpt lat=\"10.1\" lon=\"20.1\"><time>2020-01-01T00:00:01Z</time>"
                + "</trkpt></trkseg></trk></gpx>";
        GpxParser.Result lookalikeParsed = GpxParser.parse(lookalike);
        GpxPreservation.Document lookalikePreserved = GpxPreservation.parse(
                new ByteArrayInputStream(lookalike.getBytes(StandardCharsets.UTF_8)), 100_000L);
        check("un élément fournisseur v:trkpt n'est pas confondu avec un vrai point GPX",
                !lookalikeParsed.failed && lookalikeParsed.pointCount() == 2
                        && lookalikePreserved.points.size() == 2
                        && lookalikePreserved.points.get(0).childrenXml.contains("v:trkpt"));

        // GPX 1.0 : la version et les propriétés communes restent GPX 1.0.
        String v10 = "<gpx version=\"1.0\" creator=\"Legacy\" xmlns=\"http://www.topografix.com/GPX/1/0\">"
                + "<wpt lat=\"1\" lon=\"2\"><name>legacy waypoint</name></wpt>"
                + "<trk><name>Legacy track</name><number>8</number><type>hiking</type><trkseg>"
                + "<trkpt lat=\"1.1\" lon=\"2.1\"><ele>10</ele><time>2020-01-01T00:00:00Z</time>"
                + "<name>legacy point</name></trkpt><trkpt lat=\"1.2\" lon=\"2.2\">"
                + "<ele>11</ele><time>2020-01-01T00:00:01Z</time></trkpt>"
                + "</trkseg></trk></gpx>";
        File v10File = new File(dir, "legacy.gpx");
        Files.write(v10File.toPath(), v10.getBytes(StandardCharsets.UTF_8));
        TrackCleaner.Outcome v10Outcome = TrackCleaner.clean(v10File, 0.0, Double.NaN,
                GpxWriter.Version.V11, null);
        check("GPX 1.0 reste GPX 1.0 malgré préférence demandée 1.1",
                v10Outcome.success && new String(Files.readAllBytes(v10Outcome.file.toPath()),
                        StandardCharsets.UTF_8).contains("<gpx version=\"1.0\""));
        String v10Clean = new String(Files.readAllBytes(v10Outcome.file.toPath()), StandardCharsets.UTF_8);
        check("propriétés GPX 1.0 et waypoint conservés", v10Clean.contains("Legacy track")
                && v10Clean.contains("number") && v10Clean.contains(">8</number>")
                && v10Clean.contains("legacy waypoint"));
        try (FileInputStream in = new FileInputStream(v10Outcome.file)) {
            GpxParser.Result reparsedV10 = GpxParser.parse(in, 2_000_000L, null, null);
            check("GPX 1.0 nettoyé reste relisible", !reparsedV10.failed
                    && reparsedV10.pointCount() == 2 && reparsedV10.waypoints == 1);
        }

        // Un fichier sans nom de trace ne reçoit pas le nom du fichier ou
        // le nom d'une autre trace comme s'il s'agissait d'une métadonnée source.
        String unnamed = "<gpx version=\"1.1\" xmlns=\"http://www.topografix.com/GPX/1/1\">"
                + "<trk><cmt>comment without name</cmt><trkseg>"
                + "<trkpt lat=\"10\" lon=\"20\"/>"
                + "<trkpt lat=\"10.1\" lon=\"20.1\"><time>2020-01-01T00:00:01Z</time></trkpt>"
                + "</trkseg></trk></gpx>";
        File unnamedFile = new File(dir, "unnamed.gpx");
        Files.write(unnamedFile.toPath(), unnamed.getBytes(StandardCharsets.UTF_8));
        TrackCleaner.Outcome unnamedOutcome = TrackCleaner.clean(unnamedFile, 0.0,
                Double.NaN, GpxWriter.Version.V11, null);
        String unnamedClean = new String(Files.readAllBytes(unnamedOutcome.file.toPath()),
                StandardCharsets.UTF_8);
        check("aucun nom de trace inventé pour une source sans <name>",
                unnamedOutcome.success && unnamedClean.contains("comment without name")
                        && !unnamedClean.contains("<name>"));
        check("aucun timestamp inventé pour un point sans heure source",
                occurrences(unnamedClean, "<time>") == 1);



        String externalEntity = "<?xml version=\"1.0\"?><!DOCTYPE gpx [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<gpx version=\"1.1\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><trkseg>"
                + "<trkpt lat=\"1\" lon=\"2\"><name>&x;</name></trkpt>"
                + "<trkpt lat=\"1.1\" lon=\"2.1\"/></trkseg></trk></gpx>";
        boolean dtdRejected = false;
        try {
            GpxPreservation.parse(new ByteArrayInputStream(
                    externalEntity.getBytes(StandardCharsets.UTF_8)), 100_000L);
        } catch (Exception expected) { dtdRejected = true; }
        check("le parseur de préservation bloque les DTD et entités externes", dtdRejected);

        // Les versions/namespace inconnus ne doivent pas être convertis silencieusement.
        String future = "<gpx version=\"1.2\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><trkseg>"
                + "<trkpt lat=\"1\" lon=\"2\"/><trkpt lat=\"1.1\" lon=\"2.1\"/>"
                + "</trkseg></trk></gpx>";
        File futureFile = new File(dir, "future.gpx");
        Files.write(futureFile.toPath(), future.getBytes(StandardCharsets.UTF_8));
        byte[] futureBefore = Files.readAllBytes(futureFile.toPath());
        TrackCleaner.Outcome futureOutcome = TrackCleaner.clean(futureFile, 0.0,
                Double.NaN, GpxWriter.Version.V11, null);
        check("une version GPX inconnue est refusée sans conversion implicite",
                !futureOutcome.success && java.util.Arrays.equals(futureBefore,
                        Files.readAllBytes(futureFile.toPath())));

        String unknownNamespace = "<gpx version=\"1.1\" xmlns=\"https://vendor.example/nonstandard-gpx\"><trk><trkseg>"
                + "<trkpt lat=\"1\" lon=\"2\"/><trkpt lat=\"1.1\" lon=\"2.1\"/>"
                + "</trkseg></trk></gpx>";
        File namespaceFile = new File(dir, "unknown-namespace.gpx");
        Files.write(namespaceFile.toPath(), unknownNamespace.getBytes(StandardCharsets.UTF_8));
        byte[] namespaceBefore = Files.readAllBytes(namespaceFile.toPath());
        TrackCleaner.Outcome namespaceOutcome = TrackCleaner.clean(namespaceFile, 0.0,
                Double.NaN, GpxWriter.Version.V11, null);
        check("un namespace non standard est refusé sans altérer le fichier",
                !namespaceOutcome.success && java.util.Arrays.equals(namespaceBefore,
                        Files.readAllBytes(namespaceFile.toPath())));

        deleteRecursive(dir);
        System.out.println("GpxPreservationTest : " + ok + " réussis, " + ko + " échoués");
        if (ko != 0) throw new AssertionError("GpxPreservationTest failed");
    }

    private static int occurrences(String text, String needle) {
        int count = 0, index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) { count++; index += needle.length(); }
        return count;
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }
}
