package com.example.gpstest;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * Tests des nouveaux modules : Datum, UTM, NMEA, Astro, GPX, mesures brutes.
 * Exécution : javac -encoding UTF-8 -d out <sources> tests/*.java && java -cp out com.example.gpstest.AdvancedTest
 */
public class AdvancedTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {

        // ============ DATUMS ============
        section("DATUMS (Helmert)");
        // WGS84 -> WGS84 : identité
        double[] same = Datum.WGS84.fromWgs84(48.8584, 2.2945, 35);
        near(48.8584, same[0], 1e-9, "WGS84->WGS84 lat identique");
        near(2.2945, same[1], 1e-9, "WGS84->WGS84 lon identique");

        // OSGB36 : un point de Londres doit reculer d'environ 100 m vers l'ouest
        double lonWgs = -0.1276; // Greenwich
        double[] osgb = Datum.OSGB36.fromWgs84(51.4779, lonWgs, 46);
        double deltaM = (lonWgs - osgb[1]) * 111320.0 * Math.cos(Math.toRadians(51.4779));
        ok(deltaM > 60 && deltaM < 160, "OSGB36 : décalage réel de la longitude ("
                + String.format("%.0f", deltaM) + " m, attendu ~100 m)");

        // Round-trip : ED50 -> WGS84 -> ED50 doit revenir au point de départ
        double[] wgs = Datum.ED50.toWgs84(48.0, 2.0, 100.0);
        double[] back = Datum.ED50.fromWgs84(wgs[0], wgs[1], wgs[2]);
        near(48.0, back[0], 1e-7, "ED50 aller-retour latitude");
        near(2.0, back[1], 1e-7, "ED50 aller-retour longitude");
        near(100.0, back[2], 0.01, "ED50 aller-retour hauteur");

        // ECEF : vérifier que la norme du vecteur = rayon terrestre + hauteur
        double[] ecef = Datum.geodeticToEcef(48.8584, 2.2945, 35, 6378137.0, 298.257223563);
        double norm = Math.sqrt(ecef[0]*ecef[0] + ecef[1]*ecef[1] + ecef[2]*ecef[2]);
        ok(norm > 6360000 && norm < 6380000, "ECEF : norme cohérente (" + String.format("%.0f m", norm) + ")");

        // ============ UTM ============
        section("UTM");
        Utm.Result eiffel = Utm.fromLatLon(48.8584, 2.2945, Datum.WGS84);
        ok(eiffel.zone == 31, "Tour Eiffel : zone 31 (" + eiffel.zone + ")");
        ok(eiffel.band == 'U', "Tour Eiffel : bande U (" + eiffel.band + ")");
        // Valeurs de référence connues : 31U 448252 E, 5411932 N (±100 m)
        near(448252, eiffel.easting, 150, "Easting Tour Eiffel (" + String.format("%.0f", eiffel.easting) + ")");
        near(5411932, eiffel.northing, 150, "Northing Tour Eiffel (" + String.format("%.0f", eiffel.northing) + ")");
        System.out.println("         forme : " + eiffel.format());

        Utm.Result rio = Utm.fromLatLon(-22.9068, -43.1729, Datum.WGS84);
        ok(rio.south && rio.northing > 7000000, "Hémisphère sud : false northing appliqué");
        Utm.Result sydney = Utm.fromLatLon(-33.8688, 151.2093, Datum.WGS84);
        ok(sydney.zone == 56, "Sydney zone 56 (" + sydney.zone + ")");
        ok(sydney.band == 'H', "Sydney bande H (" + sydney.band + ")");

        // ============ NMEA ============
        section("NMEA");
        String gga = "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47";
        ok(NmeaParser.hasValidChecksum(gga), "GGA : checksum accepté");
        ok(!NmeaParser.hasValidChecksum(gga.replace("*47", "*48")), "GGA : checksum corrompu rejeté");
        ok(NmeaParser.sentenceType(gga).equals("GGA"), "Type de trame GGA");

