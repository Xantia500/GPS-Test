package com.example.gpstest;

/**
 * POSITION ENTIÈRE SIGNÉE 32 BITS — virgule fixe, et une correction importante
 * sur « Q3.6 ».
 *
 * ══════════════════════════════════════════════════════════════════════
 * CE QUE VOUS AVEZ DEMANDÉ, ET CE QUE ÇA DONNE VRAIMENT
 * ══════════════════════════════════════════════════════════════════════
 *
 * Vous avez demandé : « un entier signé 32 bits en virgule fixe ±180.000000
 * (sQ3.6) ». Deux lectures sont possibles, et elles ne se valent pas du tout.
 *
 * ── Lecture A : Q3.6 au sens BINAIRE (26 bits entiers, 6 bits fraction) ──
 *
 *     pas = 2^-6 = 0,015625°
 *     0,015625° × 111 320 m/° ≈ **1 740 mètres**
 *
 * **1,7 km de résolution.** Pour une trace GPS, c'est inutilisable : deux
 * points consécutifs à 1 m d'intervalle tombent sur le même entier, et une
 * position arrondie à 1,7 km près n'est pas une position.
 *
 * ── Lecture B : « ±180.000000 » au sens DÉCIMAL (3 chiffres avant, 6 après) ──
 *
 *     pas = 10^-6 ° = 1 microdegré ≈ **0,111 mètre** (11 cm)
 *     180 000 000 tient dans un int32 (max 2 147 483 647)
 *
 * **11 cm de résolution, et ça tient exactement dans un int32 signé.**
 *
 * C'est la lecture B qui est implémentée : c'est la seule qui sert à quelque
 * chose pour du GNSS, et c'est aussi la seule qui donne *exactement*
 * « ±180.000000 » comme vous l'avez écrit. Le nom « sQ3.6 » décrit bien la
 * forme décimale (signe, 3 chiffres entiers, 6 décimaux), mais la « Q »
 * binaire classique désigne autre chose — d'où cette mise au point.
 *
 * ══════════════════════════════════════════════════════════════════════
 * POURQUOI GARDER LES DEUX
 * ══════════════════════════════════════════════════════════════════════
 *
 * Le moteur travaille en double (fp64). Un double a 53 bits de mantisse :
 * pour une longitude de 180°, le pas est de l'ordre de 1e-15° — soit
 * 0,0000000000001 mètre. Le double est donc LARGEMENT suffisant, et c'est
 * la référence.
 *
 * La version entière 32 bits ne remplace pas le double, elle répond à
 * d'autres besoins :
 *   - COMPARAISON EXACTE : deux positions sont égales ou non, sans epsilon ;
 *   - STOCKAGE COMPACT : 8 octets pour latitude+longitude au lieu de 16 ;
 *   - EMPREINTE pour détecter un point figé (le GPS qui renvoie la même
 *     position en boucle) : identité stricte, sans tolérance flottante ;
 *   - PORTABILITÉ : un entier de 32 bits se transmet, se hache et se
 *     compare à l'identique sur toutes les plateformes.
 *
 * Classe PURE : aucun import Android, entièrement testable.
 */
public final class FixGeometry {

    private FixGeometry() { }

    // ------------------------------------------------------------------
    // Virgule fixe
    // ------------------------------------------------------------------

    /** Facteur d'échelle : 1 degré = 1 000 000 microdegrés. */
    public static final int SCALE = 1_000_000;

    /** Borne : ±180.000000° = ±180 000 000 microdegrés. */
    public static final int MAX_MICRO = 180 * SCALE;

    /**
     * Encode une latitude ou longitude en microdegrés.
     *
     * Tronque (et non arrondit) vers le zéro pour les valeurs négatives ?
     * NON : on utilise Math.round, qui arrondit au plus proche en valeur
     * absolue — jamais de biais systématique vers le sud ou l'ouest.
     *
     * Lève IllegalArgumentException hors bornes : une position de 200° est
     * une donnée corrompue, pas une position à rogner silencieusement.
     */
    public static int toMicro(double degrees) {
        if (Double.isNaN(degrees) || Double.isInfinite(degrees)) {
            throw new IllegalArgumentException("Position non finie : " + degrees);
        }
        if (degrees > 180.0 || degrees < -180.0) {
            throw new IllegalArgumentException("Hors ±180° : " + degrees);
        }
        return (int) Math.round(degrees * SCALE);
    }

