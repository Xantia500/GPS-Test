package com.example.gpstest;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Génération d'une trace GPX (GPS Exchange Format).
 *
 * GPX est le format standard, lisible par QGIS, Organic Maps, Google Earth,
 * OsmAnd... C'est ce qui transforme l'app d'un "testeur" en "outil".
 *
 * Classe 100 % PURE : le fichier produit est du texte, donc testable sans Android.
 */
public final class GpxWriter {

    private GpxWriter() { }

    /**
     * Version du format GPX.
     *
     * C'EST IMPORTANT : les éléments <speed> et <course> sont LÉGAUX en GPX 1.0
     * (enfants directs de <trkpt>) mais INTERDITS en GPX 1.1, où il faut passer
     * par <extensions>. Un fichier 1.1 avec <speed> en direct est rejeté par
     * QGIS et les validateurs de schéma.
     *
     * On gère donc les deux, et l'utilisateur choisit :
     *   1.1 (défaut, recommandé)  -> vitesse et cap dans <extensions>
     *   1.0                       -> vitesse et cap en direct, lisible par les
     *                                outils anciens qui ne connaissent pas <extensions>
     */
    public enum Version {
        V11("1.1"),
        V10("1.0");

        public final String label;
        Version(String label) { this.label = label; }
    }

    public static final class TrackPoint {
        public long timeUtcMs;
        public double lat, lon, elevation;
        public Float speedMps;     // null si indisponible
        public Float courseDeg;    // null si indisponible
        public Float hdop;         // null si indisponible
        public Float accuracyM;    // null si indisponible

        public TrackPoint(long timeUtcMs, double lat, double lon, double elevation) {
            this.timeUtcMs = timeUtcMs;
            this.lat = lat;
            this.lon = lon;
            this.elevation = elevation;
        }
    }