        NmeaParser.Gga g = NmeaParser.parseGga(gga);
        ok(g.valid, "GGA : parse OK");
        near(48.11730, g.lat, 1e-5, "GGA latitude 48.1173");
        near(11.51667, g.lon, 1e-5, "GGA longitude 11.51667");
        ok(g.numSatellites == 8, "GGA 8 satellites");
        near(0.9, g.hdop, 1e-9, "GGA HDOP 0.9");
        near(545.4, g.altitude, 1e-9, "GGA altitude 545.4 m");
        ok(g.qualityLabel().equals("GPS"), "GGA qualité -> GPS");

        String gsa = "$GPGSA,A,3,04,05,,09,12,,,24,,,,,2.5,1.3,2.1*39";
        ok(NmeaParser.hasValidChecksum(gsa), "GSA : checksum accepté");
        NmeaParser.Gsa ga = NmeaParser.parseGsa(gsa);
        ok(ga.fixType == 3 && ga.fixLabel().equals("3D"), "GSA fix 3D");
        near(2.5, ga.pdop, 1e-9, "GSA PDOP 2.5");
        near(1.3, ga.hdop, 1e-9, "GSA HDOP 1.3");
        near(2.1, ga.vdop, 1e-9, "GSA VDOP 2.1");

        String rmc = "$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A";
        ok(NmeaParser.hasValidChecksum(rmc), "RMC : checksum accepté");
        NmeaParser.Rmc r = NmeaParser.parseRmc(rmc);
        near(22.4, r.speedKnots, 1e-9, "RMC vitesse 22.4 nœuds");
        near(84.4, r.courseDeg, 1e-9, "RMC cap 84.4°");
        ok(r.date.equals("230394"), "RMC date 230394");

        String zda = "$GPZDA,201530.00,04,07,2002,00,00*60";
        ok(NmeaParser.hasValidChecksum(zda), "ZDA : checksum accepté");
        NmeaParser.Zda z = NmeaParser.parseZda(zda);
        ok(z.day == 4 && z.month == 7 && z.year == 2002, "ZDA date 04/07/2002");

        NmeaParser.Rmc invalidRmc = NmeaParser.parseRmc(nmea("GPRMC,123519,V,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W"));
        ok(!invalidRmc.valid, "RMC statut V : données déclarées invalides rejetées");
        NmeaParser.Gga nullIsland = NmeaParser.parseGga(nmea("GPGGA,123519,0000.000,N,00000.000,E,1,08,0.9,0.0,M,0.0,M,,"));
        ok(!nullIsland.valid, "GGA qualité >0 mais coordonnées 0,0 : rejetées");
        NmeaParser.Vtg vtg = NmeaParser.parseVtg(nmea("GPVTG,054.7,T,034.4,M,005.5,N,010.2,K,A"));
        ok(vtg.valid, "VTG valide parsée");
        near(10.2, vtg.speedKmh, 1e-9, "VTG vitesse km/h");
        near(54.7, vtg.trueCourseDeg, 1e-9, "VTG cap vrai");
        NmeaParser.Gll gll = NmeaParser.parseGll(nmea("GPGLL,4916.45,N,12311.12,W,225444,A,A"));
        ok(gll.valid, "GLL valide parsée");

        ok(NmeaParser.parseCoordinate("4807.038", "N") > 48.1, "Coordonnée N");
        ok(NmeaParser.parseCoordinate("4807.038", "S") < -48.1, "Coordonnée S");
        ok(NmeaParser.parseCoordinate("01131.000", "W") < 0, "Coordonnée W");
        ok(NmeaParser.dopQuality(0.8).equals("Idéal"), "DOP 0.8 -> Idéal");
        ok(NmeaParser.dopQuality(7.0).equals("Médiocre"), "DOP 7 -> Médiocre");
        ok(NmeaParser.dopQuality(25).equals("Inutilisable"), "DOP 25 -> Inutilisable");

