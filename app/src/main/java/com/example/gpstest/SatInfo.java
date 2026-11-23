package com.example.gpstest;

/**
 * Données d'un satellite observé.
 * Classe 100 % PURE (aucun import Android) : elle sert de brique commune
 * à l'affichage, au filtrage et au calcul de DOP, et reste testable hors appareil.
 */
public class SatInfo {
    public final String constellation;
    public final int svid;
    public final float azimuth, elevation, cn0;
    public final boolean inFix;
    public final double carrierHz;

    public SatInfo(String constellation, int svid, float azimuth,
                   float elevation, float cn0, boolean inFix, double carrierHz) {
        this.constellation = constellation;
        this.svid = svid;
        this.azimuth = azimuth;
        this.elevation = elevation;
        this.cn0 = cn0;
        this.inFix = inFix;
        this.carrierHz = carrierHz;
    }

    /** Bande radio déduite de la fréquence porteuse (L1, L5, E5b...). */
    public String band() {
        return RawMeasurementsFormatter.bandLabel(carrierHz);
    }

    /** La fréquence porteuse est-elle connue ? (non sur les vieux chipsets) */
    public boolean hasCarrier() {
        return !Double.isNaN(carrierHz) && carrierHz > 0;
    }

    /**
     * Satellite retenu dans la solution courante et doté d'un C/N0 exploitable.
     * Le DOP calculé doit refléter les satellites IN FIX (pas tous ceux visibles).
     */
    public boolean usableForDop() {
        // GnssStatus documente C/N0 sur [0, 63] dB-Hz et azimut sur
        // [0, 360] degrés. 360° est le même axe que 0° : il est normalisé
        // par DopCalculator, pas rejeté.
        return inFix && Float.isFinite(cn0) && cn0 > 0f && cn0 <= 63f
                && Float.isFinite(elevation) && elevation > 0f && elevation <= 90f
                && Float.isFinite(azimuth) && azimuth >= 0f && azimuth <= 360f;
    }

    @Override
    public String toString() {
        return constellation + " " + svid + (inFix ? " [utilisé]" : "") +
                " " + String.format(java.util.Locale.FRANCE, "%.0f dB-Hz", cn0);
    }
}
