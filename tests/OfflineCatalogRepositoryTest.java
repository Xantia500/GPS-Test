package com.example.gpstest;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;
import java.util.List;
import java.util.Properties;

public final class OfflineCatalogRepositoryTest {
    private static int passed, failed;
    private static void check(String label, boolean value) {
        if (value) passed++; else failed++;
        System.out.println((value ? "  OK   " : "  FAIL ") + label);
    }
    private static String index(String first, String second) {
        return "<html><body><table>"
                + "<tr><td><a href=\"" + first + "\">" + first + "</a></td><td>2026-11-20 10:00</td><td>1.2M</td></tr>"
                + "<tr><td><a href=\"" + second + "\">" + second + "</a></td><td>2026-11-21 11:00</td><td>2.4M</td></tr>"
                + "</table></body></html>";
    }
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("offline-catalog-test-").toFile();
        OfflineCatalogRepository repo = new OfflineCatalogRepository(root);
        String europe = index("france/", "belgium.map");
        repo.saveDirectory("europe/", europe, 1_800_000_000_000L);
        OfflineCatalogRepository.Snapshot loaded = repo.loadDirectory("europe/");
        check("index enregistré et relu hors ligne", loaded != null && loaded.stale
                && loaded.entries.size() == 2 && loaded.directory.equals("europe/"));
        check("chemins relatifs conservés pour les actions ultérieures", loaded != null
                && "europe/france/".equals(loaded.entries.get(0).relativePath));

        String france = index("ile-de-france.map", "bretagne.map");
        repo.saveDirectory("europe/france/", france, 1_800_000_100_000L);
        List<MapsforgeCatalogParser.Entry> matches = repo.searchCached("bretagne", 50);
        check("recherche locale traverse les catalogues de plusieurs dossiers",
                matches.size() == 1 && matches.get(0).relativePath.endsWith("bretagne.map"));
        List<MapsforgeCatalogParser.Entry> directories = repo.searchCached("europe/france/", 50);
        boolean franceDirectoryFound = false;
        for (MapsforgeCatalogParser.Entry entry : directories) {
            if (entry.directory && "europe/france/".equals(entry.relativePath)) franceDirectoryFound = true;
        }
        check("recherche locale retrouve le sous-dossier exact parmi les résultats imbriqués",
                franceDirectoryFound);
        check("deux index sont persistés indépendamment", repo.cachedDirectoryCount() == 2
                && repo.cachedBytes() > 0);

        boolean traversal = false;
        try { repo.saveDirectory("../outside/", europe, System.currentTimeMillis()); }
        catch (Exception expected) { traversal = true; }
        check("chemin de catalogue avec traversal refusé", traversal);

        // Corrompt le HTML de l'un des index sans toucher aux métadonnées :
        // la recherche doit ignorer ce dossier et continuer sur l'autre.
        File[] files = root.listFiles((d, n) -> n.endsWith(".html"));
        if (files == null || files.length != 2) throw new AssertionError("Cache inattendu");
        File europeHtml = null;
        for (File html : files) {
            String key = html.getName().substring(0, html.getName().length() - ".html".length());
            File meta = new File(root, key + ".properties");
            Properties properties = new Properties();
            try (FileInputStream in = new FileInputStream(meta)) { properties.load(in); }
            if ("europe/".equals(properties.getProperty("directory"))) europeHtml = html;
        }
        if (europeHtml == null) throw new AssertionError("Index Europe introuvable");
        Files.write(europeHtml.toPath(), "corrompu".getBytes(StandardCharsets.UTF_8));
        List<MapsforgeCatalogParser.Entry> afterCorruption = repo.searchCached("bretagne", 50);
        check("index corrompu ignoré tandis que la recherche continue sur les autres dossiers",
                afterCorruption.size() == 1
                        && afterCorruption.get(0).relativePath.endsWith("bretagne.map"));
        System.out.println("RESULTAT : " + passed + " réussis, " + failed + " échoués");
        if (failed > 0) throw new AssertionError(failed + " échec(s)");
    }
}
