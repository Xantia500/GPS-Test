package com.example.gpstest;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import java.io.*;
import java.util.Locale;
import java.util.ArrayDeque;
import java.util.Deque;

/** Conservative GPX recovery from a synced prefix. The source is never modified. */
public final class GpxRecovery {
    private static final Object LOCK = new Object();
    private static final int BUFFER = 32 * 1024;
    private static final String NS10 = "http://www.topografix.com/GPX/1/0";
    private static final String NS11 = "http://www.topografix.com/GPX/1/1";

    private GpxRecovery() { }

    public enum Status { RECOVERED, INVALID_ARGUMENT, NO_CHECKPOINT, SOURCE_MISSING,
        CHECKPOINT_BEYOND_EOF, SOURCE_CHANGED, INVALID_PREFIX, IO_ERROR }

    public static final class Result {
        public final Status status;
        public final String sourceFileName, recoveredFileName, message;
        public final long checkpointBytes, recoveredBytes;
        public final boolean footerWasAdded;
        private Result(Status s, String source, String recovered, long checkpoint,
                       long bytes, boolean footer, String text) {
            status=s; sourceFileName=source==null?"":source;
            recoveredFileName=recovered==null?"":recovered;
            checkpointBytes=checkpoint; recoveredBytes=bytes;
            footerWasAdded=footer; message=text==null?"":text;
        }
        public boolean isSuccess() { return status == Status.RECOVERED; }
    }

    /** Builds *_RECUPEREE.gpx from exactly [0, safeLengthBytes), never touching source. */
    public static Result recover(File directory, String sourceName, long safeLengthBytes) {
        synchronized (LOCK) { return recoverLocked(directory, sourceName, safeLengthBytes); }
    }

    private static Result recoverLocked(File directory, String sourceName, long checkpoint) {
        if (directory == null || !safeName(sourceName))
            return fail(Status.INVALID_ARGUMENT, sourceName, checkpoint, "Dossier ou nom de fichier GPX invalide.");
        if (checkpoint <= 0L)
            return fail(Status.NO_CHECKPOINT, sourceName, checkpoint,
                    "Aucun checkpoint GPX durable n'est disponible. Le fichier original est conservé.");
        File temp = null;
        try {
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
                return fail(Status.IO_ERROR, sourceName, checkpoint, "Le dossier des traces est inaccessible.");
            File dir = directory.getCanonicalFile();
            File source = new File(dir, sourceName);
            File canonicalSource = source.getCanonicalFile();
            if (!dir.equals(canonicalSource.getParentFile()) || !source.isFile() || !source.canRead())
                return fail(Status.SOURCE_MISSING, sourceName, checkpoint,
                        "Le fichier GPX source est absent ou inaccessible. Il n'a pas été modifié.");

            long beforeLength = source.length(), beforeModified = source.lastModified();
            if (checkpoint > beforeLength)
                return fail(Status.CHECKPOINT_BEYOND_EOF, sourceName, checkpoint,
                        "Le fichier est plus court que le checkpoint durable. Aucune réparation n'a été tentée.");

            temp = File.createTempFile(".gpstest-recovery-", ".tmp", dir);
            copyPrefix(source, temp, checkpoint);
            boolean footerAdded = false;
            if (!isCompleteGpx(temp)) {
                appendFooter(temp);
                footerAdded = true;
            }
            if (!isCompleteGpx(temp))
                return fail(Status.INVALID_PREFIX, sourceName, checkpoint,
                        "Le préfixe synchronisé n'est pas un document GPX récupérable. L'original est intact.");
            if (source.length()!=beforeLength || source.lastModified()!=beforeModified)
                return fail(Status.SOURCE_CHANGED, sourceName, checkpoint,
                        "Le fichier source a changé pendant la récupération. La copie temporaire a été écartée.");

            long recoveredLength = temp.length();
            for (int suffix=1; suffix<=999; suffix++) {
                String targetName = suffix==1 ? recoveredName(sourceName) : recoveredName(sourceName, suffix);
                File target = new File(dir, targetName);
                if (target.exists()) {
                    if (target.isFile() && target.length()==recoveredLength && filesEqual(target, temp)) {
                        temp.delete(); temp=null;
                        return new Result(Status.RECOVERED, sourceName, target.getName(), checkpoint,
                                recoveredLength, footerAdded, "Une copie identique existe déjà ; elle a été réutilisée.");
                    }
                    continue;
                }
                if (!temp.renameTo(target))
                    return fail(Status.IO_ERROR, sourceName, checkpoint,
                            "Impossible de publier la copie GPX récupérée. L'original est intact.");
                temp = null;
                return new Result(Status.RECOVERED, sourceName, target.getName(), checkpoint,
                        recoveredLength, footerAdded, footerAdded
                        ? "Préfixe durable recopié et pied GPX ajouté à la copie."
                        : "Le préfixe durable était déjà un document GPX complet ; copie vérifiée.");
            }
            return fail(Status.IO_ERROR, sourceName, checkpoint,
                    "Impossible de réserver un nom pour la copie récupérée.");
        } catch (Exception e) {
            return fail(Status.IO_ERROR, sourceName, checkpoint,
                    "Erreur de récupération ("+e.getClass().getSimpleName()
                            +(e.getMessage()==null?"":": "+e.getMessage())+"). Le fichier original est conservé.");
        } finally {
            if (temp != null && temp.exists()) temp.delete();
        }
    }

