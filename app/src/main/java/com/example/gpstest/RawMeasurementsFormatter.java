package com.example.gpstest;

import java.util.Locale;

/**
 * Formatage CSV pur des observations GNSS brutes.
 *
 * Important : GnssMeasurement fournit le taux de pseudodistance (Doppler),
 * mais pas une pseudodistance absolue prête à écrire. La colonne pseudorangeM
 * reste donc vide. Il serait dangereux de reconstruire une pseudo-mesure en
 * ignorant les états de synchronisation, les biais inter-signaux et le temps
 * de référence de l'horloge.
 */
public final class RawMeasurementsFormatter {

    private RawMeasurementsFormatter() { }

    /**
     * Schéma stable (47 colonnes) : les options décochées laissent leurs
     * colonnes vides, elles ne changent jamais la structure du CSV.
     * Les suffixes d'unités sont volontaires : ms != ns, Hz != MHz, m != m/s.
     */
    public static final String CSV_HEADER =
            "callbackUtcMillis,callbackElapsedRealtimeNanos,constellation,svid,codeType,measurementState,"
            + "carrierHz,band,cn0DbHz,basebandCn0DbHz,automaticGainControlDb,snrDb,pseudorangeM,"
            + "pseudorangeRateMps,pseudorangeRateUncertaintyMps,receivedSvTimeNanos,"
            + "receivedSvTimeUncertaintyNanos,timeOffsetNanos,adrMeters,adrUncertaintyMeters,adrState,"
            + "legacyCarrierCycles,legacyCarrierPhase,legacyCarrierPhaseUncertainty,multipath,"
            + "clockTimeNanos,clockTimeUncertaintyNanos,clockElapsedRealtimeNanos,"
            + "clockElapsedRealtimeUncertaintyNanos,clockLeapSecond,clockFullBiasNanos,clockBiasNanos,"
            + "clockBiasUncertaintyNanos,clockDriftNanosPerSecond,clockDriftUncertaintyNanosPerSecond,"
            + "fullInterSignalBiasNanos,fullInterSignalBiasUncertaintyNanos,satelliteInterSignalBiasNanos,"
            + "satelliteInterSignalBiasUncertaintyNanos,hardwareClockDiscontinuityCount,"
            + "eventAutomaticGainControlDb,isFullTracking,recordType,rawNmeaSentence,"
            + "clockIsbReferenceConstellationType,clockIsbReferenceCodeType,"
            + "clockIsbReferenceCarrierFrequencyHz";

    /**
     * Ancienne signature conservée pour les consommateurs/tests existants.
     * Les nouvelles colonnes sont simplement vides si elles ne sont pas
     * fournies par cet appelant.
     */
    public static String detailedCsvLine(long utcMillis, long elapsedRealtimeNanos,
            String system, int svid, String codeType, int measurementState,
            double carrierHz, double cn0, double pseudorangeRate,
            double pseudorangeRateUncertainty, long receivedSvTimeNanos,
            long receivedSvTimeUncertaintyNanos, double timeOffsetNanos,
            double adrMeters, double adrUncertaintyMeters, int adrState, int multipath,
            boolean includeTime, boolean includeSignal, boolean includeDoppler,
            boolean includePhase, long clockTimeNanos, Long fullBiasNanos,
            Double biasNanos, Double biasUncertaintyNanos,
            Double driftNanosPerSecond, Double driftUncertainty,
            int hardwareClockDiscontinuityCount) {
        return detailedCsvLineFull(utcMillis, elapsedRealtimeNanos, system, svid,
                codeType, measurementState, carrierHz, cn0, null, null, null,
                pseudorangeRate, pseudorangeRateUncertainty,
                receivedSvTimeNanos, receivedSvTimeUncertaintyNanos, timeOffsetNanos,
                adrMeters, adrUncertaintyMeters, adrState, null, null, null, multipath,
                clockTimeNanos, null, null, null, null,
                fullBiasNanos, biasNanos, biasUncertaintyNanos,
                driftNanosPerSecond, driftUncertainty, null, null, null, null,
                hardwareClockDiscontinuityCount, null, null, null, null, null,
                includeTime, includeSignal, includeDoppler, includePhase);
    }

