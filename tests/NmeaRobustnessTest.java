package com.example.gpstest;

import java.util.Locale;

/** Tests renforcés des trames NMEA 0183 (validation, champs facultatifs et cas limites). */
public final class NmeaRobustnessTest {
    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        section("CHECKSUM ET IDENTIFICATION");
        String validGga = sentence("GNGGA,123519.25,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,1.2,0123");
        ok(NmeaParser.hasValidChecksum(validGga), "checksum calculé valide");
        ok(NmeaParser.hasAcceptableChecksum(validGga), "checksum valide accepté");
        ok(!NmeaParser.hasValidChecksum(validGga.replaceAll("\\*[0-9A-F]{2}$", "*00")),
                "checksum altéré refusé");
        ok(!NmeaParser.hasAcceptableChecksum(validGga.replaceAll("\\*[0-9A-F]{2}$", "*00")),
                "checksum présent mais invalide non ignoré");
        String noChecksum = validGga.substring(0, validGga.lastIndexOf('*'));
        ok(!NmeaParser.hasValidChecksum(noChecksum), "fonction stricte exige le checksum");
        ok(NmeaParser.hasAcceptableChecksum(noChecksum), "checksum absent toléré selon politique OEM");
        ok(!NmeaParser.hasAcceptableChecksum(noChecksum.replace(",", ",\n")),
                "contrôle caractères ASCII internes");
        ok("GGA".equals(NmeaParser.sentenceType(validGga)), "type GGA reconnu");
        ok(!NmeaParser.parseRmc(validGga).valid, "une phrase GGA n'est pas parsée comme RMC");

        section("GGA — FIX ET HDOP");
        NmeaParser.Gga gga = NmeaParser.parseGga(validGga);
        ok(gga.valid, "GGA valide");
        near(545.4, gga.altitude, 1e-9, "altitude en mètres");
        near(1.2, gga.differentialAgeSeconds, 1e-9, "âge de correction différentielle");
        ok(gga.differentialStationId == 123, "identifiant station différentiel");
        ok(NmeaParser.parseGga(noChecksum).valid, "GGA sans checksum mais sans erreur accepté");
        ok(!NmeaParser.parseGga(validGga.replace("123519.25", "")).valid,
                "heure UTC GGA obligatoire");
        ok(!NmeaParser.parseGga(validGga.replace(",1,08,", ",,08," )).valid,
                "qualité GGA obligatoire");
        ok(!NmeaParser.parseGga(validGga.replace(",0123", ",1024")).valid,
                "identifiant de station GGA hors plage rejeté");
        ok(!NmeaParser.parseGga(validGga.replace(",0.9,", ",NaN," )).valid,
                "HDOP NaN rejeté");
        ok(!NmeaParser.parseGga(validGga.replace(",545.4,", ",Infinity," )).valid,
                "altitude Infinity rejetée");
        ok(!NmeaParser.parseGga(validGga.replace(",0.9,", ",-0.1," )).valid,
                "HDOP négatif rejeté");
        ok(!NmeaParser.parseGga(validGga.replace(",545.4,M,", ",545.4,F," )).valid,
                "unité altitude non métrique rejetée");
        ok("SBAS / WAAS (NMEA 4.x)".equals(
                NmeaParser.parseGga(sentence("GPGGA,123519,4807.038,N,01131.000,E,9,08,0.9,545.4,M,46.9,M,,")).qualityLabel()),
                "code GGA 9 identifié SBAS/WAAS");
        NmeaParser.Gga twoDimensional = NmeaParser.parseGga(sentence(
                "GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,,M,,M,,"));
        ok(twoDimensional.valid && !Double.isFinite(twoDimensional.altitude),
                "GGA valide sans altitude (fix horizontal)");
        NmeaParser.Gga noFix = NmeaParser.parseGga(sentence("GPGGA,123519,,,,,0,00,,,,,,,"));
        ok(noFix.valid && noFix.fixQuality == 0, "GGA no-fix sans coordonnées accepté comme statut");
        ok(!NmeaParser.parseGga(sentence("GPGGA,123519,0000.000,N,00000.000,E,1,08,0.9,0.0,M,0.0,M,,")).valid,
                "coordonnées null island en GGA de qualité positive rejetées");