    private static void copyPrefix(File source, File target, long length) throws IOException {
        byte[] buffer = new byte[BUFFER]; long remaining = length;
        try (InputStream in = new BufferedInputStream(new FileInputStream(source), BUFFER);
             FileOutputStream raw = new FileOutputStream(target, false);
             OutputStream out = new BufferedOutputStream(raw, BUFFER)) {
            while (remaining > 0) {
                int n = in.read(buffer, 0, (int)Math.min((long)buffer.length, remaining));
                if (n < 0) throw new IOException("Fin de fichier avant le checkpoint");
                if (n == 0) continue;
                out.write(buffer, 0, n); remaining -= n;
            }
            out.flush(); raw.getFD().sync();
        }
        if (target.length()!=length) throw new IOException("Longueur de copie incohérente");
    }

    private static void appendFooter(File file) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(GpxWriter.footer().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush(); out.getFD().sync();
        }
    }

    /** Secure well-formed XML check; DOCTYPE and external entities are rejected. */
    private static boolean isCompleteGpx(File file) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER)) {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true); factory.setValidating(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            requiredFeature(reader, "http://apache.org/xml/features/disallow-doctype-decl", true);
            requiredFeature(reader, "http://xml.org/sax/features/external-general-entities", false);
            requiredFeature(reader, "http://xml.org/sax/features/external-parameter-entities", false);
            requiredFeature(reader, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            RootHandler handler = new RootHandler(); reader.setContentHandler(handler);
            reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("Entité externe interdite"); });
            reader.parse(new InputSource(in));
            return handler.valid;
        } catch (Exception ignored) { return false; }
    }

    private static void requiredFeature(XMLReader reader, String name, boolean value) throws SAXException {
        reader.setFeature(name, value);
        if (reader.getFeature(name)!=value) throw new SAXException("Protection XML non appliquée : "+name);
    }

    private static final class RootHandler extends DefaultHandler {
        boolean valid; boolean rootSeen; boolean sawTrack; boolean sawTrackSegment;
        String expectedNamespace = "";
        final Deque<String> elements = new ArrayDeque<>();

        @Override public void startElement(String uri, String local, String qName, Attributes attrs)
                throws SAXException {
            String namespace = uri == null ? "" : uri;
            if (!rootSeen) {
                rootSeen=true;
                String version=attrs.getValue("version");
                expectedNamespace = namespace;
                valid="gpx".equals(local) && (("1.0".equals(version) && NS10.equals(namespace))
                        || ("1.1".equals(version) && NS11.equals(namespace)));
                if (!valid) throw new SAXException("Racine, version ou namespace GPX incorrect");
            } else if (namespace.equals(expectedNamespace)) {
                String parent = elements.peek();
                if ("gpx".equals(parent) && "trk".equals(local)) sawTrack=true;
                if ("trk".equals(parent) && "trkseg".equals(local)) sawTrackSegment=true;
            }
            elements.push(local == null ? "" : local);
        }

        @Override public void endElement(String uri, String local, String qName) throws SAXException {
            if (!elements.isEmpty()) elements.pop();
        }

        @Override public void endDocument() throws SAXException {
            if (!valid || !sawTrack || !sawTrackSegment)
                throw new SAXException("La trace ne contient pas la structure gpx/trk/trkseg attendue");
        }
    }

    private static boolean safeName(String value) {
        if (value==null) return false;
        String n=value.trim();
        if (n.isEmpty() || n.length()>512 || ".".equals(n) || "..".equals(n)
                || n.contains("/") || n.contains("\\") || n.contains("\n") || n.contains("\r")
                || !n.toLowerCase(Locale.ROOT).endsWith(".gpx")) return false;
        for(int i=0;i<n.length();i++) if(n.charAt(i)<0x20) return false;
        return true;
    }

    private static String recoveredName(String source) { return recoveredName(source, 1); }
    private static String recoveredName(String source, int suffix) {
        int dot=source.lastIndexOf('.');
        return source.substring(0,dot)+"_RECUPEREE"+(suffix==1?"":"-"+suffix)+source.substring(dot);
    }

    private static boolean filesEqual(File a, File b) throws IOException {
        if(a.length()!=b.length()) return false;
        byte[] x=new byte[BUFFER], y=new byte[BUFFER];
        try(InputStream inA=new BufferedInputStream(new FileInputStream(a),BUFFER);
            InputStream inB=new BufferedInputStream(new FileInputStream(b),BUFFER)) {
            while(true) {
                int na=inA.read(x), nb=inB.read(y);
                if(na!=nb) return false; if(na<0) return true;
                for(int i=0;i<na;i++) if(x[i]!=y[i]) return false;
            }
        }
    }

    private static Result fail(Status status, String name, long checkpoint, String message) {
        return new Result(status,name,"",checkpoint,0L,false,message);
    }
}