    /**
     * Formateur complet utilisé par TrackingService pour alimenter l’écran Expert et le journal CSV. Les champs optionnels
     * non disponibles sur l'appareil sont passés à null/NaN et restent vides.
     */
    public static String detailedCsvLineFull(long utcMillis, long elapsedRealtimeNanos,
            String system, int svid, String codeType, int measurementState,
            double carrierHz, double cn0, Double basebandCn0, Double automaticGainControlDb,
            Double snrDb, double pseudorangeRate, double pseudorangeRateUncertainty,
            long receivedSvTimeNanos, long receivedSvTimeUncertaintyNanos,
            double timeOffsetNanos, double adrMeters, double adrUncertaintyMeters,
            int adrState, Long carrierCycles, Double carrierPhase,
            Double carrierPhaseUncertainty, int multipath,
            long clockTimeNanos, Double clockTimeUncertaintyNanos,
            Long clockElapsedRealtimeNanos, Double clockElapsedRealtimeUncertaintyNanos,
            Integer leapSecond, Long fullBiasNanos, Double biasNanos,
            Double biasUncertaintyNanos, Double driftNanosPerSecond,
            Double driftUncertainty, Double fullInterSignalBiasNanos,
            Double fullInterSignalBiasUncertaintyNanos,
            Double satelliteInterSignalBiasNanos,
            Double satelliteInterSignalBiasUncertaintyNanos,
            int hardwareClockDiscontinuityCount,
            Double eventAutomaticGainControlDb, Boolean fullTracking,
            Integer clockIsbReferenceConstellationType, String clockIsbReferenceCodeType,
            Double clockIsbReferenceCarrierFrequencyHz,
            boolean includeTime, boolean includeSignal,
            boolean includeDoppler, boolean includePhase) {

        String[] f = new String[] {
                Long.toString(utcMillis),                                    // 0 indispensable
                Long.toString(elapsedRealtimeNanos),                          // 1 indispensable
                safe(system), Integer.toString(svid), safe(codeType),
                Integer.toString(measurementState),                           // 2..5
                includeSignal ? num(carrierHz) : "",                         // 6
                includeSignal ? bandLabel(carrierHz) : "",                   // 7
                includeSignal ? num(cn0) : "",                               // 8
                includeSignal ? nullable(basebandCn0) : "",                 // 9
                includeSignal ? nullable(automaticGainControlDb) : "",      // 10
                includeSignal ? nullable(snrDb) : "",                        // 11
                "",                                                           // 12 pseudorange non calculée
                includeDoppler ? num(pseudorangeRate) : "",                 // 13
                includeDoppler ? num(pseudorangeRateUncertainty) : "",      // 14
                includeDoppler ? Long.toString(receivedSvTimeNanos) : "",   // 15
                includeDoppler ? Long.toString(receivedSvTimeUncertaintyNanos) : "", // 16
                includeDoppler ? num(timeOffsetNanos) : "",                 // 17
                includePhase ? num(adrMeters) : "",                          // 18
                includePhase ? num(adrUncertaintyMeters) : "",               // 19
                includePhase ? Integer.toString(adrState) : "",              // 20
                includePhase ? nullable(carrierCycles) : "",                // 21 legacy
                includePhase ? nullable(carrierPhase) : "",                 // 22 legacy
                includePhase ? nullable(carrierPhaseUncertainty) : "",      // 23 legacy
                includePhase ? Integer.toString(multipath) : "",             // 24
                includeTime ? Long.toString(clockTimeNanos) : "",           // 25
                includeTime ? nullable(clockTimeUncertaintyNanos) : "",      // 26
                includeTime ? nullable(clockElapsedRealtimeNanos) : "",      // 27
                includeTime ? nullable(clockElapsedRealtimeUncertaintyNanos) : "", // 28
                includeTime ? nullable(leapSecond) : "",                    // 29
                includeTime ? nullable(fullBiasNanos) : "",                 // 30
                includeTime ? nullable(biasNanos) : "",                     // 31
                includeTime ? nullable(biasUncertaintyNanos) : "",          // 32
                includeTime ? nullable(driftNanosPerSecond) : "",           // 33
                includeTime ? nullable(driftUncertainty) : "",              // 34
                includeDoppler ? nullable(fullInterSignalBiasNanos) : "",   // 35
                includeDoppler ? nullable(fullInterSignalBiasUncertaintyNanos) : "", // 36
                includeDoppler ? nullable(satelliteInterSignalBiasNanos) : "", // 37
                includeDoppler ? nullable(satelliteInterSignalBiasUncertaintyNanos) : "", // 38
                includeTime ? Integer.toString(hardwareClockDiscontinuityCount) : "", // 39
                includeSignal ? nullable(eventAutomaticGainControlDb) : "",    // 40
                includeTime ? nullable(fullTracking) : "",                   // 41
                "MEASUREMENT",                                              // 42
                "",                                                         // 43 raw NMEA
                includeTime && clockIsbReferenceConstellationType != null
                        ? clockIsbReferenceConstellationType.toString() : "", // 44
                includeTime ? safe(clockIsbReferenceCodeType) : "",          // 45
                includeTime ? nullable(clockIsbReferenceCarrierFrequencyHz) : "" // 46
        };
        StringBuilder out = new StringBuilder(512);
        for (int i = 0; i < f.length; i++) {
            if (i > 0) out.append(',');
            out.append(csv(f[i]));
        }
        return out.toString();
    }

