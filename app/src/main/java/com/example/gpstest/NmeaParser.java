package com.example.gpstest;

import java.util.Locale;

/**
 * Analyseur NMEA 0183 pour les phrases utiles au diagnostic GNSS.
 *
 * Classe Java pure : aucune dépendance Android, testable sur la JVM.
 * Les champs absents sont représentés par NaN / chaîne vide. Un champ
 * non vide mais invalide invalide la phrase : on ne convertit jamais NaN,
 * Infinity ou une notation Java exotique en mesure NMEA.
 */
public final class NmeaParser {
    private NmeaParser() { }

    /** Vérification stricte : la phrase doit contenir un checksum *HH valide. */
    public static boolean hasValidChecksum(String line) {
        if (line == null) return false;
        String s = line.trim();
        if (s.length() < 4 || s.charAt(0) != '$') return false;
        int star = s.indexOf('*');
        if (star < 2 || star != s.lastIndexOf('*') || star + 3 > s.length()) return false;
        if (star + 3 < s.length() && !s.substring(star + 3).trim().isEmpty()) return false;
        int hi = Character.digit(s.charAt(star + 1), 16);
        int lo = Character.digit(s.charAt(star + 2), 16);
        if (hi < 0 || lo < 0) return false;
        int sum = 0;
        for (int i = 1; i < star; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7e) return false;
            sum ^= c;
        }
        return sum == ((hi << 4) | lo);
    }

    /**
     * Politique de lecture : un checksum absent est toléré (certains OEM le
     * suppriment), mais un checksum présent et incorrect reste rédhibitoire.
     * L'enregistrement NMEA brut conserve toujours la phrase originale.
     */
    public static boolean hasAcceptableChecksum(String line) {
        if (line == null) return false;
        String s = line.trim();
        if (s.length() < 2 || s.charAt(0) != '$') return false;
        int star = s.indexOf('*');
        if (star >= 0) return hasValidChecksum(s);
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7e) return false;
        }
        return s.indexOf(',') > 1;
    }

    /** Type de phrase standard : GGA, GSA, RMC, GLL, VTG, ZDA, GNS, GST... */
    public static String sentenceType(String line) {
        if (line == null) return "";
        String s = line.trim();
        if (s.length() < 6 || s.charAt(0) != '$') return "";
        int comma = s.indexOf(',');
        if (comma < 4) return "";
        String address = s.substring(1, comma);
        if (address.length() < 5) return "";
        String type = address.substring(address.length() - 3);
        for (int i = 0; i < type.length(); i++) {
            char c = type.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) return "";
        }
        return type.toUpperCase(Locale.ROOT);
    }

    private static boolean isSentence(String line, String type) {
        return hasAcceptableChecksum(line) && type.equals(sentenceType(line));
    }

    private static String[] fields(String line) {
        String s = line.trim();
        int star = s.indexOf('*');
        String body = star > 0 ? s.substring(0, star) : s;
        return body.split(",", -1);
    }

    private static String field(String[] fields, int index) {
        return index >= 0 && index < fields.length && fields[index] != null
                ? fields[index].trim() : "";
    }

    private static double optionalDecimal(String value) {
        String s = value == null ? "" : value.trim();
        if (s.isEmpty()) return Double.NaN;
        // NMEA utilise une notation décimale simple, pas NaN, Infinity,
        // hexadécimal ni exposants propres à Double.parseDouble().
        if (!s.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) {
            throw new NumberFormatException("nombre NMEA invalide : " + value);
        }
        double d = Double.parseDouble(s);
        if (!Double.isFinite(d)) throw new NumberFormatException("nombre non fini");
        return d;
    }

    private static int optionalUnsignedInt(String value, int defaultValue) {
        String s = value == null ? "" : value.trim();
        if (s.isEmpty()) return defaultValue;
        if (!s.matches("[0-9]+")) throw new NumberFormatException("entier NMEA invalide");
        return Integer.parseInt(s);
    }

    // ------------------------------------------------------------------
    // GGA — position, qualité du fix, satellites, HDOP et altitude
    // ------------------------------------------------------------------
    public static final class Gga {
        public String utcTime = "";
        public double lat = Double.NaN, lon = Double.NaN;
        public int fixQuality = 0;
        public int numSatellites = 0;
        public double hdop = Double.NaN;
        public double altitude = Double.NaN;
        public double geoidSeparation = Double.NaN;
        public double differentialAgeSeconds = Double.NaN;
        public int differentialStationId = -1;
        public boolean valid = false;

        public String qualityLabel() {
            switch (fixQuality) {
                case 0: return "Aucun fix";
                case 1: return "GPS";
                case 2: return "DGPS / DGNSS";
                case 3: return "PPS";
                case 4: return "RTK fixé";
                case 5: return "RTK flottant";
                case 6: return "Estimé / navigation à l'estime";
                case 7: return "Entrée manuelle";
                case 8: return "Simulation";
                case 9: return "SBAS / WAAS (NMEA 4.x)";
                default: return "Qualité " + fixQuality + " (selon récepteur)";
            }
        }
    }

    public static Gga parseGga(String line) {
        Gga out = new Gga();
        if (!isSentence(line, "GGA")) return out;
        String[] f = fields(line);
        if (f.length < 10) return out;
        try {
            out.utcTime = field(f, 1);
            if (out.utcTime.isEmpty() || !validUtcTime(out.utcTime)) return out;
            String lat = field(f, 2), ns = field(f, 3);
            String lon = field(f, 4), ew = field(f, 5);
            if (!lat.isEmpty() || !ns.isEmpty()) {
                if (lat.isEmpty() || ns.isEmpty()) return out;
                out.lat = parseCoordinate(lat, ns);
            }
            if (!lon.isEmpty() || !ew.isEmpty()) {
                if (lon.isEmpty() || ew.isEmpty()) return out;
                out.lon = parseCoordinate(lon, ew);
            }
            if (field(f, 6).isEmpty()) return out;
            out.fixQuality = optionalUnsignedInt(field(f, 6), 0);
            out.numSatellites = optionalUnsignedInt(field(f, 7), 0);
            out.hdop = optionalDecimal(field(f, 8));
            out.altitude = optionalDecimal(field(f, 9));
            out.geoidSeparation = optionalDecimal(field(f, 11));
            out.differentialAgeSeconds = optionalDecimal(field(f, 13));
            out.differentialStationId = optionalUnsignedInt(field(f, 14), -1);

            if (out.fixQuality < 0 || out.fixQuality > 9 || out.numSatellites > 99
                    || (out.differentialStationId > 1023)
                    || (Double.isFinite(out.hdop) && out.hdop < 0)
                    || (Double.isFinite(out.altitude) && Math.abs(out.altitude) > 100_000)
                    || (Double.isFinite(out.geoidSeparation) && Math.abs(out.geoidSeparation) > 100_000)
                    || (Double.isFinite(out.differentialAgeSeconds) && out.differentialAgeSeconds < 0)
                    || (!field(f, 9).isEmpty() && !"M".equalsIgnoreCase(field(f, 10)))
                    || (!field(f, 11).isEmpty() && !"M".equalsIgnoreCase(field(f, 12)))) return out;
            if (out.fixQuality > 0) {
                if (!Double.isFinite(out.lat) || !Double.isFinite(out.lon)
                        || (out.lat == 0.0 && out.lon == 0.0)) return out;
            }
            out.valid = true;
        } catch (RuntimeException ignored) { out.valid = false; }
        return out;
    }

    // ------------------------------------------------------------------
    // GSA — type de solution, satellites utilisés et DOP du récepteur
    // ------------------------------------------------------------------
    public static final class Gsa {
        public int fixType = 1; // 1=aucun, 2=2D, 3=3D
        public double pdop = Double.NaN, hdop = Double.NaN, vdop = Double.NaN;
        /** Identifiant système NMEA 4.x (champ facultatif), -1 si absent. */
        public int systemId = -1;
        public boolean valid = false;

        public String fixLabel() {
            switch (fixType) {
                case 2: return "2D";
                case 3: return "3D";
                default: return "aucun";
            }
        }
    }

    public static Gsa parseGsa(String line) {
        Gsa out = new Gsa();
        if (!isSentence(line, "GSA")) return out;
        String[] f = fields(line);
        if (f.length < 18) return out;
        try {
            String mode = field(f, 1);
            if (!"A".equalsIgnoreCase(mode) && !"M".equalsIgnoreCase(mode)) return out;
            if (field(f, 2).isEmpty()) return out;
            out.fixType = optionalUnsignedInt(field(f, 2), 1);
            if (out.fixType < 1 || out.fixType > 3) return out;
            out.pdop = optionalDecimal(field(f, 15));
            out.hdop = optionalDecimal(field(f, 16));
            out.vdop = optionalDecimal(field(f, 17));
            if ((Double.isFinite(out.pdop) && out.pdop < 0)
                    || (Double.isFinite(out.hdop) && out.hdop < 0)
                    || (Double.isFinite(out.vdop) && out.vdop < 0)) return out;
            // Les identifiants de système évoluent selon la révision et les
            // profils OEM : 0 est notamment utilisé pour QZSS sur certains
            // récepteurs. On accepte la plage numérique 0..18, et non une
            // liste figée de constellations, car ce champ sert ici au diagnostic.
            if (f.length > 18 && !field(f, 18).isEmpty()) {
                out.systemId = optionalUnsignedInt(field(f, 18), -1);
                if (out.systemId < 0 || out.systemId > 18) return out;
            }
            for (int i = 3; i <= 14; i++) {
                String sv = field(f, i);
                if (!sv.isEmpty()) {
                    int svid = optionalUnsignedInt(sv, -1);
                    // Certains firmwares utilisent explicitement 0 comme
                    // marqueur « slot absent » au lieu d'un champ vide.
                    if (svid < 0 || svid > 999) return out;
                }
            }
            out.valid = true;
        } catch (RuntimeException ignored) { out.valid = false; }
        return out;
    }

    // ------------------------------------------------------------------
    // RMC — données minimales recommandées (status A/V séparé du mode)
    // ------------------------------------------------------------------
    public static final class Rmc {
        public String utcTime = "", date = "", status = "V", mode = "", navigationStatus = "";
        public double latitude = Double.NaN, longitude = Double.NaN;
        public double speedKnots = Double.NaN, courseDeg = Double.NaN;
        public boolean valid = false;
    }

    public static Rmc parseRmc(String line) {
        Rmc out = new Rmc();
        if (!isSentence(line, "RMC")) return out;
        String[] f = fields(line);
        if (f.length < 10) return out;
        try {
            out.utcTime = field(f, 1);
            out.status = field(f, 2).toUpperCase(Locale.ROOT);
            String lat = field(f, 3), ns = field(f, 4), lon = field(f, 5), ew = field(f, 6);
            if (!lat.isEmpty() || !ns.isEmpty()) {
                if (lat.isEmpty() || ns.isEmpty()) return out;
                out.latitude = parseCoordinate(lat, ns);
            }
            if (!lon.isEmpty() || !ew.isEmpty()) {
                if (lon.isEmpty() || ew.isEmpty()) return out;
                out.longitude = parseCoordinate(lon, ew);
            }
            out.speedKnots = optionalDecimal(field(f, 7));
            out.courseDeg = optionalDecimal(field(f, 8));
            out.date = field(f, 9);
            out.mode = field(f, 12).toUpperCase(Locale.ROOT);
            out.navigationStatus = field(f, 13);
            out.valid = "A".equals(out.status)
                    && (out.mode.isEmpty() || isModeIndicator(out.mode))
                    && !"N".equals(out.mode)
                    && validUtcTime(out.utcTime) && validDateDdmmyy(out.date)
                    && Double.isFinite(out.latitude) && Double.isFinite(out.longitude)
                    && !(out.latitude == 0.0 && out.longitude == 0.0)
                    && (!Double.isFinite(out.speedKnots) || out.speedKnots >= 0)
                    && (!Double.isFinite(out.courseDeg) || (out.courseDeg >= 0 && out.courseDeg <= 360));
        } catch (RuntimeException ignored) { out.valid = false; }
        return out;
    }

    // ------------------------------------------------------------------
    // GLL — latitude / longitude géographiques
    // ------------------------------------------------------------------
    public static final class Gll {
        public double latitude = Double.NaN, longitude = Double.NaN;
        public String utcTime = "", status = "V", mode = "";
        public boolean valid = false;
    }

    public static Gll parseGll(String line) {
        Gll out = new Gll();
        if (!isSentence(line, "GLL")) return out;
        String[] f = fields(line);
        if (f.length < 7) return out;
        try {
            String lat = field(f, 1), ns = field(f, 2), lon = field(f, 3), ew = field(f, 4);
            if (lat.isEmpty() || ns.isEmpty() || lon.isEmpty() || ew.isEmpty()) return out;
            out.latitude = parseCoordinate(lat, ns);
            out.longitude = parseCoordinate(lon, ew);
            out.utcTime = field(f, 5);
            out.status = field(f, 6).toUpperCase(Locale.ROOT);
            out.mode = field(f, 7).toUpperCase(Locale.ROOT);
            out.valid = "A".equals(out.status)
                    && (out.mode.isEmpty() || isModeIndicator(out.mode))
                    && !"N".equals(out.mode)
                    && validUtcTime(out.utcTime)
                    && !(out.latitude == 0.0 && out.longitude == 0.0);
        } catch (RuntimeException ignored) { out.valid = false; }
        return out;
    }

    // ------------------------------------------------------------------
    // VTG — cap et vitesses sol
    // ------------------------------------------------------------------
    public static final class Vtg {
        public double trueCourseDeg = Double.NaN, magneticCourseDeg = Double.NaN;
        public double speedKnots = Double.NaN, speedKmh = Double.NaN;
        public String mode = "";
        public boolean valid = false;
    }

    public static Vtg parseVtg(String line) {
        Vtg out = new Vtg();
        if (!isSentence(line, "VTG")) return out;
        String[] f = fields(line);
        if (f.length < 9) return out;
        try {
            out.trueCourseDeg = optionalDecimal(field(f, 1));
            out.magneticCourseDeg = optionalDecimal(field(f, 3));
            out.speedKnots = optionalDecimal(field(f, 5));
            out.speedKmh = optionalDecimal(field(f, 7));
            out.mode = field(f, 9).toUpperCase(Locale.ROOT);
            if ((!out.mode.isEmpty() && !isModeIndicator(out.mode))
                    || (!field(f, 1).isEmpty() && !"T".equalsIgnoreCase(field(f, 2)))
                    || (!field(f, 3).isEmpty() && !"M".equalsIgnoreCase(field(f, 4)))
                    || (!field(f, 5).isEmpty() && !"N".equalsIgnoreCase(field(f, 6)))
                    || (!field(f, 7).isEmpty() && !"K".equalsIgnoreCase(field(f, 8)))) return out;
            if ((Double.isFinite(out.trueCourseDeg) && (out.trueCourseDeg < 0 || out.trueCourseDeg > 360))
                    || (Double.isFinite(out.magneticCourseDeg) && (out.magneticCourseDeg < 0 || out.magneticCourseDeg > 360))
                    || (Double.isFinite(out.speedKnots) && out.speedKnots < 0)
                    || (Double.isFinite(out.speedKmh) && out.speedKmh < 0)
                    || "N".equals(out.mode)) return out;
            out.valid = Double.isFinite(out.speedKmh) || Double.isFinite(out.speedKnots)
                    || Double.isFinite(out.trueCourseDeg);
        } catch (RuntimeException ignored) { out.valid = false; }
        return out;
    }

    // ------------------------------------------------------------------
    // ZDA — temps et date UTC, fuseau local facultatif
    // ------------------------------------------------------------------
    public static final class Zda {
        public String utcTime = "";
        public int day, month, year;
        public int localZoneHours = Integer.MIN_VALUE, localZoneMinutes = Integer.MIN_VALUE;
        public boolean valid = false;
    }

    public static Zda parseZda(String line) {
        Zda out = new Zda();
        if (!isSentence(line, "ZDA")) return out;
        String[] f = fields(line);
        if (f.length < 5) return out;
        try {
            out.utcTime = field(f, 1);
            out.day = optionalUnsignedInt(field(f, 2), 0);
            out.month = optionalUnsignedInt(field(f, 3), 0);
            out.year = optionalUnsignedInt(field(f, 4), 0);
            String zoneH = field(f, 5), zoneM = field(f, 6);
            if (!zoneH.isEmpty() || !zoneM.isEmpty()) {
                if (zoneH.isEmpty() || zoneM.isEmpty() || !zoneH.matches("[+-]?[0-9]+")) return out;
                out.localZoneHours = Integer.parseInt(zoneH);
                out.localZoneMinutes = optionalUnsignedInt(zoneM, -1);
                if (out.localZoneHours < -13 || out.localZoneHours > 13
                        || out.localZoneMinutes < 0 || out.localZoneMinutes > 59) return out;
            }
            out.valid = validUtcTime(out.utcTime) && validCivilDate(out.year, out.month, out.day);
        } catch (RuntimeException ignored) { out.valid = false; }
        return out;
    }

    // ------------------------------------------------------------------
    // GNS — position multi-GNSS et modes par constellation
    // ------------------------------------------------------------------
    public static final class Gns {
        public String utcTime = "", mode = "";
        public double latitude = Double.NaN, longitude = Double.NaN;
        public int satellitesUsed = 0;
        public double hdop = Double.NaN, altitude = Double.NaN, geoidSeparation = Double.NaN;
        public double differentialAgeSeconds = Double.NaN;
        public int differentialStationId = -1;
        public boolean valid = false;

        public boolean hasAnyFixMode() {
            if (!valid || mode.isEmpty()) return false;
            for (int i = 0; i < mode.length(); i++) if (mode.charAt(i) != 'N') return true;
            return false;
        }
    }

    public static Gns parseGns(String line) {
        Gns out = new Gns();
        if (!isSentence(line, "GNS")) return out;
        String[] f = fields(line);
        if (f.length < 10) return out;
        try {
            out.utcTime = field(f, 1);
            out.mode = field(f, 6).toUpperCase(Locale.ROOT);
            if (!validUtcTime(out.utcTime) || out.mode.isEmpty()) return out;
            for (int i = 0; i < out.mode.length(); i++) {
                if (!isModeIndicator(String.valueOf(out.mode.charAt(i)))) return out;
            }
            out.satellitesUsed = optionalUnsignedInt(field(f, 7), 0);
            out.hdop = optionalDecimal(field(f, 8));
            out.altitude = optionalDecimal(field(f, 9));
            out.geoidSeparation = optionalDecimal(field(f, 10));
            out.differentialAgeSeconds = optionalDecimal(field(f, 11));
            out.differentialStationId = optionalUnsignedInt(field(f, 12), -1);
            if (out.satellitesUsed > 99 || out.differentialStationId > 1023
                    || (Double.isFinite(out.hdop) && out.hdop < 0)
                    || (Double.isFinite(out.altitude) && Math.abs(out.altitude) > 100_000)
                    || (Double.isFinite(out.geoidSeparation) && Math.abs(out.geoidSeparation) > 100_000)
                    || (Double.isFinite(out.differentialAgeSeconds) && out.differentialAgeSeconds < 0)) return out;
            String lat = field(f, 2), ns = field(f, 3), lon = field(f, 4), ew = field(f, 5);
            if (hasActiveGnsMode(out.mode)) {
                if (lat.isEmpty() || ns.isEmpty() || lon.isEmpty() || ew.isEmpty()) return out;
                out.latitude = parseCoordinate(lat, ns);
                out.longitude = parseCoordinate(lon, ew);
                if (out.latitude == 0.0 && out.longitude == 0.0) return out;
            }
            out.valid = true;
        } catch (RuntimeException ignored) { out.valid = false; }
        return out;
    }

    private static boolean hasActiveGnsMode(String mode) {
        if (mode == null || mode.isEmpty()) return false;
        for (int i = 0; i < mode.length(); i++) if (mode.charAt(i) != 'N') return true;
        return false;
    }


    // ------------------------------------------------------------------
    // GST — statistiques d'erreur de position (écarts-types 1 sigma)
    // ------------------------------------------------------------------
    public static final class Gst {
        public String utcTime = "";
        public double rmsPseudorangeResidual = Double.NaN;
        public double semiMajorErrorM = Double.NaN, semiMinorErrorM = Double.NaN;
        public double ellipseOrientationDeg = Double.NaN;
        public double latitudeSigmaM = Double.NaN, longitudeSigmaM = Double.NaN;
        public double altitudeSigmaM = Double.NaN;
        public boolean valid = false;

        public String summary() {
            if (!valid) return "";
            return String.format(Locale.FRANCE,
                    "GST 1σ : lat %s m · lon %s m · alt %s m",
                    fmtSigma(latitudeSigmaM), fmtSigma(longitudeSigmaM), fmtSigma(altitudeSigmaM));
        }
        private static String fmtSigma(double v) {
            return Double.isFinite(v) ? String.format(Locale.FRANCE, "%.2f", v) : "—";
        }
    }

    public static Gst parseGst(String line) {
        Gst out = new Gst();
        if (!isSentence(line, "GST")) return out;
        String[] f = fields(line);
        if (f.length < 9) return out;
        try {
            out.utcTime = field(f, 1);
            if (!validUtcTime(out.utcTime)) return out;
            out.rmsPseudorangeResidual = optionalDecimal(field(f, 2));
            out.semiMajorErrorM = optionalDecimal(field(f, 3));
            out.semiMinorErrorM = optionalDecimal(field(f, 4));
            out.ellipseOrientationDeg = optionalDecimal(field(f, 5));
            out.latitudeSigmaM = optionalDecimal(field(f, 6));
            out.longitudeSigmaM = optionalDecimal(field(f, 7));
            out.altitudeSigmaM = optionalDecimal(field(f, 8));
            double[] values = { out.rmsPseudorangeResidual, out.semiMajorErrorM,
                    out.semiMinorErrorM, out.latitudeSigmaM, out.longitudeSigmaM, out.altitudeSigmaM };
            boolean any = false;
            for (double v : values) {
                if (Double.isFinite(v)) { if (v < 0) return out; any = true; }
            }
            if (Double.isFinite(out.ellipseOrientationDeg)
                    && (out.ellipseOrientationDeg < 0 || out.ellipseOrientationDeg > 360)) return out;
            out.valid = any;
        } catch (RuntimeException ignored) { out.valid = false; }
        return out;
    }

    /** Indicateurs de mode NMEA rencontrés sur RMC/GLL/VTG/GNS. */
    private static boolean isModeIndicator(String mode) {
        if (mode == null || mode.length() != 1) return false;
        // A autonome, D différentiel, E estime, F RTK flottant, M manuel,
        // N données invalides, P précis, R RTK fixé, S simulateur.
        return "ADEFMNPRS".indexOf(mode.charAt(0)) >= 0;
    }

    // ------------------------------------------------------------------
    // Coordonnées et temps
    // ------------------------------------------------------------------
    public static double parseCoordinate(String value, String hemisphere) {
        if (value == null || hemisphere == null) throw new NumberFormatException("coordonnée absente");
        String v = value.trim();
        String h = hemisphere.trim().toUpperCase(Locale.ROOT);
        final boolean latitude;
        final int degreeDigits;
        if ("N".equals(h) || "S".equals(h)) { latitude = true; degreeDigits = 2; }
        else if ("E".equals(h) || "W".equals(h)) { latitude = false; degreeDigits = 3; }
        else throw new NumberFormatException("hémisphère invalide : " + hemisphere);
        if (!v.matches("[0-9]+(?:\\.[0-9]+)?")) throw new NumberFormatException("coordonnée invalide : " + value);
        int dot = v.indexOf('.');
        String whole = dot >= 0 ? v.substring(0, dot) : v;
        if (whole.length() != degreeDigits + 2) throw new NumberFormatException("longueur invalide : " + value);
        double deg = Double.parseDouble(whole.substring(0, degreeDigits));
        double min = Double.parseDouble(v.substring(degreeDigits));
        double maxDeg = latitude ? 90.0 : 180.0;
        if (!Double.isFinite(deg) || !Double.isFinite(min) || min < 0 || min >= 60
                || deg > maxDeg || (deg == maxDeg && min != 0.0)) {
            throw new NumberFormatException("coordonnée hors limites : " + value);
        }
        double result = deg + min / 60.0;
        if ("S".equals(h) || "W".equals(h)) result = -result;
        return result;
    }

    public static boolean validUtcTime(String s) {
        if (s == null || !s.matches("[0-9]{6}(?:\\.[0-9]+)?")) return false;
        try {
            int h = Integer.parseInt(s.substring(0, 2));
            int m = Integer.parseInt(s.substring(2, 4));
            double sec = Double.parseDouble(s.substring(4));
            return h <= 23 && m <= 59 && Double.isFinite(sec) && sec >= 0 && sec < 60;
        } catch (RuntimeException e) { return false; }
    }

    private static boolean validDateDdmmyy(String s) {
        if (s == null || !s.matches("[0-9]{6}")) return false;
        try {
            int d = Integer.parseInt(s.substring(0, 2));
            int m = Integer.parseInt(s.substring(2, 4));
            int y = Integer.parseInt(s.substring(4, 6));
            return validCivilDate(y >= 80 ? 1900 + y : 2000 + y, m, d);
        } catch (RuntimeException e) { return false; }
    }

    private static boolean validCivilDate(int year, int month, int day) {
        if (year < 1 || month < 1 || month > 12 || day < 1) return false;
        int[] days = {31, isLeapYear(year) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        return day <= days[month - 1];
    }

    private static boolean isLeapYear(int y) {
        return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
    }

    /** Barème indicatif usuel ; ce n'est pas une garantie de précision métrique. */
    public static String dopQuality(double dop) {
        if (!Double.isFinite(dop) || dop <= 0) return "—";
        if (dop < 1.0) return "Idéal";
        if (dop < 2.0) return "Excellent";
        if (dop < 5.0) return "Bon";
        if (dop < 10.0) return "Médiocre";
        if (dop < 20.0) return "Mauvais";
        return "Inutilisable";
    }
}