    private static String buildHeader(Version version, String trackName, long startUtcMs) {
        String name = escapeXml(trackName);
        if (version == Version.V10) {
            // GPX 1.0 : pas de bloc <metadata>, pas d'<extensions>
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<gpx version=\"1.0\" creator=\"GPS Test\"\n"
                    + "     xmlns=\"http://www.topografix.com/GPX/1/0\">\n"
                    + "  <trk>\n"
                    + "    <name>" + name + "</name>\n"
                    + "    <trkseg>\n";
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<gpx version=\"1.1\" creator=\"GPS Test\"\n"
                + "     xmlns=\"http://www.topografix.com/GPX/1/1\"\n"
                + "     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "     xmlns:gpstest=\"https://example.org/gpstest/ext\"\n"
                + "     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\">\n"
                + "  <metadata>\n"
                + "    <name>" + name + "</name>\n"
                + "    <time>" + iso8601(startUtcMs) + "</time>\n"
                + "  </metadata>\n"
                + "  <trk>\n"
                + "    <name>" + name + "</name>\n"
                + "    <trkseg>\n";
    }

    private static final String FOOTER =
            "    </trkseg>\n"
                    + "  </trk>\n"
                    + "</gpx>\n";

    // ------------------------------------------------------------------
    // Sérialisation non destructive utilisée par TrackCleaner.
    // ------------------------------------------------------------------

    /** Entête de document sans création de métadonnées artificielles. */
    public static String cleanedDocumentHeader(Version version, String creator,
                                               String metadataXml) {
        return cleanedDocumentHeader(version, creator, metadataXml, null, null);
    }

    /** En-tête qui conserve aussi les namespaces et attributs non standards de la racine. */
    public static String cleanedDocumentHeader(Version version, String creator,
                                               String metadataXml,
                                               java.util.Map<String, String> rootNamespaces,
                                               java.util.List<GpxPreservation.RootAttribute> rootAttributes) {
        Version v = version != null ? version : Version.V11;
        String safeCreator = escapeXml(creator != null && !creator.trim().isEmpty()
                ? creator.trim() : "GPS Test");
        String gpxNamespace = v == Version.V10
                ? "http://www.topografix.com/GPX/1/0"
                : "http://www.topografix.com/GPX/1/1";
        StringBuilder out = new StringBuilder(768);
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<gpx version=\"").append(v.label).append("\" creator=\"")
                .append(safeCreator).append("\"\n")
                .append("     xmlns=\"").append(gpxNamespace).append("\"");

        boolean hasXsi = false;
        if (rootNamespaces != null) {
            for (java.util.Map.Entry<String, String> entry : rootNamespaces.entrySet()) {
                String prefix = entry.getKey();
                String namespace = entry.getValue();
                if (prefix == null || prefix.isEmpty() || "xml".equals(prefix)
                        || "xmlns".equals(prefix) || namespace == null) continue;
                if ("xsi".equals(prefix)
                        && "http://www.w3.org/2001/XMLSchema-instance".equals(namespace)) {
                    hasXsi = true;
                    if (v == Version.V11) {
                        out.append("\n     xmlns:xsi=\"").append(escapeXml(namespace)).append("\"");
                    } else {
                        out.append("\n     xmlns:xsi=\"").append(escapeXml(namespace)).append("\"");
                    }
                    continue;
                }
                out.append("\n     xmlns:").append(prefix).append("=\"")
                        .append(escapeXml(namespace)).append("\"");
            }
        }
        if (v == Version.V11 && !hasXsi) {
            out.append("\n     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"");
            hasXsi = true;
        }

        boolean hasSchemaLocation = false;
        if (rootAttributes != null) {
            for (GpxPreservation.RootAttribute attr : rootAttributes) {
                if (attr == null || attr.name == null || attr.name.isEmpty()
                        || attr.value == null) continue;
                if ("xsi:schemaLocation".equals(attr.name)) hasSchemaLocation = true;
                out.append("\n     ").append(attr.name).append("=\"")
                        .append(escapeXml(attr.value)).append("\"");
            }
        }
        if (v == Version.V11 && !hasSchemaLocation) {
            out.append("\n     xsi:schemaLocation=\"")
                    .append(gpxNamespace).append(' ').append(gpxNamespace)
                    .append("/gpx.xsd\"");
        }
        out.append(">\n");
        if (metadataXml != null && !metadataXml.isEmpty()) out.append(metadataXml).append('\n');
        return out.toString();
    }

    /** Ouvre une trace en conservant ses champs GPX d'origine. */
    public static String cleanedTrackOpen(String trackMetadataXml, String fallbackName,
                                          boolean sourceHasName) {
        StringBuilder out = new StringBuilder(256);
        out.append("  <trk>\n");
        if (!sourceHasName && fallbackName != null && !fallbackName.trim().isEmpty()) {
            out.append("    <name>").append(escapeXml(fallbackName.trim()))
                    .append("</name>\n");
        }
        if (trackMetadataXml != null && !trackMetadataXml.isEmpty()) {
            out.append(trackMetadataXml);
        }
        return out.toString();
    }

    /** Champs centraux d'un trkpt reconstruits sans arrondi ni horodatage inventé. */
    public static String cleanedPointCore(double elevation, String timeIso,
                                          long parsedTimeMs) {
        return cleanedPointCore(elevation, null, timeIso, parsedTimeMs);
    }

    /** Altitude source conservée lexiquement lorsqu'elle est décimale et finie. */
    public static String cleanedPointCore(double elevation, String elevationText,
                                          String timeIso, long parsedTimeMs) {
        StringBuilder out = new StringBuilder(96);
        if (Double.isFinite(elevation)) {
            String lexical = safeElevationText(elevationText, elevation);
            out.append("        <ele>").append(lexical).append("</ele>\n");
        }
        if (timeIso != null && !timeIso.trim().isEmpty() && parsedTimeMs != -1L) {
            out.append("        <time>").append(escapeXml(timeIso.trim())).append("</time>\n");
        }
        return out.toString();
    }


    private static String safeElevationText(String text, double value) {
        if (text != null && !text.trim().isEmpty()) {
            String candidate = text.trim();
            try {
                double parsed = Double.parseDouble(candidate);
                boolean decimalLexical = candidate.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");
                if (decimalLexical && Double.isFinite(parsed) && Double.compare(parsed, value) == 0)
                    return candidate;
            } catch (NumberFormatException ignored) { }
        }
        return Double.toString(value);
    }

    public static String cleanedSegmentOpen() { return "    <trkseg>\n"; }
    public static String cleanedSegmentClose(String segmentExtraXml) {
        StringBuilder out = new StringBuilder(128);
        if (segmentExtraXml != null && !segmentExtraXml.isEmpty()) out.append(segmentExtraXml);
        out.append("    </trkseg>\n");
        return out.toString();
    }
    public static String cleanedTrackClose() { return "  </trk>\n"; }
    public static String cleanedDocumentFooter() { return "</gpx>\n"; }

    /** Début de point nettoyé ; les valeurs source valides évitent l'arrondi à 7 décimales. */
    public static String cleanedPointOpen(double lat, double lon,
                                          String latitudeText, String longitudeText) {
        String latText = safeCoordinateText(latitudeText, lat, 90.0);
        String lonText = safeCoordinateText(longitudeText, lon, 180.0);
        return "      <trkpt lat=\"" + latText + "\" lon=\"" + lonText + "\">\n";
    }

    private static String safeCoordinateText(String text, double value, double maxAbs) {
        if (text != null && !text.trim().isEmpty()) {
            String candidate = text.trim();
            try {
                double parsed = Double.parseDouble(candidate);
                boolean decimalLexical = candidate.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");
                if (decimalLexical && Double.isFinite(parsed) && Math.abs(parsed) <= maxAbs)
                    return candidate;
            } catch (NumberFormatException ignored) { }
        }
        return Double.toString(value);
    }

    /** Construit un GPX 1.1 complet en mémoire. */
    public static String build(List<TrackPoint> points, String trackName) {
        return build(points, trackName, Version.V11);
    }

    /** Construit un GPX complet en mémoire, dans la version demandée. */
    public static String build(List<TrackPoint> points, String trackName, Version version) {
        StringBuilder sb = new StringBuilder();
        long first = points.isEmpty() ? System.currentTimeMillis() : points.get(0).timeUtcMs;
        sb.append(buildHeader(version, trackName, first));
        for (TrackPoint p : points) {
            sb.append(pointXml(p, version));
        }
        sb.append(FOOTER);
        return sb.toString();
    }

    /** Une ligne <trkpt> en GPX 1.1 (défaut). */
    public static String pointXml(TrackPoint p) {
        return pointXml(p, Version.V11);
    }

    /** Une ligne <trkpt>, dans la version demandée. Utilisable en streaming. */
    public static String pointXml(TrackPoint p, Version version) {
        // TAILLE INITIALE RÉALISTE. Sans elle, la capacité par défaut (16)
        // provoque une série de recopies à chaque point — précisément dans la
        // boucle d'écriture qui doit tenir la cadence d'un fix par seconde.
        // Un trkpt complet fait ~260 caractères ; 320 évite d'allouer deux fois.
        StringBuilder sb = new StringBuilder(320);
        sb.append(String.format(Locale.ROOT,
                "      <trkpt lat=\"%.7f\" lon=\"%.7f\">\n", p.lat, p.lon));
        if (!Double.isNaN(p.elevation)) {
            sb.append(String.format(Locale.ROOT, "        <ele>%.1f</ele>\n", p.elevation));
        }
        // BUG CORRIGÉ (v5.12, trouvé par le banc JPX) : on écrivait TOUJOURS
        // un <time>, même quand timeUtcMs valait -1. Résultat : un point sans
        // horodatage sortait daté du 31 décembre 1969 à 23:59:59 — une date
        // FABRIQUÉE, présentée comme un fait. Un lecteur ne peut pas la
        // distinguer d'une vraie mesure. Quand le temps est inconnu, on
        // n'écrit rien : c'est <time> qui est optionnel dans le schéma.
        if (p.timeUtcMs > 0) {
            sb.append(String.format(Locale.ROOT, "        <time>%s</time>\n",
                    iso8601(p.timeUtcMs)));
        }
        // <hdop> est standard en GPX 1.1.
        if (p.hdop != null) {
            sb.append(String.format(Locale.ROOT, "        <hdop>%.2f</hdop>\n", p.hdop));
        }
        if (version == Version.V10) {
            // En GPX 1.0, <speed> et <course> sont des enfants DIRECTS et légaux.
            if (p.speedMps != null && p.speedMps >= 0) {
                sb.append(String.format(Locale.ROOT, "        <speed>%.2f</speed>\n", p.speedMps));
            }
            if (p.courseDeg != null) {
                sb.append(String.format(Locale.ROOT, "        <course>%.1f</course>\n", p.courseDeg));
            }
            sb.append("      </trkpt>\n");
            return sb.toString();
        }

        // GPX 1.1 : <speed>, <course>, <accuracy> n'existent plus.
        // On passe par <extensions>, le mécanisme prévu par la spécification.
        boolean hasExt = (p.speedMps != null && p.speedMps >= 0)
                || p.courseDeg != null || p.accuracyM != null;
        if (hasExt) {
            sb.append("        <extensions>\n");
            if (p.speedMps != null && p.speedMps >= 0) {
                sb.append(String.format(Locale.ROOT,
                        "          <gpstest:speed>%.2f</gpstest:speed>\n", p.speedMps));
            }
            if (p.courseDeg != null) {
                sb.append(String.format(Locale.ROOT,
                        "          <gpstest:course>%.1f</gpstest:course>\n", p.courseDeg));
            }
            if (p.accuracyM != null) {
                sb.append(String.format(Locale.ROOT,
                        "          <gpstest:accuracy>%.1f</gpstest:accuracy>\n", p.accuracyM));
            }
            sb.append("        </extensions>\n");
        }
        sb.append("      </trkpt>\n");
        return sb.toString();
    }

    /** Entête GPX 1.1 (version par défaut). */
    public static String header(String trackName, long startUtcMs) {
        return buildHeader(Version.V11, trackName,
                startUtcMs == 0 ? System.currentTimeMillis() : startUtcMs);
    }

    /** Entête GPX dans la version demandée. */
    public static String header(Version version, String trackName, long startUtcMs) {
        return buildHeader(version, trackName,
                startUtcMs == 0 ? System.currentTimeMillis() : startUtcMs);
    }

    public static String footer() {
        return FOOTER;
    }

    /**
     * MARQUEUR DE COUPURE : ferme le segment courant et en ouvre un neuf.
     *
     * ══════════════════════════════════════════════════════════════════
     * POURQUOI CETTE MÉTHODE EXISTE, ET CE QU'ELLE EMPÊCHE
     * ══════════════════════════════════════════════════════════════════
     *
     * Sans elle, une perte de signal de 10 minutes dans un tunnel produit
     * DEUX points consécutifs dans le même <trkseg> : l'entrée du tunnel et
     * la sortie. Tous les outils (QGIS, Google Earth, OsmAnd) les relient
     * par une LIGNE DROITE et calculent une distance, une vitesse moyenne
     * et un dénivelé qui n'ont jamais existé.
     *
     * Pire : la distance affichée par l'application serait fausse. Un
     * utilisateur pourrait croire avoir marché 8 km sous terre.
     *
     * GPX prévoit exactement ce cas : plusieurs <trkseg> dans un <trk>
     * signifient « la trace est interrompue ici ». C'est la représentation
     * CORRECTE d'une coupure, et c'est celle que tous les lecteurs
     * comprennent.
     *
     * On n'écrit donc PAS de points artificiels (pas de « trou » à 0,0,
     * pas de point dupliqué) : uniquement une frontière de segment.
     *
     * @param version version GPX du fichier (1.0 ou 1.1)
     * @return le texte à écrire, ou "" si aucune coupure n'est possible
     */
    public static String segmentBreak(Version version) {
        return "    </trkseg>\n"
                + "    <trkseg>\n";
    }

    /**
     * Nom de fichier lisible, horodaté au DÉBUT de la trace.
     *
     *     Trace_2026_11_22_12h23m.GPX
     *     └─┬─┘ └───┬───┘ └───┬──┘
     *       │       │         └── heure et minute de DÉBUT (heure locale)
     *       │       └── date en chiffres, triée naturellement par ordre alphabétique
     *       └── préfixe constant : facile à retrouver, à filtrer, à trier
     *
     * POURQUOI CE FORMAT, ET PAS « trace-2026-11-22_184503 » :
     *   - il se lit à voix haute sans hésitation (« Trace du 22 novembre à
     *     12h23 ») ;
     *   - il contient explicitement l'unité de l'heure (12h23m), donc aucune
     *     ambiguïté entre 12:23 et 12h23 selon les conventions ;
     *   - le tri alphabétique = le tri chronologique, ce qui rend un dossier
     *     de 200 traces immédiatement exploitable ;
     *   - il est valide sur TOUS les systèmes de fichiers (pas de « : », qui
     *     est interdit sur FAT/exFAT et sur Windows).
     *
     * POURQUOI PAS DE SECONDES DANS LE NOM, ALORS QUE LA v5.2 LES AJOUTAIT :
     * les secondes évitaient une collision entre deux traces démarrées dans
     * la même minute. Ce rôle est désormais tenu par uniqueFile(), qui
     * n'écrase JAMAIS un fichier existant et ajoute « -2 », « -3 »… Le nom
     * redevient donc lisible, et la sécurité est assurée ailleurs.
     *
     * ATTENTION : l'heure est LOCALE. Une trace faite à Paris s'appelle
     * « 12h23 » et contient des points en UTC (le contenu GPX reste en UTC,
     * c'est la norme). C'est un choix : le nom parle à l'utilisateur, le
     * contenu parle aux machines.
     */
    public static String fileName(long utcMs) {
        return fileName(utcMs, Version.V11);
    }

    public static String fileName(long utcMs, Version version) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy_MM_dd_HH'h'mm'm'", Locale.ROOT);
        fmt.setTimeZone(TimeZone.getDefault());   // heure locale : c'est un nom pour l'humain
        return "Trace_" + fmt.format(new Date(utcMs))
                + (version == Version.V10 ? "_v10" : "") + ".GPX";
    }

