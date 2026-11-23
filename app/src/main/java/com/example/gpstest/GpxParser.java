package com.example.gpstest;

import org.xml.sax.Attributes;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.parsers.SAXParserFactory;

/**
 * Lecteur GPX 1.0 / 1.1 en streaming SAX.
 *
 * Le document XML n'est jamais matérialisé en String : seuls les points
 * de trace nécessaires à la carte / au post-traitement sont conservés.
 * Le parseur est indépendant d'Android et n'ajoute aucune dépendance.
 *
 * Sécurité : DTD et entités externes interdites, résolution réseau bloquée,
 * profondeur et tailles bornées. Un document XML mal formé est signalé ;
 * les points lus avant l'erreur restent dans Result à titre diagnostique,
 * mais l'appelant ne doit pas présenter le résultat comme complet.
 */
public final class GpxParser {
    private GpxParser() { }

    /** Limite métier ; au-delà, l'import est refusé plutôt que de saturer la RAM. */
    public static final int MAX_TRACK_POINTS = 250_000;
    public static final int MAX_SEGMENTS = 20_000;
    public static final int MAX_XML_DEPTH = 64;
    public static final int MAX_SCALAR_CHARS = 16_384;

    public interface CancellationToken {
        boolean isCancelled();
    }

    /** Appelé sur le thread de parsing, à intervalles espacés. */
    public interface ProgressListener {
        void onProgress(int parsedTrackPoints, long bytesRead);
    }

    public static final class Point {
        public final double lat;
        public final double lon;
        /** Altitude, ou NaN si absente. */
        public final double ele;
        /** Horodatage ISO 8601 tel quel, ou null. */
        public final String timeIso;
        /** Horodatage epoch millisecondes, ou -1 si illisible. */
        public final long timeMs;
        /** Index du segment d'appartenance. */
        public final int segment;
        /** Ordre parmi les points valides. */
        public final int index;
        public final Float speedMps, courseDeg, hdop, accuracyM;

        Point(double lat, double lon, double ele, String timeIso, long timeMs,
              int segment, int index, Float speedMps, Float courseDeg,
              Float hdop, Float accuracyM) {
            this.lat = lat;
            this.lon = lon;
            this.ele = ele;
            this.timeIso = timeIso;
            this.timeMs = timeMs;
            this.segment = segment;
            this.index = index;
            this.speedMps = speedMps;
            this.courseDeg = courseDeg;
            this.hdop = hdop;
            this.accuracyM = accuracyM;
        }
    }

    public static final class Result {
        public final List<Point> points = new ArrayList<>();
        public String trackName;
        public String creator;
        public String version;
        public int segmentCount;
        public int routePoints;
        public int waypoints;
        public int invalidTrackPoints;
        public boolean failed;
        public boolean cancelled;
        public String error;

        public int pointCount() { return points.size(); }
        public boolean isEmpty() { return points.isEmpty(); }
    }

    /** Compatibilité avec les appels existants et les tests de format. */
    public static Result parse(String xml) {
        Result result = new Result();
        if (xml == null || xml.trim().isEmpty()) {
            result.failed = true;
            result.error = "Fichier vide";
            return result;
        }
        try {
            String normalized = xml.charAt(0) == '\uFEFF' ? xml.substring(1) : xml;
            parseSource(new InputSource(new StringReader(normalized)), null, null, null, result);
        } catch (Exception e) {
            setFailure(result, e);
        }
        finishResult(result);
        return result;
    }

    /**
     * Parse directement un flux d'octets. Le flux reste la propriété de
     * l'appelant et doit être fermé par celui-ci.
     *
     * @param maxBytes limite stricte sur les octets lus (pas sur la taille
     *                 déclarée par le fournisseur SAF, souvent absente).
     */
    public static Result parse(InputStream input, long maxBytes,
                               ProgressListener progress,
                               CancellationToken cancellation) {
        Result result = new Result();
        if (input == null) {
            result.failed = true;
            result.error = "Flux GPX absent";
            return result;
        }
        if (maxBytes < 1L) {
            result.failed = true;
            result.error = "Limite de lecture GPX invalide";
            return result;
        }

        CountingInputStream counted = new CountingInputStream(input, maxBytes);
        try {
            InputSource source = new InputSource(counted);
            parseSource(source, counted, progress, cancellation, result);
        } catch (Exception e) {
            if (isCancelled(e, cancellation)) {
                result.cancelled = true;
                result.failed = true;
                result.error = "Import annulé";
            } else {
                setFailure(result, e);
                if (counted.limitExceeded) {
                    result.error = "Fichier GPX trop volumineux (limite "
                            + formatBytes(maxBytes) + ")";
                }
            }
        }
        finishResult(result);
        return result;
    }