        // ============ ASTRO ============
        section("ASTRO (soleil / lune)");
        long solsticeEte = 1782000000000L; // 2026-06-21 ~ heure UTC quelconque
        Astro.SunTimes paris = Astro.compute(48.8566, 2.3522, solsticeEte);
        ok(paris.valid(), "Paris : lever/coucher calculés");
        TimeZone tzParis = TimeZone.getTimeZone("Europe/Paris");
        String lever = Astro.formatLocalTime(paris.sunriseUtcMs, tzParis);
        String coucher = Astro.formatLocalTime(paris.sunsetUtcMs, tzParis);
        System.out.println("         Paris 21 juin : lever " + lever + " / coucher " + coucher);
        ok(paris.sunriseUtcMs < paris.sunsetUtcMs, "Lever avant coucher");
        // durée du jour au solstice d'été à Paris : ~16h05
        double heures = (paris.sunsetUtcMs - paris.sunriseUtcMs) / 3600000.0;
        ok(heures > 15.5 && heures < 16.6, "Durée du jour ~16 h (" + String.format("%.1f h", heures) + ")");

        // Tromso (Norvège) au solstice d'été : jour polaire
        Astro.SunTimes tromso = Astro.compute(69.6492, 18.9553, solsticeEte);
        ok(tromso.polarDay, "Tromso 21 juin : jour polaire détecté");
        // Tromso en hiver : nuit polaire
        long solsticeHiver = 1766300000000L; // 2026-12-21
        Astro.SunTimes tromsoHiver = Astro.compute(69.6492, 18.9553, solsticeHiver);
        ok(tromsoHiver.polarNight, "Tromso 21 décembre : nuit polaire détectée");

        // Lune
        double phase = Astro.moonPhaseFraction(1763750000000L); // 2026-11-21
        ok(phase >= 0 && phase <= 1, "Phase lunaire dans [0,1] (" + String.format("%.3f", phase) + ")");
        double illum = Astro.moonIllumination(phase);
        ok(illum >= 0 && illum <= 1, "Illumination dans [0,1] (" + String.format("%.0f%%", illum*100) + ")");
        ok(Astro.moonPhaseName(0.0).equals("Nouvelle lune"), "Phase 0 = Nouvelle lune");
        ok(Astro.moonPhaseName(0.5).equals("Pleine lune"), "Phase 0.5 = Pleine lune");

        // ============ GPX ============
        section("GPX");
        List<GpxWriter.TrackPoint> pts = new ArrayList<>();
        GpxWriter.TrackPoint p1 = new GpxWriter.TrackPoint(1763750000000L, 48.8584, 2.2945, 35.0);
        p1.speedMps = 1.2f; p1.courseDeg = 312f; p1.hdop = 0.9f;
        GpxWriter.TrackPoint p2 = new GpxWriter.TrackPoint(1763750001000L, 48.8585, 2.2946, 35.5);
        pts.add(p1); pts.add(p2);
        String gpx = GpxWriter.build(pts, "Trace du <test> & co");
        ok(gpx.startsWith("<?xml"), "GPX : déclaration XML");
        ok(gpx.contains("<gpx version=\"1.1\""), "GPX : version 1.1");
        ok(gpx.contains("<trkpt lat=\"48.8584000\" lon=\"2.2945000\">"), "GPX : coordonnées 7 décimales");
        ok(gpx.contains("<ele>35.0</ele>"), "GPX : altitude");
        ok(gpx.contains("<gpstest:speed>1.20</gpstest:speed>"), "GPX : vitesse dans <extensions>");
        ok(gpx.contains("<hdop>0.90</hdop>"), "GPX : HDOP (element standard GPX 1.1)") ; ok(gpx.contains("<extensions>"), "GPX : bloc extensions present");
        ok(gpx.contains("&lt;test&gt; &amp; co"), "GPX : échappement XML du nom");
        ok(gpx.trim().endsWith("</gpx>"), "GPX : fermeture");
        ok(GpxWriter.iso8601(0).equals("1970-01-01T00:00:00Z"), "GPX : ISO 8601 epoch");
        ok(GpxWriter.fileName(0).startsWith("Trace_1970_01_01_"), "GPX : nom horodaté, format Trace_");