        section("GSA — DOP ET DIMENSION");
        NmeaParser.Gsa gsa3d = NmeaParser.parseGsa(sentence(
                "GNGSA,A,3,04,05,,09,12,,,24,,,,,2.5,1.3,2.1,1"));
        ok(gsa3d.valid && gsa3d.fixType == 3, "GSA 3D valide");
        near(1.3, gsa3d.hdop, 1e-9, "HDOP de GSA");
        ok(gsa3d.systemId == 1, "System ID NMEA 4.x");
        NmeaParser.Gsa gsaQzss = NmeaParser.parseGsa(sentence(
                "GNGSA,A,3,04,05,,09,12,,,24,,,,,2.5,1.3,2.1,0"));
        ok(gsaQzss.valid && gsaQzss.systemId == 0,
                "System ID 0 toléré pour profils récepteur qui l'emploient pour QZSS");
        NmeaParser.Gsa gsa2d = NmeaParser.parseGsa(sentence(
                "GPGSA,M,2,04,05,,,,,,,,,,,3.0,2.0,2.5"));
        ok(gsa2d.valid && gsa2d.fixType == 2, "GSA manuel 2D valide");
        ok(!NmeaParser.parseGsa(sentence("GPGSA,,3,04,05,,,,,,,,,,,2.5,1.3,2.1")).valid,
                "mode GSA obligatoire");
        ok(!NmeaParser.parseGsa(sentence("GPGSA,A,,04,05,,,,,,,,,,,2.5,1.3,2.1")).valid,
                "dimension GSA obligatoire");
        ok(!NmeaParser.parseGsa(sentence("GPGSA,A,3,04,05,,,,,,,,,,,NaN,1.3,2.1")).valid,
                "PDOP non numérique rejeté");
        ok(!NmeaParser.parseGsa(sentence("GPGSA,A,4,04,05,,,,,,,,,,,2.5,1.3,2.1")).valid,
                "dimension GSA hors 1..3 rejetée");
        ok(NmeaParser.parseGsa(sentence("GPGSA,A,3,00,05,,,,,,,,,,,2.5,1.3,2.1")).valid,
                "SVID 00 utilisé comme slot vide toléré");

        section("RMC / GLL / VTG");
        String rmcBody = "GNRMC,123519.00,A,4807.038,N,01131.000,E,022.4,084.4,230394,,,A,S";
        NmeaParser.Rmc rmc = NmeaParser.parseRmc(sentence(rmcBody));
        ok(rmc.valid, "RMC A valide");
        near(22.4, rmc.speedKnots, 1e-9, "vitesse RMC en nœuds");
        ok("A".equals(rmc.mode) && "S".equals(rmc.navigationStatus), "mode et statut navigation préservés");
        ok(!NmeaParser.parseRmc(sentence(rmcBody.replace(",A,4807", ",V,4807"))).valid,
                "RMC status V rejeté");
        ok(!NmeaParser.parseRmc(sentence(rmcBody.replace(",,,A,S", ",,,N,S"))).valid,
                "RMC mode N rejeté");
        ok(!NmeaParser.parseRmc(sentence(rmcBody.replace(",,,A,S", ",,,X,S"))).valid,
                "RMC mode inconnu rejeté");
        ok(!NmeaParser.parseRmc(sentence(rmcBody.replace(",022.4,", ",NaN,"))).valid,
                "vitesse RMC NaN rejetée");
        ok(!NmeaParser.parseRmc(sentence(rmcBody.replace(",230394,", ",310294,"))).valid,
                "date civile RMC impossible rejetée");
        NmeaParser.Rmc rmcNoSpeed = NmeaParser.parseRmc(sentence(
                "GPRMC,123519,A,4807.038,N,01131.000,E,,,230394,,,"));
        ok(rmcNoSpeed.valid && !Double.isFinite(rmcNoSpeed.speedKnots),
                "RMC valide avec vitesse/cap absents");
        ok(!NmeaParser.parseGll(sentence("GPGLL,4916.45,N,12311.12,W,225444,V,A")).valid,
                "GLL status V rejeté");
        ok(NmeaParser.parseGll(sentence("GPGLL,4916.45,N,12311.12,W,225444,A,A")).valid,
                "GLL valide");
        NmeaParser.Vtg vtg = NmeaParser.parseVtg(sentence("GNVTG,054.7,T,034.4,M,005.5,N,010.2,K,A"));
        ok(vtg.valid, "VTG valide");
        near(10.2, vtg.speedKmh, 1e-9, "vitesse VTG km/h");
        ok(!NmeaParser.parseVtg(sentence("GPVTG,054.7,T,034.4,M,-005.5,N,-010.2,K,A")).valid,
                "vitesse VTG négative rejetée");
        ok(!NmeaParser.parseVtg(sentence("GPVTG,NaN,T,034.4,M,005.5,N,010.2,K,A")).valid,
                "cap VTG NaN rejeté");

