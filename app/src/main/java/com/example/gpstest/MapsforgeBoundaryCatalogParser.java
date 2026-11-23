package com.example.gpstest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parseur borné des index Apache du répertoire maps/poly Mapsforge. */
public final class MapsforgeBoundaryCatalogParser {
    private static final Pattern ROW = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr>");
    private static final Pattern ANCHOR = Pattern.compile(
            "(?is)<a\\b[^>]*href\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"][^>]*>(.*?)</a>");
    private static final Pattern DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}(?::\\d{2})?)");
    private static final Pattern SIZE = Pattern.compile("(?i)(\\d+(?:[.,]\\d+)?)\\s*([KMG])(?:i?B)?\\b");
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,119}");

    public static final class Entry {
        public final String name, displayName, relativePath, lastModified;
        public final boolean directory;
        public final long sizeBytes;
        public Entry(String name, String displayName, String relativePath,
                     String lastModified, boolean directory, long sizeBytes) {
            this.name = name; this.displayName = displayName; this.relativePath = relativePath;
            this.lastModified = lastModified; this.directory = directory; this.sizeBytes = sizeBytes;
        }
    }

    private MapsforgeBoundaryCatalogParser() { }

    public static List<Entry> parse(String html, String currentDirectory) {
        if (html == null || html.isEmpty() || html.length() > 2 * 1024 * 1024) {
            return Collections.emptyList();
        }
        String current = normalize(currentDirectory);
        if (current == null) return Collections.emptyList();
        ArrayList<Entry> out = new ArrayList<>();
        Matcher rows = ROW.matcher(html);
        while (rows.find()) {
            String row = rows.group(1);
            Matcher anchor = ANCHOR.matcher(row);
            if (!anchor.find()) continue;
            String href = clean(anchor.group(1));
            String label = plain(anchor.group(2));
            String text = plain(row);
            String modified = "";
            Matcher date = DATE.matcher(text);
            if (date.find()) modified = date.group(1);
            long size = -1;
            Matcher sizeMatcher = SIZE.matcher(text);
            if (sizeMatcher.find()) {
                try {
                    double value = Double.parseDouble(sizeMatcher.group(1).replace(',', '.'));
                    int power = "KMG".indexOf(sizeMatcher.group(2).toUpperCase(Locale.ROOT)) + 1;
                    double bytes = value * Math.pow(1024d, power);
                    if (bytes > 0 && bytes < Long.MAX_VALUE) size = (long) bytes;
                } catch (RuntimeException ignored) { size = -1; }
            }
            add(href, label, current, modified, size, out);
        }
        out.sort(Comparator.comparing((Entry e) -> !e.directory)
                .thenComparing(e -> e.displayName.toLowerCase(Locale.ROOT)));
        return Collections.unmodifiableList(out);
    }

    private static void add(String href, String label, String current, String modified,
                            long size, List<Entry> out) {
        if (href == null || href.isEmpty() || href.startsWith("/") || href.contains("..")
                || href.contains("?") || href.contains("#") || href.contains("\\")
                || href.contains(":") || href.contains("%")) return;
        boolean directory = href.endsWith("/");
        String name = directory ? href.substring(0, href.length() - 1) : href;
        if (!SAFE.matcher(name).matches()) return;
        if (!directory && !name.toLowerCase(Locale.ROOT).endsWith(".poly")) return;
        String relative = current + (directory ? name + "/" : name);
        for (Entry entry : out) if (entry.relativePath.equals(relative)) return;
        String display = label == null ? "" : label.trim().replaceAll("\\s+", " ");
        if (display.isEmpty() || display.equals(name) || display.equals(name + "/")) {
            display = name.replaceAll("(?i)\\.poly$", "").replace('-', ' ').replace('_', ' ');
        }
        if (!display.isEmpty()) display = Character.toUpperCase(display.charAt(0)) + display.substring(1);
        out.add(new Entry(name, display, relative, modified, directory, size));
    }

    private static String normalize(String path) {
        if (path == null || path.isEmpty()) return "";
        String value = path.replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        if (value.contains("..") || value.contains(":") || value.contains("?") || value.contains("%")) return null;
        if (!value.endsWith("/")) value += "/";
        for (String part : value.split("/")) {
            if (!part.isEmpty() && !SAFE.matcher(part).matches()) return null;
        }
        return value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace("&amp;", "&").trim();
    }
    private static String plain(String html) {
        return html == null ? "" : html.replaceAll("(?is)<[^>]*>", " ")
                .replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }
}