        // --- GESTION DE VERSION ---
        String gpx10 = GpxWriter.build(pts, "Test v10", GpxWriter.Version.V10);
        ok(gpx10.contains("version=\"1.0\""), "GPX 1.0 : déclaration de version");
        ok(gpx10.contains("<speed>1.20</speed>"), "GPX 1.0 : <speed> en enfant direct (legal en 1.0)");
        ok(!gpx10.contains("<extensions>"), "GPX 1.0 : pas de bloc extensions");
        ok(!gpx10.contains("<metadata>"), "GPX 1.0 : pas de bloc metadata");

        ok(gpx.contains("version=\"1.1\""), "GPX 1.1 : déclaration de version");
        ok(!gpx.contains("<trkpt lat=\"48.8584000\" lon=\"2.2945000\">\n        <speed>"),
                "GPX 1.1 : PAS de <speed> en enfant direct (interdit en 1.1)");
        ok(gpx.contains("<extensions>"), "GPX 1.1 : bloc extensions present");

        // Un point seul dans les deux versions
        String p11 = GpxWriter.pointXml(p1, GpxWriter.Version.V11);
        String p10 = GpxWriter.pointXml(p1, GpxWriter.Version.V10);
        ok(p11.contains("<extensions>") && !p11.contains("<speed>1.20</speed>"),
                "pointXml(1.1) : extensions, pas de speed direct");
        ok(p10.contains("<speed>1.20</speed>") && !p10.contains("<extensions>"),
                "pointXml(1.0) : speed direct, pas d'extensions");
        int p10Course = p10.indexOf("<course>312.0</course>");
        int p10Speed = p10.indexOf("<speed>1.20</speed>");
        int p10Hdop = p10.indexOf("<hdop>0.90</hdop>");
        ok(p10Course >= 0 && p10Course < p10Speed && p10Speed < p10Hdop,
                "pointXml(1.0) : ordre XSD course, speed, hdop");
        int p11Hdop = p11.indexOf("<hdop>0.90</hdop>");
        int p11Extensions = p11.indexOf("<extensions>");
        ok(p11Hdop >= 0 && p11Extensions >= 0 && p11Hdop < p11Extensions,
                "pointXml(1.1) : hdop précède extensions");

        GpxWriter.TrackPoint unsafeScalars = new GpxWriter.TrackPoint(
                1763750002000L, 48.0, 2.0, Double.POSITIVE_INFINITY);
        unsafeScalars.speedMps = Float.POSITIVE_INFINITY;
        unsafeScalars.courseDeg = Float.NaN;
        unsafeScalars.hdop = Float.NEGATIVE_INFINITY;
        unsafeScalars.accuracyM = -1.0f;
        String safeScalarsXml = GpxWriter.pointXml(unsafeScalars, GpxWriter.Version.V11);
        ok(!safeScalarsXml.contains("<ele>") && !safeScalarsXml.contains("<hdop>")
                        && !safeScalarsXml.contains("<extensions>")
                        && !safeScalarsXml.contains("Infinity") && !safeScalarsXml.contains("NaN"),
                "pointXml : altitude et champs facultatifs non finis/négatifs omis");

        GpxWriter.TrackPoint excessiveElevation = new GpxWriter.TrackPoint(
                1763750003000L, 48.0, 2.0, 10000.1);
        ok(!GpxWriter.pointXml(excessiveElevation, GpxWriter.Version.V11).contains("<ele>"),
                "pointXml : altitude hors limite ±10 000 m omise");

        boolean nullIslandRejected = false;
        try {
            GpxWriter.pointXml(new GpxWriter.TrackPoint(1763750004000L, 0.0, 0.0, 5.0),
                    GpxWriter.Version.V11);
        } catch (IllegalArgumentException expected) { nullIslandRejected = true; }
        ok(nullIslandRejected, "pointXml : coordonnée (0,0) interdite par la politique du projet");

