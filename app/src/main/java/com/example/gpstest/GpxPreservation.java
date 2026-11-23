package com.example.gpstest;

import org.xml.sax.Attributes;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.parsers.SAXParserFactory;

/**
 * Second-pass, streaming preservation model used only by the non-destructive
 * GPX cleaner. GpxParser remains the lean reader for map display. This class
 * preserves document/track/segment metadata and the complete child XML of
 * every retained track point without constructing the whole source document.
 *
 * Fragments are serialized from SAX events (not copied as raw source bytes):
 * namespace declarations are made self-contained, text/attributes are escaped,
 * and DTD/external entities are rejected. Unknown extension elements therefore
 * survive semantically even when their namespace was declared on the GPX root.
 */
public final class GpxPreservation {
    private GpxPreservation() { }

    public static final int MAX_PRESERVED_XML_CHARS = 4 * 1024 * 1024;
    private static final int MAX_TRACKS = 20_000;
    private static final int MAX_ROOT_ELEMENTS = 50_000;

    public static final class Document {
        public String version;
        public String creator;
        public String namespaceUri;
        public String metadataXml;
        public final Map<String, String> rootNamespaces = new LinkedHashMap<>();
        public final List<RootAttribute> rootAttributes = new ArrayList<>();
        public final List<String> rootElementsXml = new ArrayList<>();
        /** Ordre des éléments enfants du GPX (waypoint/route/extension/trace). */
        public final List<RootChild> rootChildren = new ArrayList<>();
        public final List<Track> tracks = new ArrayList<>();
        public final List<Segment> segments = new ArrayList<>();
        public final List<Point> points = new ArrayList<>();
        public long preservedChars;
    }

    public static final class RootAttribute {
        public final String name;
        public final String value;
        RootAttribute(String name, String value) { this.name = name; this.value = value; }
    }

    public static final class RootChild {
        public static final int XML = 1;
        public static final int TRACK = 2;
        public final int type;
        public final int index;
        public final String xml;
        private RootChild(int type, int index, String xml) {
            this.type = type; this.index = index; this.xml = xml;
        }
        static RootChild xml(int index, String xml) { return new RootChild(XML, index, xml); }
        static RootChild track(int index) { return new RootChild(TRACK, index, null); }
    }

    public static final class Track {
        public final int index;
        public boolean hasName;
        public String metadataXml = "";
        Track(int index) { this.index = index; }
    }

    public static final class Segment {
        public final int index;
        public final int trackIndex;
        public String extraXml = "";
        Segment(int index, int trackIndex) {
            this.index = index;
            this.trackIndex = trackIndex;
        }
    }

    public static final class Point {
        public final int trackIndex;
        public final int segmentIndex;
        public final String latitudeText;
        public final String longitudeText;
        /** Texte décimal original de l'altitude, si sa valeur numérique est valide. */
        public String elevationText;
        public String childrenXml = "";
        Point(int trackIndex, int segmentIndex, String latitudeText, String longitudeText) {
            this.trackIndex = trackIndex;
            this.segmentIndex = segmentIndex;
            this.latitudeText = latitudeText;
            this.longitudeText = longitudeText;
        }
    }

    public static Document parse(InputStream input, long maxBytes) throws Exception {
        if (input == null) throw new IllegalArgumentException("Flux GPX absent");
        if (maxBytes < 1L) throw new IllegalArgumentException("Limite de lecture GPX invalide");
        Document document = new Document();
        LimitedInputStream limited = new LimitedInputStream(input, maxBytes);
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        XMLReader reader = factory.newSAXParser().getXMLReader();
        PreservingHandler handler = new PreservingHandler(document);
        reader.setContentHandler(handler);
        reader.setErrorHandler(handler);
        reader.setEntityResolver(new EntityResolver() {
            @Override public InputSource resolveEntity(String publicId, String systemId)
                    throws SAXException {
                throw new SAXException("Entité XML externe interdite");
            }
        });
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
        reader.parse(new InputSource(limited));
        if (limited.limitExceeded) throw new IOException("Fichier GPX trop volumineux");
        if (!handler.rootSeen) throw new SAXException("Racine GPX absente");
        if (document.points.isEmpty()) throw new SAXException("Aucun point GPX à préserver");
        return document;
    }

