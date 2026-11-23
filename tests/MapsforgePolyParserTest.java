package com.example.gpstest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class MapsforgePolyParserTest {
    private static int ok;
    private static int ko;
    private static void check(String label, boolean value) {
        if (value) { ok++; System.out.println("  OK   " + label); }
        else { ko++; System.out.println("  FAIL " + label); }
    }
    private static boolean fails(String text) {
        try { MapsforgePolyParser.parse(text.getBytes(StandardCharsets.UTF_8)); return false; }
        catch (IOException expected) { return true; }
    }

    public static void main(String[] args) throws Exception {
        String poly = """
                idf
                1
                2.0 48.0
                3.0 48.0
                3.0 49.0
                2.0 49.0
                END
                !lake
                2.2 48.2
                2.4 48.2
                2.4 48.4
                2.2 48.4
                END
                2
                4.0 48.0
                5.0 48.0
                5.0 49.0
                4.0 49.0
                4.0 48.0
                END
                END
                """;
        MapsforgePolyParser.Polygon parsed = MapsforgePolyParser.parse(poly.getBytes(StandardCharsets.UTF_8));
        check("en-tête source .poly conservé", "idf".equals(parsed.sourceName));
        check("deux polygones extérieurs et un trou reconnus", parsed.rings.size() == 3
                && !parsed.rings.get(0).hole && parsed.rings.get(1).hole && !parsed.rings.get(2).hole);
        check("coordonnées interprétées longitude puis latitude", parsed.rings.get(0).points.get(0).longitude == 2.0
                && parsed.rings.get(0).points.get(0).latitude == 48.0);
        check("point dans le premier contour reconnu", parsed.contains(48.1, 2.1));
        check("point dans le trou exclu", !parsed.contains(48.3, 2.3));
        check("île/deuxième contour reconnu", parsed.contains(48.5, 4.5));
        check("point extérieur rejeté", !parsed.contains(47.0, 2.5));
        check("limites WGS84 calculées", parsed.minLatitude == 48.0 && parsed.maxLatitude == 49.0
                && parsed.minLongitude == 2.0 && parsed.maxLongitude == 5.0);

        String unclosed = "triangle\n1\n0 0\n1 0\n0 1\nEND\nEND\n";
        MapsforgePolyParser.Polygon closed = MapsforgePolyParser.parse(
                new ByteArrayInputStream(unclosed.getBytes(StandardCharsets.UTF_8)));
        check("anneau automatiquement refermé si le premier sommet n'est pas répété",
                closed.rings.get(0).points.size() == 4
                        && closed.rings.get(0).points.get(0).longitude == closed.rings.get(0).points.get(3).longitude);
        check("un trou avant tout contour est refusé", fails("bad\n!hole\n0 0\n1 0\n0 1\nEND\nEND\n"));
        check("coordonnée NaN refusée", fails("bad\n1\nNaN 0\n1 0\n0 1\nEND\nEND\n"));
        check("coordonnée hors longitude WGS84 refusée", fails("bad\n1\n181 0\n1 0\n0 1\nEND\nEND\n"));
        check("anneau avec deux sommets refusé", fails("bad\n1\n0 0\n1 0\nEND\nEND\n"));
        check("anneau tronqué refusé", fails("bad\n1\n0 0\n1 0\n0 1\n"));
        check("END final absent refusé", fails("bad\n1\n0 0\n1 0\n0 1\nEND\n"));
        check("données après END final refusées", fails("bad\n1\n0 0\n1 0\n0 1\nEND\nEND\nextra\n"));
        check("flux vide refusé", fails(""));
        check("fichier dépassant 512 Kio refusé", fails("x\n" + " ".repeat(MapsforgePolyParser.MAX_BYTES)));

        System.out.println("RESULTAT : " + ok + " réussis, " + ko + " échoués");
        if (ko != 0) throw new AssertionError(ko + " test(s) échoué(s)");
    }
}
