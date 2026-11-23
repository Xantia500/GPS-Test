package com.example.gpstest;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public final class GpxInputEncodingTest {
    private static int ok;
    private static int ko;

    private static void check(String label, boolean value) {
        if (value) { ok++; System.out.println("  OK   " + label); }
        else { ko++; System.out.println("  FAIL " + label); }
    }

    private static GpxParser.Result parse(byte[] bytes) {
        return GpxParser.parse(new ByteArrayInputStream(bytes), 1_000_000, null, null);
    }

    public static void main(String[] args) throws Exception {
        String utf8 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<gpx version=\"1.1\"><trk><name>Été &amp; vélo</name><trkseg>"
                + "<trkpt lat=\"48\" lon=\"2\"/></trkseg></trk></gpx>";
        byte[] utf8Bytes = utf8.getBytes(StandardCharsets.UTF_8);
        byte[] utf8Bom = new byte[utf8Bytes.length + 3];
        utf8Bom[0] = (byte) 0xef; utf8Bom[1] = (byte) 0xbb; utf8Bom[2] = (byte) 0xbf;
        System.arraycopy(utf8Bytes, 0, utf8Bom, 3, utf8Bytes.length);
        GpxParser.Result decodedUtf8 = parse(utf8Bom);
        check("UTF-8 avec BOM lu directement par SAX", !decodedUtf8.failed
                && decodedUtf8.pointCount() == 1);
        check("accents et entités XML UTF-8 conservés", "Été & vélo".equals(decodedUtf8.trackName));

        String utf16 = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><gpx><trk><name>Carte</name>"
                + "<trkseg><trkpt lat=\"48\" lon=\"2\"/></trkseg></trk></gpx>";
        GpxParser.Result decodedUtf16 = parse(utf16.getBytes(Charset.forName("UTF-16")));
        check("UTF-16 avec BOM/signature reconnu par SAX", !decodedUtf16.failed
                && "Carte".equals(decodedUtf16.trackName));

        String latin = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><gpx><trk><name>Été</name>"
                + "<trkseg><trkpt lat=\"48\" lon=\"2\"/></trkseg></trk></gpx>";
        GpxParser.Result decodedLatin = parse(latin.getBytes(Charset.forName("ISO-8859-1")));
        check("encodage déclaré ISO-8859-1 reconnu", !decodedLatin.failed
                && "Été".equals(decodedLatin.trackName));

        byte[] malformed = "<gpx><trk><name>".getBytes(StandardCharsets.US_ASCII);
        byte[] tail = "</name><trkseg><trkpt lat=\"48\" lon=\"2\"/></trkseg></trk></gpx>"
                .getBytes(StandardCharsets.US_ASCII);
        byte[] invalid = new byte[malformed.length + 2 + tail.length];
        System.arraycopy(malformed, 0, invalid, 0, malformed.length);
        invalid[malformed.length] = (byte) 0xc3;
        invalid[malformed.length + 1] = 0x28;
        System.arraycopy(tail, 0, invalid, malformed.length + 2, tail.length);
        GpxParser.Result invalidUtf8 = parse(invalid);
        check("séquence UTF-8 mal formée refusée", invalidUtf8.failed);

        GpxParser.Result limited = GpxParser.parse(
                new ByteArrayInputStream(utf8Bytes), 10, null, null);
        check("limite stricte sur les octets appliquée à l'entrée", limited.failed
                && limited.error != null && limited.error.contains("volumineux"));

        System.out.println("GpxInputEncodingTest : " + ok + " réussis, " + ko + " échoués");
        if (ko != 0) throw new AssertionError("GpxInputEncodingTest failed");
    }
}
