package com.example.gpstest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * NETTOYAGE D'UN FICHIER GPX TERMINÉ — v5.11
 *
 * ══════════════════════════════════════════════════════════════════════
 * POURQUOI ON N'ÉCRASE JAMAIS L'ORIGINAL
 * ══════════════════════════════════════════════════════════════════════
 *
 * Le nettoyage est une DESTRUCTION D'INFORMATION. Il retire des points.
 * Même bien fait, il peut se tromper : une tolérance trop grande sur une
 * trace de trail en lacets supprimerait les virages, et personne ne s'en
 * apercevrait avant d'avoir perdu l'original.
 *
 * → On écrit `Trace_2026_11_22_12h23m_nettoye.GPX` A COTÉ de l'original.
 *   L'utilisateur compare, garde celui qu'il préfère, supprime l'autre
 *   quand il est sûr.
 *
 * C'est la même logique que la sauvegarde avant modification : ce qui
 * détruit doit être réversible.
 *
 * ══════════════════════════════════════════════════════════════════════
 * POURQUOI LE TRAITEMENT N'A PAS LIEU PENDANT L'ENREGISTREMENT
 * ══════════════════════════════════════════════════════════════════════
 *
 * En direct, on ne connaît pas la suite. Un point qui paraît aberrant
 * devient parfois juste quand on voit les dix suivants (démarrage,
 * sortie de tunnel, recalage d'altitude). Filtrer agressivement en direct,
 * c'est trancher avec la moitié de l'information.
 *
 * → On enregistre honnêtement, on nettoie ensuite. C'est exactement votre
 *   proposition, et elle est meilleure que le filtrage en direct.
 */
public final class TrackCleaner {

    private TrackCleaner() { }

    /** Suffixe du fichier nettoyé. */
    public static final String SUFFIX = "_nettoye";

    public static final class Outcome {
        public final boolean success;
        public final File file;
        public final String message;
        public final TrackPostProcess.Report report;

        Outcome(boolean success, File file, String message,
                TrackPostProcess.Report report) {
            this.success = success;
            this.file = file;
            this.message = message;
            this.report = report;
        }
    }

    /**
     * Lit un GPX, le nettoie, écrit le résultat à côté de l'original.
     *
     * @param gpxFile       fichier source (jamais modifié)
     * @param toleranceM    tolérance Douglas-Peucker
     * @param maxSpeedMps   vitesse plafond pour le rejet des aberrants
     * @param version       version GPX d'écriture (1.0 ou 1.1)
     * @param trackName     nom à inscrire dans le fichier
     */
    public static Outcome clean(File gpxFile, double toleranceM,
                                double maxSpeedMps,
                                GpxWriter.Version version,
                                String trackName) {
        if (gpxFile == null || !gpxFile.exists()) {
            return new Outcome(false, null, "Fichier introuvable", null);
        }

        final long maxInputBytes = 24L * 1024L * 1024L;
        final long sourceLengthAtStart = gpxFile.length();
        final long sourceModifiedAtStart = gpxFile.lastModified();
        if (sourceLengthAtStart > maxInputBytes) {
            return new Outcome(false, null, "Fichier trop volumineux pour le nettoyage (24 Mio max)", null);
        }
        GpxParser.Result parsed;
        GpxPreservation.Document preserved;
        try (FileInputStream input = new FileInputStream(gpxFile)) {
            // Première passe : modèle géométrique léger utilisé par le filtre.
            parsed = GpxParser.parse(input, maxInputBytes, null, null);
        } catch (OutOfMemoryError oom) {
            return new Outcome(false, null, "Mémoire insuffisante pendant la lecture GPX ; l'original est intact", null);
        } catch (Exception e) {
            return new Outcome(false, null, "Lecture impossible : "
                    + e.getClass().getSimpleName(), null);
        }

        if (parsed.failed || parsed.points.isEmpty()) {
            return new Outcome(false, null,
                    parsed.error != null ? parsed.error : "Aucun point à nettoyer", null);
        }

        // Deuxième passe SAX : conserve les métadonnées, tous les champs GPX
        // non utilisés par la géométrie, les extensions inconnues, les routes,
        // les waypoints ainsi que les frontières entre plusieurs <trk>.
        // Le fichier est relu en flux ; aucune String XML complète n'est créée.
        try (FileInputStream input = new FileInputStream(gpxFile)) {
            preserved = GpxPreservation.parse(input, maxInputBytes);
        } catch (OutOfMemoryError oom) {
            return new Outcome(false, null,
                    "Mémoire insuffisante pendant la préservation GPX ; l'original est intact", null);
        } catch (Exception e) {
            return new Outcome(false, null, "Préservation GPX impossible : "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), null);
        }
        if (gpxFile.length() != sourceLengthAtStart
                || gpxFile.lastModified() != sourceModifiedAtStart) {
            return new Outcome(false, null,
                    "Le fichier source a changé pendant sa lecture ; original conservé", null);
        }
        if (preserved.points.size() != parsed.points.size()) {
            return new Outcome(false, null,
                    "Incohérence entre lecture GPX et préservation des points ; original conservé", null);
        }
        if (preserved.tracks.isEmpty()) {
            return new Outcome(false, null,
                    "Aucune trace GPX identifiable pour le nettoyage", null);
        }
        for (int i = 0; i < parsed.points.size(); i++) {
            GpxParser.Point parsedPoint = parsed.points.get(i);
            GpxPreservation.Point rawPoint = preserved.points.get(i);
            double rawLat;
            double rawLon;
            try {
                rawLat = Double.parseDouble(rawPoint.latitudeText);
                rawLon = Double.parseDouble(rawPoint.longitudeText);
            } catch (Exception invalidCoordinate) {
                return new Outcome(false, null,
                        "Coordonnée source impossible à préserver ; original conservé", null);
            }
            if (parsedPoint.segment != rawPoint.segmentIndex
                    || Double.compare(parsedPoint.lat, rawLat) != 0
                    || Double.compare(parsedPoint.lon, rawLon) != 0) {
                return new Outcome(false, null,
                        "Les points/segments GPX ne correspondent pas entre les lectures ; original conservé", null);
            }
        }

        // Conversion vers la vue de nettoyage. On conserve l'altitude : elle
        // ne sert pas au calcul mais doit survivre au fichier.
        List<TrackPostProcess.P> in;
        TrackPostProcess.Report report = new TrackPostProcess.Report();
        List<TrackPostProcess.P> out;
        try {
            in = new ArrayList<>(parsed.points.size());
            for (int i = 0; i < parsed.points.size(); i++) {
                GpxParser.Point p = parsed.points.get(i);
                GpxPreservation.Point raw = preserved.points.get(i);
                in.add(new TrackPostProcess.P(p.lat, p.lon, p.ele, p.timeMs, p.segment,
                        raw.trackIndex, p.speedMps, p.courseDeg, p.hdop, p.accuracyM,
                        p.timeIso, raw.elevationText, raw.childrenXml,
                        raw.latitudeText, raw.longitudeText));
            }
            // Libérer tôt les objets du parseur : la liste de travail ne conserve
            // que les champs utiles au post-traitement. Aucun String XML géant n'a
            // été construit pendant la lecture.
            parsed.points.clear();
            preserved.points.clear(); // les fragments sont maintenant référencés par les P retenus.
            out = TrackPostProcess.clean(in, toleranceM, maxSpeedMps, report);
        } catch (OutOfMemoryError oom) {
            parsed.points.clear();
            return new Outcome(false, null,
                    "Mémoire insuffisante pendant le nettoyage ; l'original est intact", report);
        }

        if (out.size() < 2) {
            return new Outcome(false, null,
                    "Le nettoyage ne laisserait que " + out.size()
                            + " point(s) : fichier conservé tel quel", report);
        }

        GpxWriter.Version sourceVersion = versionFromSource(
                preserved.version, preserved.namespaceUri, version);
        if (sourceVersion == null) {
            return new Outcome(false, null,
                    "Version GPX déclarée et espace de noms incompatibles ; original conservé", report);
        }

        File dest;
        try {
            dest = reserveSiblingFile(gpxFile);
        } catch (Exception e) {
            return new Outcome(false, null,
                    "Impossible de réserver un nom de fichier nettoyé : "
                            + e.getClass().getSimpleName(), report);
        }
        // Ne pas recopier le nom de la première trace sur toutes les autres.
        // En l'absence de nom source, on n'en fabrique pas implicitement.
        String outputName = trackName;
        try (FileOutputStream output = new FileOutputStream(dest, false);
             Writer writer = new OutputStreamWriter(output, java.nio.charset.StandardCharsets.UTF_8)) {
            // Sérialisation point par point : ne pas fabriquer une deuxième
            // chaîne géante (StringBuilder + String) pour les longues traces.
            serializePreserving(writer, out, sourceVersion, outputName, preserved);
            writer.flush();
            // Le nettoyage est un fichier distinct : on synchronise à sa
            // fermeture pour ne pas annoncer un fichier terminé encore vide
            // en cas de perte brutale du processus juste après le retour.
            output.getFD().sync();
        } catch (OutOfMemoryError oom) {
            // Un fichier partiel ne doit jamais ressembler à une trace terminée.
            try { if (dest.exists()) dest.delete(); }
            catch (Exception ignored) { }
            return new Outcome(false, null,
                    "Mémoire insuffisante pendant l'écriture ; l'original est intact", report);
        } catch (Exception e) {
            // Ne jamais laisser un fichier nettoyé partiel ressemblant à un
            // GPX complet. L'original reste intact et constitue le secours.
            try { if (dest.exists()) dest.delete(); }
            catch (Exception ignored) { }
            return new Outcome(false, null, "Écriture impossible : "
                    + e.getClass().getSimpleName(), report);
        }

        return new Outcome(true, dest, report.describe(), report);
    }

    /**
     * Réserve atomiquement un nom libre. Évite qu'une seconde opération
     * simultanée tronque un fichier nettoyé créé entre exists() et open().
     */
    private static File reserveSiblingFile(File source) throws java.io.IOException {
        File parent = source.getAbsoluteFile().getParentFile();
        String name = source.getName();
        int dot = name.lastIndexOf('.');
        String base = (dot > 0) ? name.substring(0, dot) : name;
        String ext = (dot > 0) ? name.substring(dot) : ".gpx";
        for (int i = 1; i < 1000; i++) {
            String suffix = i == 1 ? SUFFIX : SUFFIX + "-" + i;
            File candidate = new File(parent, base + suffix + ext);
            if (candidate.createNewFile()) return candidate;
        }
        File candidate = new File(parent, base + SUFFIX + "-" + System.nanoTime() + ext);
        if (candidate.createNewFile()) return candidate;
        throw new java.io.IOException("Aucun nom de fichier libre");
    }

    /** `Trace_x.GPX` → `Trace_x_nettoye.GPX`, dans le même répertoire. */
    static File siblingName(File source) {
        String name = source.getName();
        int dot = name.lastIndexOf('.');
        String base = (dot > 0) ? name.substring(0, dot) : name;
        String ext = (dot > 0) ? name.substring(dot) : ".gpx";
        File candidate = new File(source.getParentFile(), base + SUFFIX + ext);
        if (!candidate.exists()) return candidate;
        for (int i = 2; i < 1000; i++) {
            candidate = new File(source.getParentFile(), base + SUFFIX + "-" + i + ext);
            if (!candidate.exists()) return candidate;
        }
        return new File(source.getParentFile(), base + SUFFIX + "-" + System.currentTimeMillis() + ext);
    }

    // ------------------------------------------------------------------
    // Sérialisation
    // ------------------------------------------------------------------

    /**
     * Réécrit la trace nettoyée en GPX.
     *
     * POINT DÉLICAT — L'HORODATAGE. On ne réinvente pas les temps : chaque
     * point conserve SON horodatage d'origine. Un point retenu à 12:03:17
     * reste à 12:03:17, même si les points de 12:03:18 à 12:03:40 ont été
     * supprimés. C'est ce qui permet à un lecteur de voir la trace comme
     * un échantillonnage irrégulier — ce qu'elle est — plutôt que de
     * fabriquer un faux 1 Hz qui suggérerait une précision qui n'existe
     * pas.
     */
    static String serialize(List<TrackPostProcess.P> pts,
                            GpxWriter.Version version,
                            String trackName,
                            String fallbackName) {
        StringWriter result = new StringWriter(Math.max(512, pts.size() * 160));
        try {
            serializeTo(result, pts, version,
                    trackName != null ? trackName : fallbackName);
        } catch (java.io.IOException impossible) {
            // StringWriter ne lève pas d'IOException.
            throw new IllegalStateException(impossible);
        }
        return result.toString();
    }

    /** Écriture en flux : mémoire bornée par le point courant, pas par tout le GPX sérialisé. */
    static void serializeTo(Writer writer, List<TrackPostProcess.P> pts,
                            GpxWriter.Version version, String trackName)
            throws java.io.IOException {
        if (writer == null) throw new IllegalArgumentException("writer null");
        if (pts == null || pts.isEmpty()) throw new IllegalArgumentException("aucun point");
        long start = 0L;
        for (TrackPostProcess.P p : pts) {
            if (p.timeMs > 0) { start = p.timeMs; break; }
        }
        if (start <= 0) start = System.currentTimeMillis();
        writer.write(GpxWriter.header(version, trackName, start));

        int currentSegment = pts.get(0).segment;
        for (int i = 0; i < pts.size(); i++) {
            TrackPostProcess.P p = pts.get(i);
            if (i > 0 && p.segment != currentSegment) {
                writer.write(GpxWriter.segmentBreak(version));
                currentSegment = p.segment;
            }
            // Aucun horodatage de substitution : l'absence d'heure dans la
            // source reste une absence d'heure dans le fichier nettoyé.
            GpxWriter.TrackPoint tp = new GpxWriter.TrackPoint(
                    p.timeMs, p.lat, p.lon, p.ele);
            tp.speedMps = p.speedMps;
            tp.courseDeg = p.courseDeg;
            tp.hdop = p.hdop;
            tp.accuracyM = p.accuracyM;
            writer.write(GpxWriter.pointXml(tp, version));
        }
        writer.write(GpxWriter.footer());
    }

    /**
     * Réécriture fidèle du document : les objets géométriques seuls sont
     * simplifiés ; les autres éléments XML sont conservés depuis le second
     * passage SAX. La version source est conservée (aucune conversion implicite).
     */
    static void serializePreserving(Writer writer, List<TrackPostProcess.P> points,
                                    GpxWriter.Version version, String fallbackTrackName,
                                    GpxPreservation.Document source) throws java.io.IOException {
        if (writer == null) throw new IllegalArgumentException("writer null");
        if (points == null || points.isEmpty()) throw new IllegalArgumentException("aucun point");
        if (source == null || source.tracks.isEmpty())
            throw new IllegalArgumentException("métadonnées source absentes");

        writer.write(GpxWriter.cleanedDocumentHeader(version, source.creator, source.metadataXml,
                source.rootNamespaces, source.rootAttributes));

        int pointCursor = 0;
        for (GpxPreservation.RootChild child : source.rootChildren) {
            if (child.type == GpxPreservation.RootChild.XML) {
                if (child.xml != null) writer.write(child.xml);
                writer.write('\n');
                continue;
            }
            if (child.type != GpxPreservation.RootChild.TRACK
                    || child.index < 0 || child.index >= source.tracks.size()) {
                throw new java.io.IOException("Ordre des éléments racine GPX incohérent");
            }
            GpxPreservation.Track track = source.tracks.get(child.index);
            String fallback = (track.index == 0) ? fallbackTrackName : null;
            writer.write(GpxWriter.cleanedTrackOpen(track.metadataXml, fallback, track.hasName));

            for (GpxPreservation.Segment segment : source.segments) {
                if (segment.trackIndex != track.index) continue;
                writer.write(GpxWriter.cleanedSegmentOpen());
                while (pointCursor < points.size()
                        && points.get(pointCursor).trackIndex == track.index
                        && points.get(pointCursor).segment == segment.index) {
                    TrackPostProcess.P point = points.get(pointCursor++);
                    writer.write(GpxWriter.cleanedPointOpen(point.lat, point.lon,
                            point.latitudeText, point.longitudeText));
                    writer.write(GpxWriter.cleanedPointCore(point.ele, point.elevationText,
                            point.timeIso, point.timeMs));
                    if (point.childrenXml != null && !point.childrenXml.isEmpty()) {
                        writer.write(point.childrenXml);
                    }
                    writer.write("      </trkpt>\n");
                }
                writer.write(GpxWriter.cleanedSegmentClose(segment.extraXml));
            }
            writer.write(GpxWriter.cleanedTrackClose());
        }
        if (pointCursor != points.size()) {
            throw new java.io.IOException("Un ou plusieurs points nettoyés n'ont pas retrouvé leur trace/segment source");
        }
        writer.write(GpxWriter.cleanedDocumentFooter());
    }

    private static GpxWriter.Version versionFromSource(String sourceVersion,
                                                        String namespaceUri,
                                                        GpxWriter.Version requestedFallback) {
        GpxWriter.Version attributeVersion = null;
        boolean hasVersionAttribute = sourceVersion != null && !sourceVersion.trim().isEmpty();
        if ("1.0".equals(sourceVersion)) attributeVersion = GpxWriter.Version.V10;
        else if ("1.1".equals(sourceVersion)) attributeVersion = GpxWriter.Version.V11;
        // Ne jamais convertir silencieusement un format futur/inconnu en 1.0 ou 1.1.
        else if (hasVersionAttribute) return null;

        GpxWriter.Version namespaceVersion = null;
        boolean hasNamespace = namespaceUri != null && !namespaceUri.isEmpty();
        if ("http://www.topografix.com/GPX/1/0".equals(namespaceUri))
            namespaceVersion = GpxWriter.Version.V10;
        else if ("http://www.topografix.com/GPX/1/1".equals(namespaceUri))
            namespaceVersion = GpxWriter.Version.V11;
        // Un namespace non reconnu ne doit pas être réécrit comme du GPX standard.
        else if (hasNamespace) return null;

        if (attributeVersion != null && namespaceVersion != null
                && attributeVersion != namespaceVersion) return null;
        if (namespaceVersion != null) return namespaceVersion;
        if (attributeVersion != null) return attributeVersion;
        return requestedFallback != null ? requestedFallback : GpxWriter.Version.V11;
    }

}