        boolean outOfRangeRejected = false;
        try {
            GpxWriter.pointXml(new GpxWriter.TrackPoint(1763750005000L, 91.0, 2.0, 5.0),
                    GpxWriter.Version.V11);
        } catch (IllegalArgumentException expected) { outOfRangeRejected = true; }
        ok(outOfRangeRejected, "pointXml : latitude hors bornes rejetée");

        // Noms de fichiers distincts par version
        ok(GpxWriter.fileName(0, GpxWriter.Version.V10).contains("_v10"),
                "GPX 1.0 : nom de fichier suffixé -v10");
        ok(!GpxWriter.fileName(0, GpxWriter.Version.V11).contains("_v10"),
                "GPX 1.1 : nom de fichier sans suffixe");

        // ============ MESURES BRUTES ============
        section("MESURES BRUTES (CSV)");
        ok(RawMeasurementsFormatter.bandLabel(1575.42e6).equals("L1"), "1575.42 MHz -> L1");
        ok(RawMeasurementsFormatter.bandLabel(1176.45e6).equals("L5"), "1176.45 MHz -> L5");
        ok(RawMeasurementsFormatter.bandLabel(1561.098e6).equals("B1"), "1561.098 MHz -> B1 (BeiDou)");
        ok(RawMeasurementsFormatter.bandLabel(1600.995e6).equals("G1"), "1600.995 MHz -> G1 (GLONASS)");
        ok(RawMeasurementsFormatter.bandLabel(1207.14e6).equals("B2/E5b"), "1207.14 MHz -> B2/E5b (frequence partagee)");
        String line = RawMeasurementsFormatter.csvLine(1763750000000L, "GPS", 12,
                1575.42e6, 42.5, 2.1e7, 45.3, 123456.7, 0, 1);
        ok(line.startsWith("1763750000000,GPS,12,1575420000.000,L1,42.50"), "CSV : 11e colonne en Hz, porteuse resolu en L1");
        ok(line.split(",").length == 11, "CSV : 11 colonnes (" + line.split(",").length + ")");
        String nanLine = RawMeasurementsFormatter.csvLine(1L, "GALILEO", 5,
                1176.45e6, 33.0, Double.NaN, Double.NaN, Double.NaN, 0, 0);
        ok(nanLine.contains(",L5,33.00,,,"), "CSV : champs indisponibles laissés vides");
        ok(RawMeasurementsFormatter.carrierLabel(1575.42e6).equals("L1 1575 MHz"), "Étiquette porteuse L1");
        String detailed = RawMeasurementsFormatter.detailedCsvLine(1000L, 2000000L,
                "GPS", 4, "C", 7, 1575.42e6, 35.0, -1200.0, 0.5,
                123456789L, 20L, 2.0, 3.0, 0.1, 3, 0,
                true, true, true, true, 456L, -100L, 1.0, 0.1, 0.0, 0.0, 1);
        ok(RawMeasurementsFormatter.CSV_HEADER.split(",", -1).length == 47,
                "CSV expert : en-tête 47 colonnes, dont les références ISB");
        ok(detailed.split(",", -1).length == 47,
                "CSV expert : chaque mesure produit exactement 47 colonnes");
        String compact = RawMeasurementsFormatter.detailedCsvLine(1000L, 2000000L,
                "GPS", 4, "C", 7, 1575.42e6, 35.0, -1200.0, 0.5,
                123456789L, 20L, 2.0, 3.0, 0.1, 3, 0,
                false, false, false, false, 456L, null, null, null, null, null, 1);
        ok(compact.split(",", -1).length == 47 && compact.startsWith("\"1000\",\"2000000\",\"GPS\""),
                "CSV expert : horodatages callback toujours présents malgré options GNSS décochées");
        String agcRow = RawMeasurementsFormatter.agcEventCsvLine(1000L, 2000000L,
                "GPS", 1575420000L, -18.5, 456L, -100L, 1.0, 18, 2, true, true);
        String[] agcFields = agcRow.split(",", -1);
        ok(agcFields.length == 47 && "\"AGC\"".equals(agcFields[42]),
                "CSV expert : ligne AGC API 33 indépendante (47 colonnes)");
        ok(agcFields[40].contains("-18.500") && agcFields[41].contains("true"),
                "CSV expert : niveau AGC et mode full tracking préservés");
        String agcWithoutTime = RawMeasurementsFormatter.agcEventCsvLine(1000L, 2000000L,
                "GPS", 1575420000L, -18.5, 456L, -100L, 1.0, 18, 2, true, false);
        String[] agcNoTimeFields = agcWithoutTime.split(",", -1);
        ok(agcNoTimeFields[0].equals("\"1000\"") && agcNoTimeFields[1].equals("\"2000000\"")
                        && agcNoTimeFields[25].equals("\"\"")
                        && agcNoTimeFields[40].contains("-18.500"),
                "CSV expert : horodatages de réception présents, détail GnssClock désactivable pour AGC");
        String optionsRow = RawMeasurementsFormatter.detailedCsvLineFull(
                1000L, 2000000L, "GALILEO", 9, "E1C", 3,
                1575.42e6, 38.0, 37.0, -12.0, 10.0,
                -450.0, 0.2, 123L, 4L, 0.5,
                15.0, 0.1, 2, 100L, 0.25, 0.01, 1,
                456L, 0.2, 789L, 0.3, 18, -100L, 1.0,
                0.1, 0.0, 0.01, 0.5, 0.1, 0.6, 0.2,
                1, null, true, 3, "E1C", 1575420000d, false, false, false, false);
        String[] optionsFields = optionsRow.split(",", -1);
        ok(optionsFields.length == 47 && optionsFields[0].equals("\"1000\"")
                        && optionsFields[1].equals("\"2000000\""),
                "CSV expert : le schéma complet reste à 47 colonnes avec options minimales");
        ok(optionsFields[8].equals("\"\"") && optionsFields[13].equals("\"\"")
                        && optionsFields[18].equals("\"\"") && optionsFields[25].equals("\"\""),
                "CSV expert : désactivation séparée des groupes signal, Doppler, phase et horloge");
        ok(optionsFields[2].equals("\"GALILEO\"") && optionsFields[3].equals("\"9\""),
                "CSV expert : constellation et identifiant satellite toujours conservés");
        String isbReferenceRow = RawMeasurementsFormatter.detailedCsvLineFull(
                1000L, 2000000L, "GPS", 12, "L1C", 0,
                1575.42e6, 40.0, null, null, null,
                0.0, 0.0, 0L, 0L, 0.0,
                0.0, 0.0, 0, null, null, null, 0,
                123L, null, null, null, 18, -100L, 1.0,
                null, null, null, null, null, null, null,
                2, null, true, 3, "E1C", 1575420000d,
                true, false, false, false);
        String[] isbFields = isbReferenceRow.split(",", -1);
        ok(isbFields.length == 47 && isbFields[44].equals("\"3\""),
                "CSV expert : constellation de référence ISB exportée");
        ok(isbFields[45].equals("\"E1C\"") && isbFields[46].contains("1575420000"),
                "CSV expert : code et fréquence de référence ISB exportés");
        ok(optionsFields[44].equals("\"\"") && optionsFields[45].equals("\"\"")
                        && optionsFields[46].equals("\"\""),
                "CSV expert : détails ISB rattachés à l'option Horloge détaillée");
        String nmeaSentence = "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47";
        String nmeaRow = RawMeasurementsFormatter.nmeaCsvLine(1234L, 5678L, nmeaSentence);
        ok(nmeaRow.startsWith("\"1234\",\"5678\""),
                "CSV expert : ligne NMEA horodatée en UTC et monotone");
        ok(nmeaRow.contains("\"NMEA\",\"" + nmeaSentence + "\""),
                "CSV expert : trame NMEA entière protégée comme un champ CSV malgré les virgules");
        String nmeaWithTerminator = nmeaSentence + "\r\n";
        String exactNmeaRow = RawMeasurementsFormatter.nmeaCsvLine(1234L, 5678L, nmeaWithTerminator);
        ok(exactNmeaRow.contains("\"NMEA\",\"" + nmeaWithTerminator + "\""),
                "CSV expert : terminateur de la trame NMEA conservé sans altération");
        ok(RawMeasurementsFormatter.CSV_HEADER.endsWith("clockIsbReferenceConstellationType,clockIsbReferenceCodeType,clockIsbReferenceCarrierFrequencyHz"),
                "CSV expert : colonnes recordType, NMEA et référence ISB explicites");