    /**
     * ISO 8601 UTC, ex. 2026-11-22T18:45:03Z
     *
     * ── CALCUL MANUEL, PAS SimpleDateFormat (corrigé v5.13) ─────────────
     *
     * Cette méthode est appelée UNE FOIS PAR POINT ÉCRIT. À 1 Hz, c'est
     * 3 600 appels par heure ; en mode véhicule, ~1 800. Chaque appel à
     * SimpleDateFormat construit un objet, un Calendar et un TimeZone :
     * trois allocations par point, dans la boucle d'écriture.
     *
     * Le parser du projet (GpxParser) documente explicitement ce choix :
     * « Implémenté à la main plutôt qu'avec SimpleDateFormat : SimpleDateFormat
     * est lent (il alloue un Calendar à chaque appel) ». Le writer faisait
     * exactement le contraire. Incohérence corrigée : la même règle
     * s'applique des deux côtés.
     *
     * L'algorithme : conversion jours-depuis-époque → (année, mois, jour)
     * par l'algorithme de Howard Hinnant (celle qu'utilise java.time).
     * Exact pour toute date entre 1 et 9999, aucune allocation, aucune
     * dépendance, aucun fuseau à configurer.
     */
    public static String iso8601(long utcMs) {
        long seconds = Math.floorDiv(utcMs, 1000L);
        long days = Math.floorDiv(seconds, 86_400L);
        long secOfDay = Math.floorMod(seconds, 86_400L);

        // « days » depuis 1970-01-01 → civil (Howard Hinnant, days_from_civil
        // inverse).
        long z = days + 719_468L;
        long era = Math.floorDiv(z, 146_097L);
        long doe = z - era * 146_097L;                        // [0, 146096]
        long yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);    // [0, 365]
        long mp = (5 * doy + 2) / 153;                         // [0, 11]
        long d = doy - (153 * mp + 2) / 5 + 1;                 // [1, 31]
        long m = mp < 10 ? mp + 3 : mp - 9;                    // [1, 12]
        if (m <= 2) y += 1;

        int hh = (int) (secOfDay / 3600);
        int mm = (int) ((secOfDay % 3600) / 60);
        int ss = (int) (secOfDay % 60);

        StringBuilder sb = new StringBuilder(24);
        pad(sb, y, 4);
        sb.append('-');
        pad(sb, m, 2);
        sb.append('-');
        pad(sb, d, 2);
        sb.append('T');
        pad(sb, hh, 2);
        sb.append(':');
        pad(sb, mm, 2);
        sb.append(':');
        pad(sb, ss, 2);
        sb.append('Z');
        return sb.toString();
    }

    private static void pad(StringBuilder sb, long v, int width) {
        String s = Long.toString(Math.abs(v));
        for (int i = s.length(); i < width; i++) sb.append('0');
        if (v < 0) sb.append('-');
        sb.append(s);
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
