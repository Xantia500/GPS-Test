package com.example.gpstest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Accès HTTPS limité aux polygones de frontières du miroir Mapsforge approuvé. */
public final class MapsforgeBoundaryClient {
    public static final String BASE_URL =
            "https://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/maps/poly/";
    private static final String HOST = "ftp-stud.hs-esslingen.de";
    private static final int MAX_INDEX_BYTES = 2 * 1024 * 1024;
    private MapsforgeBoundaryClient() { }

    public interface CancellationToken { boolean isCancelled(); }

    public static List<MapsforgeBoundaryCatalogParser.Entry> fetchDirectory(
            String relativeDirectory, CancellationToken cancellation) throws IOException {
        String path = normalize(relativeDirectory, true);
        byte[] bytes = request(path, MAX_INDEX_BYTES, cancellation);
        return MapsforgeBoundaryCatalogParser.parse(new String(bytes, StandardCharsets.UTF_8), path);
    }

    public static byte[] fetchPolygon(String relativePath, CancellationToken cancellation)
            throws IOException {
        String path = normalize(relativePath, false);
        if (!path.toLowerCase(java.util.Locale.ROOT).endsWith(".poly")) {
            throw new IOException("Seuls les fichiers .poly sont autorisés");
        }
        return request(path, MapsforgePolyParser.MAX_BYTES, cancellation);
    }

    private static byte[] request(String path, int maximum, CancellationToken cancellation)
            throws IOException {
        URL original = URI.create(BASE_URL + path).toURL();
        URL url = original;
        HttpURLConnection connection = open(url);
        try {
            if (cancelled(cancellation)) throw new IOException("Téléchargement de frontières annulé");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("Redirection de frontière invalide");
                try { url = original.toURI().resolve(location).toURL(); }
                catch (Exception e) { throw new IOException("Redirection de frontière invalide", e); }
                validateDestination(url);
                connection.disconnect();
                connection = open(url);
                status = connection.getResponseCode();
            }
            validateDestination(connection.getURL());
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Frontière indisponible (HTTP " + status + ")");
            }
            long length = connection.getContentLengthLong();
            if (length > maximum) throw new IOException("Frontière trop volumineuse");
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream(length > 0 ? (int) length : 4096)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (cancelled(cancellation)) throw new IOException("Téléchargement de frontières annulé");
                    if (out.size() + read > maximum) throw new IOException("Réponse de frontières trop volumineuse");
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(18_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", MapsforgeCatalogClient.USER_AGENT);
        connection.setRequestProperty("Accept", "text/plain,text/html,application/xhtml+xml");
        return connection;
    }

    private static void validateDestination(URL url) throws IOException {
        String allowed = URI.create(BASE_URL).getPath();
        if (!"https".equalsIgnoreCase(url.getProtocol()) || !HOST.equalsIgnoreCase(url.getHost())
                || (url.getPort() != -1 && url.getPort() != 443)
                || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null
                || !url.getPath().startsWith(allowed)) {
            throw new IOException("Destination de frontière non autorisée");
        }
    }

    private static String normalize(String raw, boolean directory) throws IOException {
        if (raw == null) throw new IOException("Chemin de frontière absent");
        String value = raw.replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        if (directory && !value.isEmpty() && !value.endsWith("/")) value += "/";
        if (value.contains("..") || value.contains(":") || value.contains("?")
                || value.contains("#") || value.contains("%") || value.contains("//")) {
            throw new IOException("Chemin de frontière invalide");
        }
        if (value.isEmpty() && !directory) throw new IOException("Nom de frontière absent");
        String checked = directory && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        for (String part : checked.split("/")) {
            if (!part.isEmpty() && !part.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) {
                throw new IOException("Nom de frontière non autorisé");
            }
        }
        return value;
    }

    private static boolean cancelled(CancellationToken token) {
        return token != null && token.isCancelled();
    }
}
