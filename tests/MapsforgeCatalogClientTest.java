package com.example.gpstest;

import java.io.IOException;
import java.net.URL;

public final class MapsforgeCatalogClientTest {
    private static int ok;
    private static int ko;

    private static void check(String label, boolean value) {
        if (value) { ok++; System.out.println("  OK   " + label); }
        else { ko++; System.out.println("  FAIL " + label); }
    }

    private static boolean rejected(String value) {
        try { MapsforgeCatalogClient.downloadUrl(value); return false; }
        catch (IOException expected) { return true; }
    }

    public static void main(String[] args) throws Exception {
        URL valid = MapsforgeCatalogClient.downloadUrl("europe/france/ile-de-france.map");
        check("URL de carte régionale construite sur le miroir HTTPS approuvé",
                "https".equals(valid.getProtocol())
                        && MapsforgeCatalogClient.HOST.equals(valid.getHost())
                        && valid.getPath().endsWith("/maps/v5/europe/france/ile-de-france.map"));
        check("chemin parent interdit", rejected("../evil.map"));
        check("traversal imbriqué interdit", rejected("europe/../../evil.map"));
        check("encodage percent interdit", rejected("europe/%2e%2e/evil.map"));
        check("URL absolue arbitraire interdite", rejected("https://evil.example/evil.map"));
        check("fichier POI ou extension non .map interdite", rejected("europe/france/poi.poi"));
        check("slash inverse/traversal interdit", rejected("europe\\..\\evil.map"));
        check("nom de fichier vide interdit", rejected(""));

        System.out.println("RESULTAT : " + ok + " réussis, " + ko + " échoués");
        if (ko != 0) throw new AssertionError(ko + " test(s) échoué(s)");
    }
}