        section("ZDA / GNS / GST");
        NmeaParser.Zda zda = NmeaParser.parseZda(sentence("GPZDA,201530.00,04,07,2002,01,30"));
        ok(zda.valid && zda.localZoneHours == 1 && zda.localZoneMinutes == 30,
                "ZDA date et fuseau valides");
        ok(!NmeaParser.parseZda(sentence("GPZDA,201530.00,31,02,2002,00,00")).valid,
                "date ZDA impossible rejetée");
        ok(!NmeaParser.parseZda(sentence("GPZDA,201530.00,04,07,2002,14,00")).valid,
                "fuseau ZDA hors limites rejeté");
        NmeaParser.Gns gns = NmeaParser.parseGns(sentence(
                "GNGNS,123519.00,4807.038,N,01131.000,E,RR,12,0.8,545.4,46.9,1.2,0123"));
        ok(gns.valid && gns.hasAnyFixMode() && "RR".equals(gns.mode), "GNS multi-constellation valide");
        near(0.8, gns.hdop, 1e-9, "HDOP GNS");
        NmeaParser.Gns gnsNoFix = NmeaParser.parseGns(sentence("GNGNS,123519.00,,,,,NN,0,,,,,"));
        ok(gnsNoFix.valid && !gnsNoFix.hasAnyFixMode(), "GNS no-fix sans position valide comme état");
        ok(!NmeaParser.parseGns(sentence("GNGNS,123519.00,,,,,RR,0,0.8,,,,")).valid,
                "GNS mode actif sans coordonnées rejeté");
        ok(!NmeaParser.parseGns(sentence("GNGNS,123519.00,4807.038,N,01131.000,E,RR,12,NaN,545.4,46.9,,")).valid,
                "HDOP GNS NaN rejeté");
        ok(!NmeaParser.parseGns(sentence("GNGNS,123519.00,4807.038,N,01131.000,E,RX,12,0.8,545.4,46.9,,")).valid,
                "indicateur de mode GNS inconnu rejeté");
        NmeaParser.Gst gst = NmeaParser.parseGst(sentence("GPGST,024603.00,1.2,0.9,0.6,45.0,0.5,0.7,1.0"));
        ok(gst.valid, "GST statistiques valides");
        near(0.5, gst.latitudeSigmaM, 1e-9, "sigma latitude GST");
        ok(gst.summary().contains("lat 0,50 m"), "résumé GST lisible en français");
        ok(!NmeaParser.parseGst(sentence("GPGST,024603.00,1.2,0.9,0.6,45.0,NaN,0.7,1.0")).valid,
                "sigma GST NaN rejeté");

        section("COORDONNÉES, TEMPS ET DOP");
        near(90.0, NmeaParser.parseCoordinate("9000.000", "N"), 1e-9, "latitude limite +90 acceptée");
        near(-180.0, NmeaParser.parseCoordinate("18000.000", "W"), 1e-9, "longitude limite -180 acceptée");
        ok(throwsCoordinate("9000.001", "N"), "latitude au-delà de 90 rejetée");
        ok(throwsCoordinate("01160.000", "E"), "minutes coordonnées égales à 60 rejetées");
        ok(NmeaParser.validUtcTime("235959.999"), "heure UTC fin de journée valide");
        ok(!NmeaParser.validUtcTime("240000"), "heure UTC heure 24 rejetée");
        ok(!NmeaParser.validUtcTime("126060"), "heure UTC seconde 60 rejetée");
        ok(NmeaParser.dopQuality(Double.NaN).equals("—"), "DOP NaN non classé");
        ok(NmeaParser.dopQuality(Double.POSITIVE_INFINITY).equals("—"), "DOP infini non classé");

        System.out.printf(Locale.ROOT, "TOTAL : %d réussis, %d échoués%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    private static String sentence(String body) {
        int checksum = 0;
        for (int i = 0; i < body.length(); i++) checksum ^= body.charAt(i);
        return String.format(Locale.ROOT, "$%s*%02X", body, checksum);
    }
    private static boolean throwsCoordinate(String c, String h) {
        try { NmeaParser.parseCoordinate(c, h); return false; }
        catch (RuntimeException e) { return true; }
    }
    private static void section(String name) { System.out.println("\n--- " + name + " ---"); }
    private static void ok(boolean condition, String message) {
        if (condition) { pass++; System.out.println("OK   " + message); }
        else { fail++; System.out.println("FAIL " + message); }
    }
    private static void near(double expected, double actual, double tolerance, String message) {
        ok(Double.isFinite(actual) && Math.abs(expected - actual) <= tolerance,
                message + " (attendu " + expected + ", obtenu " + actual + ")");
    }
}
