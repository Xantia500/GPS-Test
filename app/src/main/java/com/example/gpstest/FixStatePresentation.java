package com.example.gpstest;

/**
 * Présentation pure de la dimension du fix : pas de dépendance Android,
 * testable sur la JVM et réutilisée telle quelle par StatusHud.
 */
public final class FixStatePresentation {
    private FixStatePresentation() { }

    /** Le type GSA récent prime. hasAltitude seul ne prouve pas un fix 3D. */
    public static String fixLabel(int receiverFixType, boolean hasAltitude, boolean hasAnyFix) {
        if (!hasAnyFix) return "No fix";
        if (receiverFixType == 2) return "2D fix";
        if (receiverFixType == 3) return "3D fix";
        return hasAltitude ? "Fix + alt." : "Fix horizontal";
    }

    /** 2D = ambre, 3D explicitement déclaré = vert, inconnu = ambre prudent. */
    public static int fixColorRes(int receiverFixType, boolean hasAnyFix, boolean hasAltitude) {
        if (!hasAnyFix) return R.color.gps_danger;
        if (receiverFixType == 3) return R.color.gps_green;
        return R.color.gps_warn;
    }
}
