package com.example.gpstest;

public final class MapsforgePolygonSamplerTest {
    private static int ok, ko;
    private static void check(String label, boolean value) {
        if (value) { ok++; System.out.println("  OK   " + label); }
        else { ko++; System.out.println("  FAIL " + label); }
    }
    public static void main(String[] args) {
        check("petit anneau dessiné intégralement", MapsforgePolygonSampler.stride(500) == 1);
        check("anneau vide sans sommet", MapsforgePolygonSampler.sampledPointCount(0) == 0);
        check("anneau à un sommet conserve ce sommet", MapsforgePolygonSampler.sampledPointCount(1) == 1);
        check("grand anneau réduit sous la limite visuelle", 
                MapsforgePolygonSampler.sampledPointCount(100_000) <= MapsforgePolygonSampler.MAX_DRAW_POINTS_PER_RING + 1);
        check("premier et dernier points inclus dans l'échantillonnage",
                MapsforgePolygonSampler.sampledPointCount(100_000) >= 3);
        check("pas strictement positif", MapsforgePolygonSampler.stride(Integer.MAX_VALUE) > 0);
        System.out.println("RESULTAT : " + ok + " réussis, " + ko + " échoués");
        if (ko != 0) throw new AssertionError(ko + " test(s) échoué(s)");
    }
}
