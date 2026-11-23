package com.example.gpstest;

import java.net.URLDecoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parseur borné des index Apache autoindex publiés par le miroir Mapsforge. */
public final class MapsforgeCatalogParser {
    private static final Pattern ROW = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr>");
    private static final Pattern ANCHOR = Pattern.compile(
            "(?is)<a\\b[^>]*href\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"][^>]*>(.*?)</a>");
    private static final Pattern CELL = Pattern.compile("(?is)<td\\b[^>]*>(.*?)</td>");
    private static final Pattern DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}(?::\\d{2})?)");
    private static final Pattern SIZE = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*([KMG])(?:i?B)?\\b");
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,119}");

    public static final class Entry {
        public final String name;
        public final String displayName;
        public final String relativePath;
        public final boolean directory;
        public final long approximateSizeBytes;
        public final String sizeLabel;
        public final String lastModified;

        private Entry(String name, String displayName, String relativePath,
                      boolean directory, long approximateSizeBytes,
                      String sizeLabel, String lastModified) {
            this.name = name;
            this.displayName = displayName;
            this.relativePath = relativePath;
            this.directory = directory;
            this.approximateSizeBytes = approximateSizeBytes;
            this.sizeLabel = sizeLabel;
            this.lastModified = lastModified;
        }

        public String regionId() {
            if (directory) return "";
            String path = relativePath.endsWith(".map")
                    ? relativePath.substring(0, relativePath.length() - 4) : relativePath;
            String slug = path.toLowerCase(Locale.ROOT).replace('/', '-').replace('_', '-');
            slug = slug.replaceAll("[^a-z0-9.-]", "-").replaceAll("-+", "-");
            slug = slug.replaceAll("^[.-]+|[.-]+$", "");
            // Le slug seul n'est pas injectif : les séparateurs de dossiers,
            // tirets et underscores peuvent produire le même texte. On ajoute
            // donc toujours un suffixe SHA-256 de 64 bits, même pour les noms
            // courts, afin de ne pas écraser deux régions distinctes.
            String readable = slug.isEmpty() ? "map" : slug;
            if (readable.length() > 42) readable = readable.substring(0, 42);
            return readable + "-" + shortPathHash(path);
        }

        public String directoryPath(String currentDirectory) {
            return join(currentDirectory, name + "/");
        }

        private static String shortPathHash(String value) {
            try {
                byte[] bytes = MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder(16);
                for (int i = 0; i < 8; i++) {
                    hex.append(String.format(Locale.ROOT, "%02x", bytes[i] & 0xff));
                }
                return hex.toString();
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 indisponible", impossible);
            }
        }
    }

    private MapsforgeCatalogParser() { }

    /**
     * Analyse une réponse Apache autoindex. N'accepte que des liens relatifs
     * simples, des sous-dossiers et des fichiers .map ; aucun lien arbitraire.
     */
    public static List<Entry> parse(String html, String currentDirectory) {
        if (html == null || html.isEmpty() || html.length() > 2 * 1024 * 1024) {
            return Collections.emptyList();
        }
        String current = normalizeDirectory(currentDirectory);
        ArrayList<Entry> result = new ArrayList<>();
        Matcher rows = ROW.matcher(html);
        while (rows.find()) {
            parseRow(rows.group(1), current, result);
        }
        // Compatibilité avec une ancienne variante Apache sans tableau <tr>.
        if (result.isEmpty()) {
            Matcher anchors = ANCHOR.matcher(html);
            while (anchors.find()) {
                String href = decodeHtml(anchors.group(1)).trim();
                String label = plainText(decodeHtml(anchors.group(2))).trim();
                addEntry(href, label, "", current, result);
            }
        }
        result.sort(Comparator.comparing((Entry e) -> !e.directory)
                .thenComparing(e -> e.displayName.toLowerCase(Locale.ROOT)));
        return Collections.unmodifiableList(result);
    }

    private static void parseRow(String row, String current, List<Entry> out) {
        Matcher anchor = ANCHOR.matcher(row);
        if (!anchor.find()) return;
        String href = decodeHtml(anchor.group(1)).trim();
        String label = plainText(decodeHtml(anchor.group(2))).trim();
        String text = plainText(row);
        String modified = "";
        Matcher date = DATE.matcher(text);
        if (date.find()) modified = date.group(1);
        String size = "";
        Matcher sizeMatcher = SIZE.matcher(text);
        long bytes = -1L;
        if (sizeMatcher.find()) {
            size = sizeMatcher.group(1) + sizeMatcher.group(2).toUpperCase(Locale.ROOT);
            try {
                double value = Double.parseDouble(sizeMatcher.group(1).replace(',', '.'));
                int power = "KMG".indexOf(sizeMatcher.group(2).toUpperCase(Locale.ROOT)) + 1;
                double converted = value * Math.pow(1024d, power);
                if (converted > 0 && converted < Long.MAX_VALUE) bytes = (long) converted;
            } catch (RuntimeException ignored) { bytes = -1L; }
        }
        addEntry(href, label, size, current, out, bytes, modified);
    }

    private static void addEntry(String href, String label, String sizeLabel,
                                 String current, List<Entry> out) {
        addEntry(href, label, sizeLabel, current, out, -1L, "");
    }

    private static void addEntry(String href, String label, String sizeLabel,
                                 String current, List<Entry> out,
                                 long sizeBytes, String modified) {
        if (href == null || href.isEmpty() || href.startsWith("/")
                || href.contains("..") || href.contains("?") || href.contains("#")
                || href.contains("\\") || href.contains(":")
                || href.equals("../") || href.equals("./")) return;
        boolean directory = href.endsWith("/");
        String name = directory ? href.substring(0, href.length() - 1) : href;
        if (!SAFE_NAME.matcher(name).matches()) return;
        if (!directory && !name.toLowerCase(Locale.ROOT).endsWith(".map")) return;
        if (label.isEmpty() || label.equals("Parent Directory") || label.equals(name + "/")
                || label.equals(name)) {
            // Le libellé du lien peut inclure une icône ou être identique au nom ;
            // cela reste un nom sûr, et le rendu utilisateur l'humanisera.
            label = humanize(name);
        } else {
            label = humanize(label.replaceAll("\\s+", " "));
        }
        String relative = join(current, directory ? name + "/" : name);
        for (Entry existing : out) if (existing.relativePath.equals(relative)) return;
        out.add(new Entry(name, label, relative, directory, sizeBytes,
                sizeLabel == null ? "" : sizeLabel, modified == null ? "" : modified));
    }

    private static String normalizeDirectory(String path) {
        if (path == null || path.isEmpty()) return "";
        String value = path.replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        if (value.contains("..") || value.contains(":") || value.contains("?")) return "";
        if (!value.endsWith("/")) value += "/";
        for (String part : value.split("/")) {
            if (!part.isEmpty() && !SAFE_NAME.matcher(part).matches()) return "";
        }
        return value;
    }

    private static String join(String a, String b) { return (a == null ? "" : a) + b; }

    private static String plainText(String html) {
        return html.replaceAll("(?is)<[^>]*>", " ").replace('&', ' ')
                .replaceAll("\\s+", " ").trim();
    }

    private static String decodeHtml(String value) {
        if (value == null) return "";
        return value.replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
    }

    private static String humanize(String value) {
        String text = value.replaceAll("(?i)\\.map$", "").replace('-', ' ').replace('_', ' ').trim();
        if (text.isEmpty()) return "Carte";
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