    /**
     * Ligne séparée pour GnssAutomaticGainControl (API 33+). Ces valeurs
     * décrivent un signal/une bande, pas un satellite individuel : elles ne
     * doivent pas être attribuées arbitrairement à chaque GnssMeasurement.
     * La ligne reste enregistrée même quand la collection de mesures est vide.
     */
    public static String agcEventCsvLine(long utcMillis, long elapsedRealtimeNanos,
            String constellation, long carrierHz, double levelDb,
            long clockTimeNanos, Long fullBiasNanos, Double biasNanos,
            Integer leapSecond, int clockDiscontinuity, Boolean fullTracking,
            boolean includeTime) {
        return agcEventCsvLineFull(utcMillis, elapsedRealtimeNanos, constellation,
                carrierHz, levelDb, clockTimeNanos, fullBiasNanos, biasNanos,
                leapSecond, clockDiscontinuity, fullTracking, null, null, null, includeTime);
    }

    public static String agcEventCsvLineFull(long utcMillis, long elapsedRealtimeNanos,
            String constellation, long carrierHz, double levelDb,
            long clockTimeNanos, Long fullBiasNanos, Double biasNanos,
            Integer leapSecond, int clockDiscontinuity, Boolean fullTracking,
            Integer clockIsbReferenceConstellationType, String clockIsbReferenceCodeType,
            Double clockIsbReferenceCarrierFrequencyHz, boolean includeTime) {
        String[] f = new String[47];
        java.util.Arrays.fill(f, "");
        // Les deux horodatages de réception sont toujours présents : sans
        // eux, les lignes ne seraient ni ordonnables ni corrélables avec une
        // horloge UTC/monotone. includeTime ne masque que le détail GnssClock.
        f[0] = Long.toString(utcMillis);
        f[1] = Long.toString(elapsedRealtimeNanos);
        if (includeTime) {
            f[25] = Long.toString(clockTimeNanos);
            f[29] = leapSecond == null ? "" : Integer.toString(leapSecond);
            f[30] = nullable(fullBiasNanos);
            f[31] = nullable(biasNanos);
            f[39] = Integer.toString(clockDiscontinuity);
            f[41] = nullable(fullTracking);
        }
        f[2] = safe(constellation);
        f[6] = Long.toString(carrierHz);
        f[7] = bandLabel(carrierHz);
        f[40] = num(levelDb);
        f[42] = "AGC";
        f[43] = "";
        if (includeTime) {
            f[44] = clockIsbReferenceConstellationType == null
                    ? "" : clockIsbReferenceConstellationType.toString();
            f[45] = safe(clockIsbReferenceCodeType);
            f[46] = nullable(clockIsbReferenceCarrierFrequencyHz);
        }
        StringBuilder out = new StringBuilder(256);
        for (int i = 0; i < f.length; i++) {
            if (i > 0) out.append(',');
            out.append(csv(f[i]));
        }
        return out.toString();
    }