    /** Décode des microdegrés en degrés. */
    public static double fromMicro(int micro) {
        return micro / (double) SCALE;
    }

    /**
     * Encode une latitude. Variante stricte : au-delà de ±90°, la valeur est
     * une erreur de calcul, pas une position.
     *
     * @return le code, ou Integer.MIN_VALUE si la valeur est invalide — un
     *         sentinelle explicite plutôt qu'une exception, parce que ce
     *         chemin est appelé à chaque point et qu'une exception y
     *         coûterait une pile de traces.
     */
    public static int toLatMicro(double lat) {
        if (Double.isNaN(lat) || lat > 90.0 || lat < -90.0) return Integer.MIN_VALUE;
        return toMicro(lat);
    }

    /** Idem pour la longitude, avec la borne ±180°. */
    public static int toLonMicro(double lon) {
        if (Double.isNaN(lon) || lon > 180.0 || lon < -180.0) return Integer.MIN_VALUE;
        return toMicro(lon);
    }

    /**
     * Variante « Q3.6 binaire » (26.6), conservée UNIQUEMENT pour comparaison
     * et pour la documentation. Voir l'avertissement en tête de classe :
     * le pas est de 0,015625°, soit ~1,7 km. Ne pas l'utiliser pour une
     * trace — elle est là pour que le chiffre soit vérifiable, pas pour
     * servir.
     */
    public static int toBinaryQ3_6(double degrees) {
        return (int) Math.round(degrees * 64.0);    // 2^6
    }

    public static double fromBinaryQ3_6(int raw) {
        return raw / 64.0;
    }

    // ------------------------------------------------------------------
    // Distance
    // ------------------------------------------------------------------

    /** Rayon moyen de la Terre (IUGG), en mètres. */
    public static final double EARTH_RADIUS_M = 6_371_008.8;

    /**
     * Distance orthodromique (great-circle) entre deux positions, en mètres.
     *
     * Formule de HAVERSINE, et pas la loi des cosinus : pour de petites
     * distances — exactement notre cas, deux points de trace consécutifs —
     * la loi des cosinus souffre d'une annulation catastrophique en double
     * précision (acos d'un nombre très proche de 1). Haversine reste stable
     * jusqu'au mètre, et même en dessous.
     *
     * @return distance en mètres, ou NaN si une entrée est invalide.
     */
    public static double distanceMeters(double lat1, double lon1,
                                        double lat2, double lon2) {
        if (Double.isNaN(lat1) || Double.isNaN(lon1)
                || Double.isNaN(lat2) || Double.isNaN(lon2)) return Double.NaN;

        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2)
                  * Math.sin(dl / 2) * Math.sin(dl / 2);
        // Clamp défensif : une erreur d'arrondi peut donner a = 1 + 1e-16,
        // et asin d'un nombre > 1 vaut NaN. On ne perd pas une trace pour ça.
        double c = 2 * Math.asin(Math.min(1.0, Math.sqrt(a)));
        return EARTH_RADIUS_M * c;
    }

    /** Vrai si les deux positions sont identiques au microdegré près. */
    public static boolean sameFixMicro(double lat1, double lon1,
                                       double lat2, double lon2) {
        return toLatMicro(lat1) == toLatMicro(lat2)
                && toLonMicro(lon1) == toLonMicro(lon2);
    }

    /**
     * Vitesse implicite entre deux points, en m/s.
     *
     * @return NaN si l'intervalle de temps est nul ou négatif.
     */
    public static double impliedSpeedMps(double lat1, double lon1, long t1Ms,
                                         double lat2, double lon2, long t2Ms) {
        long dt = t2Ms - t1Ms;
        if (dt <= 0) return Double.NaN;
        double d = distanceMeters(lat1, lon1, lat2, lon2);
        if (Double.isNaN(d)) return Double.NaN;
        return d / (dt / 1000.0);
    }
}