        // ============ FILETS DE SÉCURITÉ v5.2 (anti-perte de données) ============
        section("Nom de fichier GPX : pas de collision dans la même minute");
        // Bug réel : l'ancien format « HHmm » donnait le MÊME nom à deux traces
        // démarrées à 20 s d'intervalle. FileWriter(f,false) tronque, donc la
        // seconde écrasait la première sans avertissement.
        long t0 = 1_700_000_000_000L;
        String n1 = GpxWriter.fileName(t0);
        String n2 = GpxWriter.fileName(t0 + 20_000L);
        String n3 = GpxWriter.fileName(t0 + 60_000L);
        ok(n1.equals(n2), "deux traces dans la MEME minute : même nom (assumé, uniqueFile protège)");
        ok(!n1.equals(n3), "deux traces à 1 min d'écart : noms différents");
        ok(n1.length() == n2.length(), "format homogène (même longueur)");
        ok(n1.endsWith(".GPX"), "suffixe .GPX conservé");
        ok(!n1.contains(":") && !n1.contains(" "), "nom valide sur tous les systèmes de fichiers");
        ok(n1.matches("Trace_\\d{4}_\\d{2}_\\d{2}_\\d{2}h\\d{2}m(_v10)?\\.GPX"),
                "format respecté : Trace_AAAA_MM_JJ_HHhMMm.GPX (" + n1 + ")");
        ok(!GpxWriter.fileName(t0, GpxWriter.Version.V10)
                .equals(GpxWriter.fileName(t0, GpxWriter.Version.V11)),
                "GPX 1.0 et 1.1 ne se recouvrent pas");