    /** Ligne NMEA brute dans le même schéma CSV, sans interpréter ni modifier la trame. */
    public static String nmeaCsvLine(long utcMillis, long elapsedRealtimeNanos, String sentence) {
        String[] f = new String[47];
        java.util.Arrays.fill(f, "");
        f[0] = Long.toString(utcMillis);
        f[1] = Long.toString(elapsedRealtimeNanos);
        f[42] = "NMEA";
        f[43] = sentence == null ? "" : sentence;
        StringBuilder out = new StringBuilder(256);
        for (int i = 0; i < f.length; i++) {
            if (i > 0) out.append(',');
            out.append(csv(f[i]));
        }
        return out.toString();
    }

    /** Format historique à 11 colonnes conservé pour compatibilité. */
    public static String csvLine(long utcMillis, String system, int svid,
            double carrierHz, double cn0, double pseudorangeRate,
            double adrMeters, double timeOffsetNanos, int state, int multipath) {
        return String.format(Locale.ROOT, "%d,%s,%d,%s,%s,%s,%s,%s,%d,%d,%s",
                utcMillis, system == null ? "" : system, svid,
                num(carrierHz), bandLabel(carrierHz), num2(cn0),
                num2(pseudorangeRate), num2(adrMeters), state, multipath,
                num2(timeOffsetNanos));
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static String csv(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    private static String num(double v) {
        if (!Double.isFinite(v)) return "";
        return String.format(Locale.ROOT, "%.3f", v);
    }
    private static String num2(double v) {
        if (!Double.isFinite(v)) return "";
        return String.format(Locale.ROOT, "%.2f", v);
    }
    private static String nullable(Double v) { return v == null ? "" : num(v); }
    private static String nullable(Long v) { return v == null ? "" : Long.toString(v); }
    private static String nullable(Integer v) { return v == null ? "" : Integer.toString(v); }
    private static String nullable(Boolean v) { return v == null ? "" : Boolean.toString(v); }

    /** Fréquence porteuse -> bande indicative. */
    public static String bandLabel(double hz) {
        if (Double.isNaN(hz) || hz <= 0) return "?";
        double mhz = hz / 1e6;
        if (Math.abs(mhz - 1575.42) < 2.5) return "L1";
        if (Math.abs(mhz - 1176.45) < 2.5) return "L5";
        if (Math.abs(mhz - 1227.60) < 2.5) return "L2";
        if (Math.abs(mhz - 1207.14) < 2.5) return "B2/E5b";
        if (Math.abs(mhz - 1191.795) < 2.5) return "E5";
        if (Math.abs(mhz - 1278.75) < 2.5) return "E6";
        if (Math.abs(mhz - 1561.098) < 2.5) return "B1";
        if (Math.abs(mhz - 1600.995) < 2.5) return "G1";
        if (Math.abs(mhz - 1246.0) < 3.0) return "G2";
        if (Math.abs(mhz - 2492.028) < 3.0) return "B3";
        return String.format(Locale.ROOT, "%.2f MHz", mhz);
    }
    public static String carrierLabel(double hz) {
        if (Double.isNaN(hz) || hz <= 0) return "—";
        return String.format(Locale.ROOT, "%s %.0f MHz", bandLabel(hz), hz / 1e6);
    }
}