    private static final class LimitedInputStream extends FilterInputStream {
        final long limit;
        long count;
        boolean limitExceeded;
        LimitedInputStream(InputStream in, long limit) { super(in); this.limit = limit; }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) add(1);
            return value;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            int n = super.read(b, off, len);
            if (n > 0) add(n);
            return n;
        }
        @Override public long skip(long n) throws IOException {
            if (n <= 0) return 0;
            byte[] scratch = new byte[(int) Math.min(4096L, n)];
            long skipped = 0;
            while (skipped < n) {
                int read = read(scratch, 0, (int) Math.min(scratch.length, n - skipped));
                if (read < 0) break;
                skipped += read;
            }
            return skipped;
        }
        private void add(int n) throws IOException {
            count += n;
            if (count > limit) {
                limitExceeded = true;
                throw new IOException("Limite de lecture GPX dépassée");
            }
        }
    }

    private static final class Fragment {
        static final int ROOT_METADATA = 1;
        static final int ROOT_ELEMENT = 2;
        static final int TRACK_METADATA = 3;
        static final int SEGMENT_EXTRA = 4;
        static final int POINT_CHILDREN = 5;
        final int type;
        final int depth;
        final StringBuilder xml = new StringBuilder(256);
        final String rootQName;
        Fragment(int type, int depth, String rootQName) {
            this.type = type; this.depth = depth; this.rootQName = rootQName;
        }
    }

    private static final class PreservingHandler extends DefaultHandler implements LexicalHandler {
        private static final String NO_MAPPING = "\u0000";
        private final Document document;
        private int depth;
        private boolean rootSeen;
        private int trackDepth = -1;
        private int segmentDepth = -1;
        private int pointDepth = -1;
        private int trackIndex = -1;
        private int segmentIndex = -1;
        private int implicitSegmentTrack = -1;
        private int implicitSegmentIndex = -1;
        private Track currentTrack;
        private Segment currentSegment;
        private Point currentPoint;
        private int elevationDepth = -1;
        private StringBuilder elevationValue;
        private Fragment fragment;
        private final Map<String, String> namespaces = new LinkedHashMap<>();
        private final Map<String, String> pendingNamespaces = new LinkedHashMap<>();
        private final Map<String, Deque<String>> namespaceHistory = new HashMap<>();

        PreservingHandler(Document document) { this.document = document; }

        @Override public void startPrefixMapping(String prefix, String uri) {
            String p = prefix != null ? prefix : "";
            Deque<String> history = namespaceHistory.get(p);
            if (history == null) {
                history = new ArrayDeque<>();
                namespaceHistory.put(p, history);
            }
            history.push(namespaces.containsKey(p) ? namespaces.get(p) : NO_MAPPING);
            namespaces.put(p, uri != null ? uri : "");
            pendingNamespaces.put(p, uri != null ? uri : "");
        }

        @Override public void endPrefixMapping(String prefix) {
            String p = prefix != null ? prefix : "";
            Deque<String> history = namespaceHistory.get(p);
            if (history == null || history.isEmpty()) return;
            String previous = history.pop();
            if (NO_MAPPING.equals(previous)) namespaces.remove(p);
            else namespaces.put(p, previous);
            if (history.isEmpty()) namespaceHistory.remove(p);
        }

        @Override public void startElement(String uri, String localName, String qName,
                                           Attributes attributes) throws SAXException {
            depth++;
            String name = local(localName, qName);
            if (depth > GpxParser.MAX_XML_DEPTH)
                throw new SAXException("Document XML trop profondément imbriqué");
            if (attributes != null && attributes.getLength() > 128)
                throw new SAXException("Trop d'attributs sur une balise XML");

            if (depth == 1 && "gpx".equals(name)) {
                rootSeen = true;
                document.namespaceUri = uri;
                document.version = attribute(attributes, "version");
                document.creator = attribute(attributes, "creator");
                document.rootNamespaces.putAll(namespaces);
                if (attributes != null) {
                    for (int i = 0; i < attributes.getLength(); i++) {
                        String attrName = attributes.getQName(i);
                        if (attrName == null || attrName.isEmpty()) {
                            attrName = attributeName(attributes.getURI(i), attributes.getLocalName(i));
                        }
                        if (attrName == null || attrName.isEmpty() || "version".equals(attrName)
                                || "creator".equals(attrName) || "xmlns".equals(attrName)
                                || attrName.startsWith("xmlns:")) continue;
                        document.rootAttributes.add(new RootAttribute(attrName, attributes.getValue(i)));
                    }
                }
                pendingNamespaces.clear();
                return;
            }
            if (!rootSeen) return;
            boolean gpxElement = isGpxElement(uri);

            if (gpxElement && "trk".equals(name)) {
                if (document.tracks.size() >= MAX_TRACKS)
                    throw new SAXException("Trop de traces GPX");
                trackIndex = document.tracks.size();
                currentTrack = new Track(trackIndex);
                document.tracks.add(currentTrack);
                if (depth == 2) document.rootChildren.add(RootChild.track(trackIndex));
                trackDepth = depth;
            } else if (gpxElement && "trkseg".equals(name) && currentTrack != null) {
                segmentIndex = document.segments.size();
                currentSegment = new Segment(segmentIndex, trackIndex);
                document.segments.add(currentSegment);
                segmentDepth = depth;
                implicitSegmentTrack = -1;
                implicitSegmentIndex = -1;
            } else if (gpxElement && "trkpt".equals(name) && currentTrack != null) {
                if (segmentDepth < 0) {
                    if (implicitSegmentTrack != trackIndex) {
                        segmentIndex = document.segments.size();
                        currentSegment = new Segment(segmentIndex, trackIndex);
                        document.segments.add(currentSegment);
                        implicitSegmentTrack = trackIndex;
                        implicitSegmentIndex = segmentIndex;
                    } else {
                        segmentIndex = implicitSegmentIndex;
                    }
                }
                String lat = attribute(attributes, "lat");
                String lon = attribute(attributes, "lon");
                if (validCoords(lat, lon)) {
                    currentPoint = new Point(trackIndex, segmentIndex, lat, lon);
                } else {
                    currentPoint = null;
                }
                pointDepth = depth;
            }

            if (currentPoint != null && pointDepth > 0 && depth == pointDepth + 1
                    && gpxElement && "ele".equals(name)) {
                elevationDepth = depth;
                elevationValue = new StringBuilder(24);
            }

            if (gpxElement && currentTrack != null && trackDepth > 0
                    && depth == trackDepth + 1 && "name".equals(name)) {
                currentTrack.hasName = true;
            }

            if (fragment == null) {
                if (depth == 2 && !(gpxElement && "trk".equals(name))) {
                    if (gpxElement && "metadata".equals(name)) beginFragment(Fragment.ROOT_METADATA, qName, localName, uri, attributes);
                    else beginFragment(Fragment.ROOT_ELEMENT, qName, localName, uri, attributes);
                } else if (currentPoint != null && pointDepth > 0 && depth == pointDepth + 1
                        && !isRebuiltPointField(name, uri)) {
                    beginFragment(Fragment.POINT_CHILDREN, qName, localName, uri, attributes);
                } else if (currentSegment != null && segmentDepth > 0
                        && depth == segmentDepth + 1 && !(gpxElement && "trkpt".equals(name))) {
                    beginFragment(Fragment.SEGMENT_EXTRA, qName, localName, uri, attributes);
                } else if (currentTrack != null && trackDepth > 0
                        && depth == trackDepth + 1 && !(gpxElement && "trkseg".equals(name))) {
                    beginFragment(Fragment.TRACK_METADATA, qName, localName, uri, attributes);
                }
            } else {
                appendStart(fragment.xml, qName, localName, uri, attributes, false);
                pendingNamespaces.clear();
            }
        }

        private void beginFragment(int type, String qName, String localName, String uri,
                                   Attributes attributes) throws SAXException {
            String qualified = elementName(uri, localName, qName);
            fragment = new Fragment(type, depth, qualified);
            appendStart(fragment.xml, qName, localName, uri, attributes, true);
            pendingNamespaces.clear();
        }

        @Override public void characters(char[] ch, int start, int length) throws SAXException {
            if (length <= 0) return;
            if (elevationValue != null && elevationDepth > 0 && depth >= elevationDepth) {
                if (elevationValue.length() + length > GpxParser.MAX_SCALAR_CHARS) {
                    throw new SAXException("Altitude GPX trop longue");
                }
                elevationValue.append(ch, start, length);
            }
            if (fragment == null) return;
            StringBuilder escaped = new StringBuilder(length + 16);
            for (int i = start; i < start + length; i++) {
                char c = ch[i];
                switch (c) {
                    case '&': escaped.append("&amp;"); break;
                    case '<': escaped.append("&lt;"); break;
                    case '>': escaped.append("&gt;"); break;
                    default: escaped.append(c); break;
                }
            }
            append(fragment.xml, escaped.toString());
        }

        @Override public void endElement(String uri, String localName, String qName)
                throws SAXException {
            String name = local(localName, qName);
            if (currentPoint != null && isGpxElement(uri) && "ele".equals(name)
                    && depth == elevationDepth && elevationValue != null) {
                String candidate = elevationValue.toString().trim();
                try {
                    double value = Double.parseDouble(candidate);
                    if (Double.isFinite(value)
                            && candidate.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) {
                        currentPoint.elevationText = candidate;
                    }
                } catch (NumberFormatException ignored) { }
                elevationValue = null;
                elevationDepth = -1;
            }
            if (fragment != null) {
                String closingName = qName != null && !qName.isEmpty()
                        ? qName : elementName(uri, localName, qName);
                append(fragment.xml, "</" + closingName + ">");
                if (depth == fragment.depth) finishFragment();
            }
            if (isGpxElement(uri) && "trkpt".equals(name) && depth == pointDepth) {
                if (currentPoint != null) {
                    if (document.points.size() >= GpxParser.MAX_TRACK_POINTS)
                        throw new SAXException("Trop de points à préserver");
                    document.points.add(currentPoint);
                }
                currentPoint = null;
                pointDepth = -1;
                elevationValue = null;
                elevationDepth = -1;
            }
            if (isGpxElement(uri) && "trkseg".equals(name) && depth == segmentDepth) {
                currentSegment = null;
                segmentDepth = -1;
                segmentIndex = -1;
            }
            if (isGpxElement(uri) && "trk".equals(name) && depth == trackDepth) {
                currentTrack = null;
                trackDepth = -1;
                trackIndex = -1;
                currentSegment = null;
                segmentDepth = -1;
                implicitSegmentTrack = -1;
                implicitSegmentIndex = -1;
            }
            depth--;
        }

        private void finishFragment() throws SAXException {
            String xml = fragment.xml.toString();
            switch (fragment.type) {
                case Fragment.ROOT_METADATA:
                    document.metadataXml = xml;
                    break;
                case Fragment.ROOT_ELEMENT:
                    if (document.rootElementsXml.size() >= MAX_ROOT_ELEMENTS)
                        throw new SAXException("Trop d'éléments GPX à préserver");
                    int rootIndex = document.rootElementsXml.size();
                    document.rootElementsXml.add(xml);
                    document.rootChildren.add(RootChild.xml(rootIndex, xml));
                    break;
                case Fragment.TRACK_METADATA:
                    if (currentTrack != null) currentTrack.metadataXml += xml + "\n";
                    break;
                case Fragment.SEGMENT_EXTRA:
                    if (currentSegment != null) currentSegment.extraXml += xml + "\n";
                    break;
                case Fragment.POINT_CHILDREN:
                    if (currentPoint != null) currentPoint.childrenXml += xml + "\n";
                    break;
                default: break;
            }
            fragment = null;
        }

        private void appendStart(StringBuilder out, String qName, String localName, String uri,
                                 Attributes attributes, boolean fragmentRoot) throws SAXException {
            String element = elementName(uri, localName, qName);
            append(out, "<" + element);
            Map<String, String> declarations = fragmentRoot
                    ? new LinkedHashMap<>(namespaces) : new LinkedHashMap<>(pendingNamespaces);
            for (Map.Entry<String, String> e : declarations.entrySet()) {
                String prefix = e.getKey();
                if ("xml".equals(prefix) || "xmlns".equals(prefix)) continue;
                String attr = prefix.isEmpty() ? " xmlns=\"" : " xmlns:" + prefix + "=\"";
                append(out, attr + escapeAttribute(e.getValue()) + "\"");
            }
            if (attributes != null) {
                for (int i = 0; i < attributes.getLength(); i++) {
                    String attrQName = attributes.getQName(i);
                    String attrName;
                    if (attrQName != null && !attrQName.isEmpty()) attrName = attrQName;
                    else attrName = attributeName(attributes.getURI(i), attributes.getLocalName(i));
                    if (attrName == null || attrName.isEmpty() || "xmlns".equals(attrName)
                            || attrName.startsWith("xmlns:")) continue;
                    append(out, " " + attrName + "=\"" + escapeAttribute(attributes.getValue(i)) + "\"");
                }
            }
            append(out, ">");
        }

        private String elementName(String uri, String localName, String qName) {
            if (qName != null && !qName.isEmpty()) return qName;
            String local = localName != null ? localName : "node";
            if (uri == null || uri.isEmpty()) return local;
            for (Map.Entry<String, String> e : namespaces.entrySet()) {
                if (uri.equals(e.getValue())) {
                    return e.getKey().isEmpty() ? local : e.getKey() + ":" + local;
                }
            }
            return local;
        }

        private String attributeName(String uri, String localName) {
            String local = localName != null ? localName : "attr";
            if (uri == null || uri.isEmpty()) return local;
            for (Map.Entry<String, String> e : namespaces.entrySet()) {
                if (!e.getKey().isEmpty() && uri.equals(e.getValue())) return e.getKey() + ":" + local;
            }
            return local;
        }

        private void append(StringBuilder out, String value) throws SAXException {
            document.preservedChars += value.length();
            if (document.preservedChars > MAX_PRESERVED_XML_CHARS)
                throw new SAXException("Métadonnées et extensions GPX trop volumineuses (limite 4 Mio)");
            out.append(value);
        }

        @Override public void startDTD(String name, String publicId, String systemId)
                throws SAXException {
            throw new SAXException("DOCTYPE et DTD interdits dans un fichier GPX");
        }
        @Override public void endDTD() { }
        @Override public void startEntity(String name) { }
        @Override public void endEntity(String name) { }
        @Override public void startCDATA() { }
        @Override public void endCDATA() { }
        @Override public void comment(char[] ch, int start, int length) { }

        // <ele> et <time> sont conservés sous forme de valeurs décodées : cela
        // évite de stocker des dizaines de mégaoctets de balises répétitives
        // sur une trace 1 Hz. Le writer réémet l'altitude sans arrondi et le
        // texte ISO d'origine (fraction/fuseau inclus), uniquement s'il est valide.
        private boolean isGpxElement(String uri) {
            String candidate = uri != null ? uri : "";
            return (document.namespaceUri != null ? document.namespaceUri : "").equals(candidate);
        }

        private boolean isRebuiltPointField(String name, String uri) {
            boolean coreName = "ele".equals(name) || "time".equals(name);
            if (!coreName) return false;
            // Un élément d'extension fournisseur nommé par hasard "ele" ou
            // "time" ne doit jamais être consommé comme champ GPX standard.
            return isGpxElement(uri);
        }

        private static boolean validCoords(String latText, String lonText) {
            if (latText == null || lonText == null) return false;
            try {
                double lat = Double.parseDouble(latText.trim());
                double lon = Double.parseDouble(lonText.trim());
                return Double.isFinite(lat) && Double.isFinite(lon)
                        && Math.abs(lat) <= 90.0 && Math.abs(lon) <= 180.0
                        && !(lat == 0.0 && lon == 0.0);
            } catch (NumberFormatException e) { return false; }
        }

        private static String local(String localName, String qName) {
            String value = localName != null && !localName.isEmpty() ? localName : qName;
            if (value == null) return "";
            int colon = value.indexOf(':');
            if (colon >= 0) value = value.substring(colon + 1);
            return value.toLowerCase(Locale.ROOT);
        }

        private static String attribute(Attributes attrs, String name) {
            if (attrs == null) return null;
            String v = attrs.getValue("", name);
            if (v == null) v = attrs.getValue(name);
            if (v == null) {
                for (int i = 0; i < attrs.getLength(); i++) {
                    if (name.equals(local(attrs.getLocalName(i), attrs.getQName(i)))) {
                        v = attrs.getValue(i); break;
                    }
                }
            }
            return v != null ? v.trim() : null;
        }
    }

    private static String escapeAttribute(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace("\"", "&quot;").replace("\r", "&#13;").replace("\n", "&#10;").replace("\t", "&#9;");
    }
}