    private static void parseSource(InputSource source, CountingInputStream counted,
                                    ProgressListener progress, CancellationToken cancellation,
                                    Result result) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        // Ne pas utiliser ici les options Xerces/JAXP spécifiques au JDK :
        // le SAX/Expat natif d'Android ne les expose pas toutes. La sécurité
        // est appliquée via les mécanismes SAX communs ci-dessous.
        XMLReader reader = factory.newSAXParser().getXMLReader();
        reader.setEntityResolver(new EntityResolver() {
            @Override public InputSource resolveEntity(String publicId, String systemId)
                    throws SAXException {
                throw new SAXException("Entité XML externe interdite");
            }
        });
        GpxHandler handler = new GpxHandler(result, counted, progress, cancellation);
        reader.setContentHandler(handler);
        reader.setErrorHandler(handler);
        // LexicalHandler est exposé par le SAX/Expat Android. Toute déclaration
        // DOCTYPE déclenche startDTD avant son sous-ensemble interne : on coupe
        // immédiatement, y compris les entités internes potentiellement expansives.
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        reader.parse(source);
        handler.checkCancellation();
        handler.reportProgress(true);
    }

    private static void finishResult(Result result) {
        if (result.cancelled) return;
        if (result.error == null && result.points.isEmpty()) {
            result.failed = true;
            result.error = "Aucun point de trace exploitable dans ce fichier";
        }
    }

    private static void setFailure(Result result, Exception error) {
        result.failed = true;
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        if (error instanceof SAXParseException) {
            SAXParseException parse = (SAXParseException) error;
            result.error = "XML GPX mal formé (ligne " + parse.getLineNumber()
                    + ", colonne " + parse.getColumnNumber() + ")";
        } else if (cause instanceof SAXParseException) {
            SAXParseException parse = (SAXParseException) cause;
            result.error = "XML GPX mal formé (ligne " + parse.getLineNumber()
                    + ", colonne " + parse.getColumnNumber() + ")";
        } else if (error instanceof SAXException && error.getMessage() != null) {
            result.error = "Lecture GPX refusée : " + error.getMessage();
        } else if (error instanceof IOException && error.getMessage() != null) {
            result.error = "Lecture GPX impossible : " + error.getMessage();
        } else {
            result.error = "XML GPX illisible (" + error.getClass().getSimpleName() + ")";
        }
    }

    private static boolean isCancelled(Exception error, CancellationToken token) {
        if (token != null && token.isCancelled()) return true;
        Throwable t = error;
        while (t != null) {
            if (t instanceof ImportCancelledException) return true;
            t = t.getCause();
        }
        return false;
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) return (bytes / (1024L * 1024L)) + " Mio";
        return Math.max(1L, bytes / 1024L) + " Kio";
    }

    private static final class ImportCancelledException extends SAXException {
        ImportCancelledException() { super("Import annulé"); }
    }

    private static final class SizeLimitException extends IOException {
        SizeLimitException() { super("Limite d'octets atteinte"); }
    }

    private static final class CountingInputStream extends FilterInputStream {
        final long maxBytes;
        long count;
        boolean limitExceeded;
        CountingInputStream(InputStream input, long maxBytes) {
            super(input);
            this.maxBytes = maxBytes;
        }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) addCount(1);
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            int n = super.read(buffer, offset, length);
            if (n > 0) addCount(n);
            return n;
        }
        /** Certains parseurs peuvent appeler skip() : ce chemin doit lui aussi être borné. */
        @Override public long skip(long requested) throws IOException {
            if (requested <= 0) return 0L;
            byte[] discard = new byte[(int) Math.min(4096L, requested)];
            long skipped = 0L;
            while (skipped < requested) {
                int n = read(discard, 0, (int) Math.min(discard.length, requested - skipped));
                if (n < 0) break;
                skipped += n;
            }
            return skipped;
        }
        private void addCount(int amount) throws IOException {
            count += amount;
            if (count > maxBytes) {
                limitExceeded = true;
                throw new SizeLimitException();
            }
        }
    }

    private static final class MutablePoint {
        double lat, lon;
        double ele = Double.NaN;
        String timeIso;
        final int segment;
        Float speedMps, courseDeg, hdop, accuracyM;
        MutablePoint(double lat, double lon, int segment) {
            this.lat = lat; this.lon = lon; this.segment = segment;
        }
        Point freeze(int index) {
            return new Point(lat, lon, ele, timeIso, parseIso8601(timeIso), segment,
                    index, speedMps, courseDeg, hdop, accuracyM);
        }
    }

    private static final class GpxHandler extends DefaultHandler implements LexicalHandler {
        private final Result result;
        private final CountingInputStream counted;
        private final ProgressListener progress;
        private final CancellationToken cancellation;
        private int depth;
        private boolean rootSeen;
        private String rootNamespaceUri = "";
        private boolean firstTrackNameSeen;
        private int trackDepth = -1;
        private int implicitSegmentTrackDepth = -1;
        private int currentSegment;
        private int pointDepth = -1;
        private MutablePoint point;
        private int captureDepth = -1;
        private String captureName;
        private StringBuilder capture;
        private int lastReportedPoints = -1;
        private long lastReportedBytes;

        GpxHandler(Result result, CountingInputStream counted, ProgressListener progress,
                   CancellationToken cancellation) {
            this.result = result;
            this.counted = counted;
            this.progress = progress;
            this.cancellation = cancellation;
        }

        @Override public void startElement(String uri, String localName, String qName,
                                           Attributes attributes) throws SAXException {
            checkCancellation();
            depth++;
            if (depth > MAX_XML_DEPTH) throw new SAXException("Document XML trop profondément imbriqué");
            if (attributes != null && attributes.getLength() > 128)
                throw new SAXException("Trop d'attributs sur une balise XML");

            String name = local(localName, qName);
            if (depth == 1 && "gpx".equals(name)) {
                rootSeen = true;
                rootNamespaceUri = uri != null ? uri : "";
                result.creator = attribute(attributes, "creator");
                result.version = attribute(attributes, "version");
                return;
            }
            if (!rootSeen) return;
            boolean gpxElement = isGpxElement(uri);

            if (gpxElement && "trk".equals(name)) {
                trackDepth = depth;
                implicitSegmentTrackDepth = -1;
                return;
            }
            if (gpxElement && "trkseg".equals(name)) {
                if (result.segmentCount >= MAX_SEGMENTS)
                    throw new SAXException("Trop de segments GPX (limite " + MAX_SEGMENTS + ")");
                currentSegment = result.segmentCount++;
                implicitSegmentTrackDepth = -1;
                activeTrackSegmentDepth = depth;
                return;
            }
            if (gpxElement && "trkpt".equals(name)) {
                if (trackDepth >= 0 && implicitSegmentTrackDepth != trackDepth
                        && !isInsideTrackSegment()) {
                    if (result.segmentCount >= MAX_SEGMENTS)
                        throw new SAXException("Trop de segments GPX (limite " + MAX_SEGMENTS + ")");
                    currentSegment = result.segmentCount++;
                    implicitSegmentTrackDepth = trackDepth;
                }
                double lat = parseDouble(attribute(attributes, "lat"));
                double lon = parseDouble(attribute(attributes, "lon"));
                point = new MutablePoint(lat, lon, currentSegment);
                pointDepth = depth;
                return;
            }
            if (gpxElement && "rtept".equals(name)) {
                result.routePoints++;
                return;
            }
            if (gpxElement && "wpt".equals(name)) {
                result.waypoints++;
                return;
            }

            if (point != null && pointDepth > 0 && captureDepth < 0) {
                if (isPointScalar(name, uri)) {
                    captureDepth = depth;
                    captureName = name;
                    capture = new StringBuilder(32);
                }
            } else if (gpxElement && "name".equals(name) && !firstTrackNameSeen
                    && trackDepth > 0 && depth == trackDepth + 1) {
                firstTrackNameSeen = true;
                captureDepth = depth;
                captureName = "trackName";
                capture = new StringBuilder(64);
            }
        }

        @Override public void characters(char[] ch, int start, int length) throws SAXException {
            checkCancellation();
            if (capture != null && capture.length() + length <= MAX_SCALAR_CHARS) {
                capture.append(ch, start, length);
            } else if (capture != null) {
                throw new SAXException("Champ texte GPX trop volumineux");
            }
            reportProgress(false);
        }

        @Override public void endElement(String uri, String localName, String qName)
                throws SAXException {
            checkCancellation();
            String name = local(localName, qName);
            if (capture != null && captureDepth == depth) {
                applyCapturedValue(captureName, capture.toString().trim());
                capture = null;
                captureName = null;
                captureDepth = -1;
            }
            if (isGpxElement(uri) && "trkseg".equals(name)
                    && depth == activeTrackSegmentDepth) {
                activeTrackSegmentDepth = -1;
            }
            if (isGpxElement(uri) && "trkpt".equals(name)
                    && point != null && depth == pointDepth) {
                if (validCoords(point.lat, point.lon)) {
                    if (result.points.size() >= MAX_TRACK_POINTS)
                        throw new SAXException("Trace trop volumineuse (limite "
                                + MAX_TRACK_POINTS + " points)");
                    result.points.add(point.freeze(result.points.size()));
                    reportProgress(false);
                } else {
                    result.invalidTrackPoints++;
                }
                point = null;
                pointDepth = -1;
            }
            if (isGpxElement(uri) && "trk".equals(name) && depth == trackDepth) trackDepth = -1;
            depth--;
        }

        @Override public void endDocument() throws SAXException {
            checkCancellation();
            if (!rootSeen) throw new SAXException("Ce fichier n'est pas un GPX (racine <gpx> absente)");
        }

        @Override public void startDTD(String name, String publicId, String systemId)
                throws SAXException {
            throw new SAXException("DOCTYPE et DTD interdits dans un fichier GPX");
        }
        @Override public void endDTD() throws SAXException { }
        @Override public void startEntity(String name) throws SAXException { }
        @Override public void endEntity(String name) throws SAXException { }
        @Override public void startCDATA() throws SAXException { }
        @Override public void endCDATA() throws SAXException { }
        @Override public void comment(char[] ch, int start, int length) throws SAXException { }

        @Override public void error(SAXParseException e) throws SAXException { throw e; }
        @Override public void fatalError(SAXParseException e) throws SAXException { throw e; }

        void checkCancellation() throws ImportCancelledException {
            if (Thread.currentThread().isInterrupted()
                    || (cancellation != null && cancellation.isCancelled()))
                throw new ImportCancelledException();
        }

        void reportProgress(boolean force) {
            if (progress == null) return;
            int points = result.points.size();
            long bytes = counted != null ? counted.count : 0L;
            if (!force && points == lastReportedPoints && bytes - lastReportedBytes < 256L * 1024L)
                return;
            if (!force && points - lastReportedPoints < 500
                    && bytes - lastReportedBytes < 256L * 1024L) return;
            lastReportedPoints = points;
            lastReportedBytes = bytes;
            try { progress.onProgress(points, bytes); }
            catch (RuntimeException ignored) { /* la progression UI ne doit pas interrompre le parsing */ }
        }

        private boolean isInsideTrackSegment() {
            // La présence d'un segment courant est déterminée par la pile SAX.
            // Le booléen du handler évite de confondre les trkseg précédents.
            return activeTrackSegmentDepth > 0;
        }

        private int activeTrackSegmentDepth = -1;

        @Override public void startPrefixMapping(String prefix, String uri) throws SAXException {
            checkCancellation();
        }

        private void applyCapturedValue(String field, String value) {
            if ("trackName".equals(field)) {
                if (result.trackName == null && !value.isEmpty()) result.trackName = value;
                return;
            }
            if (point == null || value.isEmpty()) return;
            double number;
            switch (field) {
                case "ele":
                    number = parseDouble(value);
                    if (Double.isFinite(number)) point.ele = number;
                    break;
                case "time":
                    point.timeIso = value;
                    break;
                case "speed":
                    number = parseDouble(value);
                    if (Float.isFinite((float) number) && number >= 0) point.speedMps = (float) number;
                    break;
                case "course":
                    number = parseDouble(value);
                    if (Float.isFinite((float) number) && number >= 0 && number <= 360)
                        point.courseDeg = (float) number;
                    break;
                case "hdop":
                    number = parseDouble(value);
                    if (Float.isFinite((float) number) && number >= 0) point.hdop = (float) number;
                    break;
                case "accuracy":
                    number = parseDouble(value);
                    if (Float.isFinite((float) number) && number >= 0) point.accuracyM = (float) number;
                    break;
                default: break;
            }
        }

        private boolean isGpxElement(String uri) {
            String candidate = uri != null ? uri : "";
            return rootNamespaceUri.equals(candidate);
        }

        private boolean isPointScalar(String name, String uri) {
            if (isGpxElement(uri)) {
                // Champs standards GPX (speed/course sont directs en 1.0).
                return "ele".equals(name) || "time".equals(name) || "speed".equals(name)
                        || "course".equals(name) || "hdop".equals(name);
            }
            // Extensions GPS Test dans GPX 1.1. Ne pas interpréter un élément
            // homonyme d'un namespace fournisseur arbitraire comme une mesure.
            return "https://example.org/gpstest/ext".equals(uri)
                    && ("speed".equals(name) || "course".equals(name)
                    || "accuracy".equals(name));
        }
    }

    private static String local(String localName, String qName) {
        String name = localName != null && !localName.isEmpty() ? localName : qName;
        if (name == null) return "";
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(colon + 1);
        return name.toLowerCase(Locale.ROOT);
    }

    private static String attribute(Attributes attributes, String name) {
        if (attributes == null) return null;
        String value = attributes.getValue("", name);
        if (value == null) value = attributes.getValue(name);
        if (value == null) {
            for (int i = 0; i < attributes.getLength(); i++) {
                if (name.equals(local(attributes.getLocalName(i), attributes.getQName(i)))) {
                    value = attributes.getValue(i);
                    break;
                }
            }
        }
        return value != null ? value.trim() : null;
    }

    private static boolean validCoords(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && Math.abs(lat) <= 90.0 && Math.abs(lon) <= 180.0
                && !(lat == 0.0 && lon == 0.0);
    }

    /** Analyse un décimal GPX en notation internationale, sans réparation locale. */
    static double parseDouble(String value) {
        if (value == null) return Double.NaN;
        String text = value.trim();
        if (text.isEmpty()) return Double.NaN;
        try {
            double parsed = Double.parseDouble(text);
            return Double.isFinite(parsed) ? parsed : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static boolean isLeapYear(int year) {
        return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    }

    static long parseIso8601(String value) {
        if (value == null) return -1L;
        String text = value.trim();
        if (text.length() < 19 || text.charAt(4) != '-' || text.charAt(7) != '-'
                || (text.charAt(10) != 'T' && text.charAt(10) != 't')
                || text.charAt(13) != ':' || text.charAt(16) != ':') return -1L;
        try {
            int year = Integer.parseInt(text.substring(0, 4));
            int month = Integer.parseInt(text.substring(5, 7));
            int day = Integer.parseInt(text.substring(8, 10));
            int hour = Integer.parseInt(text.substring(11, 13));
            int minute = Integer.parseInt(text.substring(14, 16));
            int second = Integer.parseInt(text.substring(17, 19));
            if (year < 1 || month < 1 || month > 12 || hour > 23 || minute > 59
                    || second > 60 || second < 0) return -1L;
            int[] monthDays = {31, isLeapYear(year) ? 29 : 28, 31, 30, 31, 30,
                    31, 31, 30, 31, 30, 31};
            if (day < 1 || day > monthDays[month - 1]) return -1L;

            int pos = 19;
            long millis = 0L;
            if (pos < text.length() && text.charAt(pos) == '.') {
                pos++;
                int digits = 0;
                long scale = 100L;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    if (digits < 3) millis += (text.charAt(pos) - '0') * scale;
                    digits++;
                    scale /= 10;
                    pos++;
                }
                if (digits == 0) return -1L;
            }

            long offsetMinutes = 0L;
            if (pos < text.length()) {
                char zone = text.charAt(pos++);
                if (zone == 'Z' || zone == 'z') {
                    if (pos != text.length()) return -1L;
                } else if (zone == '+' || zone == '-') {
                    String rest = text.substring(pos);
                    String hoursText, minutesText;
                    if (rest.matches("\\d{2}:\\d{2}")) {
                        hoursText = rest.substring(0, 2); minutesText = rest.substring(3, 5);
                    } else if (rest.matches("\\d{4}")) {
                        hoursText = rest.substring(0, 2); minutesText = rest.substring(2, 4);
                    } else return -1L;
                    int hours = Integer.parseInt(hoursText), minutes = Integer.parseInt(minutesText);
                    if (hours > 14 || minutes > 59 || (hours == 14 && minutes != 0)) return -1L;
                    offsetMinutes = (zone == '-') ? -(hours * 60L + minutes)
                            : (hours * 60L + minutes);
                } else return -1L;
            }
            // Sans suffixe de fuseau, conserver la convention historique du
            // lecteur : interprétation UTC (jamais le fuseau du téléphone).
            long days = daysFromCivil(year, month, day);
            long seconds = days * 86_400L + hour * 3_600L + minute * 60L + second;
            seconds -= offsetMinutes * 60L;
            return seconds * 1000L + millis;
        } catch (Exception e) {
            return -1L;
        }
    }

    static long daysFromCivil(int y, int m, int d) {
        long year = y;
        year -= (m <= 2) ? 1 : 0;
        long era = (year >= 0 ? year : year - 399) / 400;
        long yearOfEra = year - era * 400;
        long dayOfYear = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear;
        return era * 146_097 + dayOfEra - 719_468;
    }

    public static final class Summary {
        public final double distanceM;
        public final int points;
        public final int segments;
        public final long durationMs;
        public final double minEle, maxEle;
        Summary(double distanceM, int points, int segments, long durationMs,
                double minEle, double maxEle) {
            this.distanceM = distanceM; this.points = points; this.segments = segments;
            this.durationMs = durationMs; this.minEle = minEle; this.maxEle = maxEle;
        }
    }

    public static Summary summarize(List<Point> points) {
        if (points == null || points.isEmpty())
            return new Summary(0, 0, 0, 0, Double.NaN, Double.NaN);
        double distance = 0.0;
        double minEle = Double.NaN, maxEle = Double.NaN;
        long firstTime = Long.MAX_VALUE, lastTime = Long.MIN_VALUE;
        int segments = 1;
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            if (Double.isFinite(point.ele)) {
                if (!Double.isFinite(minEle) || point.ele < minEle) minEle = point.ele;
                if (!Double.isFinite(maxEle) || point.ele > maxEle) maxEle = point.ele;
            }
            if (point.timeMs >= 0) {
                if (point.timeMs < firstTime) firstTime = point.timeMs;
                if (point.timeMs > lastTime) lastTime = point.timeMs;
            }
            if (i > 0) {
                Point previous = points.get(i - 1);
                if (previous.segment != point.segment) {
                    segments++;
                } else {
                    distance += FixGeometry.distanceMeters(previous.lat, previous.lon,
                            point.lat, point.lon);
                }
            }
        }
        long duration = firstTime == Long.MAX_VALUE || lastTime == Long.MIN_VALUE
                ? 0L : Math.max(0L, lastTime - firstTime);
        return new Summary(distance, points.size(), segments, duration, minEle, maxEle);
    }
}
