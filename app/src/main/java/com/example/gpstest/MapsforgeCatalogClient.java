package com.example.gpstest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Client réseau volontairement limité au miroir Mapsforge référencé officiellement. */
public final class MapsforgeCatalogClient {
    public static final String BASE_URL =
            "https://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/maps/v5/";
    public static final String HOST = "ftp-stud.hs-esslingen.de";
    public static final String SOURCE_LABEL = "Mapsforge · miroir Hochschule Esslingen · données OSM";
    public static final String USER_AGENT = "GPS-Test/1.0 (Android offline maps)";
    private static final int MAX_CATALOG_BYTES = 2 * 1024 * 1024;

    public interface CancellationToken { boolean isCancelled(); }

    private MapsforgeCatalogClient() { }

    public static List<MapsforgeCatalogParser.Entry> fetchDirectory(
            String relativeDirectory, CancellationToken cancellation) throws IOException {
        String path = normalizeDirectory(relativeDirectory);
        String html = fetchDirectoryHtml(path, cancellation);
        return MapsforgeCatalogParser.parse(html, path);
    }

    /** HTML contrôlé, exposé pour alimenter le cache de navigation hors ligne. */
    public static String fetchDirectoryHtml(String relativeDirectory,
                                            CancellationToken cancellation) throws IOException {
        String path = normalizeDirectory(relativeDirectory);
        URL url = toUrl(BASE_URL + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(25_000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        connection.setInstanceFollowRedirects(false);
        try {
            if (cancelled(cancellation)) throw new IOException("Chargement du catalogue annulé");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("Redirection du catalogue invalide");
                URL redirected;
                try {
                    redirected = url.toURI().resolve(location).toURL();
                } catch (URISyntaxException | IllegalArgumentException e) {
                    throw new IOException("Redirection du catalogue invalide", e);
                }
                String allowedPrefix = URI.create(BASE_URL).getPath();
                if (!"https".equalsIgnoreCase(redirected.getProtocol())
                        || !HOST.equalsIgnoreCase(redirected.getHost())
                        || (redirected.getPort() != -1 && redirected.getPort() != 443)
                        || redirected.getUserInfo() != null
                        || redirected.getQuery() != null || redirected.getRef() != null
                        || !redirected.getPath().startsWith(allowedPrefix)) {
                    throw new IOException("Redirection vers une destination non autorisée");
                }
                connection.disconnect();
                connection = (HttpURLConnection) redirected.openConnection();
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(25_000);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setInstanceFollowRedirects(false);
                status = connection.getResponseCode();
            }
            if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())
                    || !HOST.equalsIgnoreCase(connection.getURL().getHost())) {
                throw new IOException("Hôte du catalogue non autorisé");
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Catalogue indisponible (HTTP " + status + ")");
            }
            long declared = connection.getContentLengthLong();
            if (declared > MAX_CATALOG_BYTES) throw new IOException("Index de catalogue trop volumineux");
            byte[] bytes;
            try (InputStream in = connection.getInputStream()) {
                int initialCapacity = declared > 0
                        ? (int) Math.min((long) MAX_CATALOG_BYTES, declared) : 1024;
                ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, initialCapacity));
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (cancelled(cancellation)) throw new IOException("Chargement du catalogue annulé");
                    if (out.size() + read > MAX_CATALOG_BYTES) {
                        throw new IOException("Index de catalogue trop volumineux");
                    }
                    out.write(buffer, 0, read);
                }
                bytes = out.toByteArray();
            }
            String html = new String(bytes, StandardCharsets.UTF_8);
            if (html.trim().isEmpty()) throw new IOException("Index de catalogue vide");
            return html;
        } finally {
            connection.disconnect();
        }
    }

    public static URL downloadUrl(String relativeMapPath) throws IOException {
        String path = normalizeFilePath(relativeMapPath);
        return toUrl(BASE_URL + path);
    }

    private static URL toUrl(String value) throws IOException {
        try {
            return URI.create(value).toURL();
        } catch (IllegalArgumentException e) {
            throw new IOException("URL Mapsforge invalide", e);
        }
    }

    private static String normalizeDirectory(String path) throws IOException {
        if (path == null || path.isEmpty()) return "";
        String value = path.replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        if (!value.endsWith("/")) value += "/";
        validatePath(value, true);
        return value;
    }

    private static String normalizeFilePath(String path) throws IOException {
        if (path == null) throw new IOException("Chemin cartographique absent");
        String value = path.replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        validatePath(value, false);
        if (!value.toLowerCase(java.util.Locale.ROOT).endsWith(".map")) {
            throw new IOException("Seuls les fichiers Mapsforge .map sont acceptés");
        }
        return value;
    }

    private static void validatePath(String path, boolean directory) throws IOException {
        if (path.isEmpty() || path.contains("..") || path.contains(":")
                || path.contains("?") || path.contains("#") || path.contains("%")
                || path.contains("//")) throw new IOException("Chemin de catalogue invalide");
        String checked = directory && path.endsWith("/")
                ? path.substring(0, path.length() - 1) : path;
        for (String part : checked.split("/")) {
            if (!part.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) {
                throw new IOException("Nom de région non autorisé");
            }
        }
    }

    private static boolean cancelled(CancellationToken token) {
        return token != null && token.isCancelled();
    }
}