        section("Budget de stockage : taille d'un point");
        GpxWriter.TrackPoint pp = new GpxWriter.TrackPoint(t0, 48.8584123, 2.2945678, 35.2);
        pp.speedMps = 1.42f; pp.courseDeg = 227.5f; pp.accuracyM = 4.8f; pp.hdop = 1.3f;
        int b11 = GpxWriter.pointXml(pp, GpxWriter.Version.V11)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int b10 = GpxWriter.pointXml(pp, GpxWriter.Version.V10)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        ok(b11 > 250 && b11 < 450, "un point GPX 1.1 fait ~350 octets (" + b11 + ")");
        ok(b10 < b11, "GPX 1.0 plus compact (" + b10 + " < " + b11 + ")");
        double moParHeure = b11 * 3600.0 / 1048576.0;
        ok(moParHeure < 2.0, String.format(java.util.Locale.FRANCE,
                "1 h de trace < 2 Mo (%.2f Mo)", moParHeure));

        System.out.println("\n=============================================");
        System.out.printf("RESULTAT : %d reussis, %d echoues%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    static String nmea(String body) {
        int checksum = 0;
        for (int i = 0; i < body.length(); i++) checksum ^= body.charAt(i);
        return String.format(java.util.Locale.ROOT, "$%s*%02X", body, checksum);
    }

    static void section(String name) {
        System.out.println("\n--- " + name + " ---");
    }
    static void ok(boolean cond, String label) {
        if (cond) { pass++; System.out.println("  OK   " + label); }
        else { fail++; System.out.println("  FAIL " + label); }
    }
    static void near(double expected, double actual, double tol, String label) {
        if (Math.abs(expected - actual) <= tol) {
            pass++; System.out.println("  OK   " + label + " -> " + String.format("%.6f", actual));
        } else {
            fail++; System.out.println("  FAIL " + label + " : attendu " + expected + " obtenu " + actual);
        }
    }
}
