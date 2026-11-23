package com.example.gpstest;

import java.util.ArrayList;
import java.util.List;

/**
 * POST-TRAITEMENT D'UNE TRACE — appliqué quand l'utilisateur termine.
 *
 * ══════════════════════════════════════════════════════════════════════
 * POURQUOI POST-TRAITER PLUTÔT QUE FILTRER PLUS FORT EN DIRECT
 * ══════════════════════════════════════════════════════════════════════
 *
 * En direct, on ne peut pas savoir ce qui va suivre. Un point qui semble
 * aberrant devient parfois correct quand on voit les dix suivants. Filtrer
 * agressivement en direct, c'est prendre une décision définitive avec la
 * moitié de l'information.
 *
 * C'est tout l'intérêt de votre proposition : enregistrer honnêtement,
 * nettoyer ensuite, quand toute la trace est connue.
 *
 * ══════════════════════════════════════════════════════════════════════
 * LES TROIS ÉTAPES, DANS CET ORDRE — ET POURQUOI CET ORDRE
 * ══════════════════════════════════════════════════════════════════════
 *
 *   1. DÉDOUBLONNAGE     Points identiques (même position) : un seul reste.
 *                         DOIT être fait en premier : sinon Douglas-Peucker
 *                         peut conserver deux points identiques en pensant
 *                         qu'ils délimitent un segment.
 *
 *   2. DÉBRUIT           Points dont la vitesse implicite dépasse ce que le
 *                         mode permet réellement. AVANT Douglas-Peucker :
 *                         un point aberrant tire le segment vers lui et fait
 *                         ensuite supprimer des points VOISINS légitimes.
 *                         L'ordre inverse détruit de la vraie trace.
 *
 *   3. DOUGLAS-PEUCKER   Simplification géométrique, segment par segment.
 *                         EN DERNIER, sur une trace déjà propre.
 *
 * ══════════════════════════════════════════════════════════════════════
 * CE QUE DOUGLAS-PEUCKER GARANTIT, ET CE QU'IL NE GARANTIT PAS
 * ══════════════════════════════════════════════════════════════════════
 *
 * Il garantit que tout point conservé est à moins de `tolerance` de la
 * ligne simplifiée. Il NE garantit PAS que la distance totale soit
 * conservée : un virage serré peut perdre quelques mètres. C'est le
 * compromis assumé — et c'est pour cela qu'on donne le choix de la
 * tolérance, avec un défaut prudent (3 m).
 *
 * ══════════════════════════════════════════════════════════════════════
 * LES POINTS DE COUPURE SONT INTANGIBLES
 * ══════════════════════════════════════════════════════════════════════
 *
 * Deux points de part et d'autre d'un tunnel ne doivent JAMAIS être reliés,
 * même après simplification. Le traitement est donc appliqué SÉPARÉMENT à
 * chaque segment, et un segment n'est jamais fusionné avec son voisin.
 */
public final class TrackPostProcess {

    private TrackPostProcess() { }

    /** Tolérance par défaut de Douglas-Peucker (mètres). */
    public static final double DEFAULT_TOLERANCE_M = 3.0;

    /** Tolérances proposées dans les réglages (mètres). 0 = pas de simplification. */
    public static final double[] TOLERANCE_CHOICES = {0.0, 1.0, 3.0, 5.0, 10.0};

    // ------------------------------------------------------------------
    // Un point simplifiable
    // ------------------------------------------------------------------

    /** Vue légère d'un point, pour ne pas dépendre de GpxWriter ni de GpxParser. */
    public static final class P {
        public final double lat, lon, ele;
        public final long timeMs;
        public final int segment;
        /** Index de la trace GPX d'origine (-1 pour les anciennes sources/tests). */
        public final int trackIndex;
        public final Float speedMps, courseDeg, hdop, accuracyM;
        /** Texte ISO original (pour préserver les fractions/fuseaux à l'export). */
        public final String timeIso;
        /** Texte décimal source de l'altitude, pour préserver sa précision lexicale. */
        public final String elevationText;
        /** Enfants XML d'origine du trkpt, extensions inconnues incluses. */
        public final String childrenXml;
        /** Texte d'origine des attributs lat/lon, pour éviter toute perte de précision. */
        public final String latitudeText, longitudeText;
        /** Marqueur interne : le point a-t-il été retenu ? */
        boolean kept = true;

        public P(double lat, double lon, double ele, long timeMs, int segment) {
            this(lat, lon, ele, timeMs, segment, null, null, null, null);
        }

        public P(double lat, double lon, double ele, long timeMs, int segment,
                 Float speedMps, Float courseDeg, Float hdop, Float accuracyM) {
            this(lat, lon, ele, timeMs, segment, -1, speedMps, courseDeg, hdop,
                    accuracyM, null, null, null, null, null);
        }

        public P(double lat, double lon, double ele, long timeMs, int segment,
                 int trackIndex, Float speedMps, Float courseDeg, Float hdop,
                 Float accuracyM, String timeIso, String childrenXml,
                 String latitudeText, String longitudeText) {
            this(lat, lon, ele, timeMs, segment, trackIndex, speedMps, courseDeg,
                    hdop, accuracyM, timeIso, null, childrenXml, latitudeText, longitudeText);
        }

        public P(double lat, double lon, double ele, long timeMs, int segment,
                 int trackIndex, Float speedMps, Float courseDeg, Float hdop,
                 Float accuracyM, String timeIso, String elevationText, String childrenXml,
                 String latitudeText, String longitudeText) {
            this.lat = lat; this.lon = lon; this.ele = ele; this.timeMs = timeMs;
            this.segment = segment; this.trackIndex = trackIndex;
            this.speedMps = speedMps; this.courseDeg = courseDeg;
            this.hdop = hdop; this.accuracyM = accuracyM;
            this.timeIso = timeIso; this.elevationText = elevationText;
            this.childrenXml = childrenXml;
            this.latitudeText = latitudeText; this.longitudeText = longitudeText;
        }
    }

    /** Statistiques d'un nettoyage, pour pouvoir le DIRE à l'utilisateur. */
    public static final class Report {
        public int inputPoints;
        public int outputPoints;
        public int duplicatesRemoved;
        public int outliersRemoved;
        public int simplifiedAway;

        public int removedTotal() { return inputPoints - outputPoints; }

        /** Pourcentage de réduction, arrondi. */
        public int reductionPercent() {
            if (inputPoints <= 0) return 0;
            return Math.round(100f * removedTotal() / inputPoints);
        }

        public String describe() {
            if (removedTotal() <= 0) return inputPoints + " points, aucun retiré";
            return inputPoints + " → " + outputPoints + " points ("
                    + reductionPercent() + " % retirés : "
                    + duplicatesRemoved + " doublons, "
                    + outliersRemoved + " aberrants, "
                    + simplifiedAway + " simplifiés)";
        }
    }

    // ------------------------------------------------------------------
    // Pipeline complet
    // ------------------------------------------------------------------

    /**
     * Nettoie une trace.
     *
     * @param in          points d'entrée (non modifiés)
     * @param toleranceM  tolérance Douglas-Peucker (0 = pas de simplification)
     * @param maxSpeedMps vitesse au-delà de laquelle un point est aberrant.
     *                    Utiliser une valeur LARGE (le mode ne doit pas
     *                    supprimer un vrai déplacement) ; NaN = pas de filtre.
     * @return les points retenus, dans l'ordre
     */
    public static List<P> clean(List<P> in, double toleranceM,
                                double maxSpeedMps, Report report) {
        if (in == null || in.isEmpty()) {
            if (report != null) report.inputPoints = 0;
            return new ArrayList<>();
        }
        Report r = (report != null) ? report : new Report();
        r.inputPoints = in.size();

        List<P> afterDup = removeDuplicates(in, r);
        List<P> afterOutliers = removeOutliers(afterDup, maxSpeedMps, r);
        List<P> result = simplify(afterOutliers, toleranceM, r);

        r.outputPoints = result.size();
        return result;
    }

    // ------------------------------------------------------------------
    // 1. Dédoublonnage
    // ------------------------------------------------------------------

    /**
     * Retire les points dont la position est IDENTIQUE au précédent.
     *
     * Pourquoi ça arrive : un récepteur bloque parfois sur une solution et
     * répète la même position pendant plusieurs secondes. Ces répétitions
     * n'ajoutent rien et faussent les statistiques (durée, points/segment).
     */
    static List<P> removeDuplicates(List<P> in, Report r) {
        List<P> out = new ArrayList<>(in.size());
        double prevLat = Double.NaN, prevLon = Double.NaN;
        int prevSeg = -1;
        for (P p : in) {
            boolean same = (p.segment == prevSeg)
                    && !Double.isNaN(prevLat)
                    && p.lat == prevLat && p.lon == prevLon;
            if (same) {
                r.duplicatesRemoved++;
                continue;
            }
            out.add(p);
            prevLat = p.lat;
            prevLon = p.lon;
            prevSeg = p.segment;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 2. Points aberrants
    // ------------------------------------------------------------------

    /**
     * Retire les points dont la vitesse implicite dépasse `maxSpeedMps`.
     *
     * Le point aberrant est retiré SANS être remplacé : le suivant est
     * comparé au dernier point CONSERVÉ. C'est essentiel — sinon une série
     * de deux points faux après un point bon ferait rejeter le point bon
     * suivant par contagion.
     *
     * Les points sans horodatage (timeMs <= 0) sont conservés : on ne peut
     * pas juger, et couper une trace parce qu'elle n'a pas d'heure serait
     * arbitraire.
     */
    static List<P> removeOutliers(List<P> in, double maxSpeedMps, Report r) {
        if (Double.isNaN(maxSpeedMps) || maxSpeedMps <= 0 || in.size() < 2) {
            return new ArrayList<>(in);
        }
        List<P> out = new ArrayList<>(in.size());
        out.add(in.get(0));
        P last = in.get(0);
        for (int i = 1; i < in.size(); i++) {
            P p = in.get(i);
            if (p.segment != last.segment || p.timeMs <= 0 || last.timeMs <= 0) {
                out.add(p);
                last = p;
                continue;
            }
            long dt = p.timeMs - last.timeMs;
            if (dt <= 0) {
                // Horodatage non croissant malgré notre garantie d'écriture :
                // le fichier a été modifié à la main. On garde le point, on
                // ne peut pas juger.
                out.add(p);
                last = p;
                continue;
            }
            double d = FixGeometry.distanceMeters(last.lat, last.lon, p.lat, p.lon);
            double speed = d / (dt / 1000.0);
            // LA VITESSE SEULE NE SUFFIT PAS (corrigé v5.12).
            //
            // Deux points voisins à 4,2 m l'un de l'autre en 1 s, c'est
            // 15 km/h : le plafond « marche » serait atteint par du BRUIT DE
            // POSITION sur une trace à l'arrêt. Ce n'est pas un aberrant,
            // c'est une imprécision de mesure. Mesuré sur une trace de test :
            // 9 points parfaitement légitimes supprimés pour cette raison.
            //
            // Un vrai aberrant est incohérent avec le VOISINAGE : il est loin
            // et il est isolé. On exige donc aussi une distance anormale.
            // Une randonnée à 15 km/h sur 40 m, ce n'est pas une mesure
            // imprécise : c'est un point qui n'est pas sur le chemin.
            if (speed > maxSpeedMps && d > OUTLIER_MIN_DISTANCE_M) {
                r.outliersRemoved++;
                continue;       // `last` n'est PAS mis à jour
            }
            out.add(p);
            last = p;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 3. Douglas-Peucker
    // ------------------------------------------------------------------

    /** Simplifie chaque segment indépendamment. */
    static List<P> simplify(List<P> in, double toleranceM, Report r) {
        if (toleranceM <= 0 || in.size() < 3) {
            r.simplifiedAway += 0;
            return new ArrayList<>(in);
        }
        List<P> out = new ArrayList<>(in.size());
        int start = 0;
        for (int i = 1; i <= in.size(); i++) {
            boolean boundary = (i == in.size()) || (in.get(i).segment != in.get(start).segment);
            if (!boundary) continue;

            // Segment [start, i-1]
            List<P> seg = in.subList(start, i);
            List<P> kept = rdp(seg, toleranceM);
            out.addAll(kept);
            r.simplifiedAway += seg.size() - kept.size();
            start = i;
            if (i == in.size()) break;
        }
        return out;
    }

    /** Douglas-Peucker itératif, sur une liste d'un seul segment.
     * Une implémentation récursive peut dépasser la pile Java sur une trace
     * très bruitée (jusqu'à 250 000 points acceptés par l'importeur). Le
     * tableau de travail garde les intervalles à traiter sans récursion.
     */
    static List<P> rdp(List<P> pts, double toleranceM) {
        int n = pts.size();
        if (n <= 2) return new ArrayList<>(pts);

        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        if (!rdpIterative(pts, toleranceM, keep)) {
            // Entrée pathologique (bruit quasi adversarial) : une simplification
            // incomplète serait trompeuse. On préfère conserver tous les points.
            return new ArrayList<>(pts);
        }

        // GARDE-FOU : UNE TRACE N'EST PAS UNE LIGNE — MAIS SEULEMENT QUAND
        // ELLE NE L'EST PAS (corrigé v5.12).
        //
        // Douglas-Peucker est CORRECT : il garantit que chaque point retiré
        // est à moins de `tolerance` de la polyligne. Mesuré sur une trace
        // bruitée de 300 points à tolérance 3 m : écart maximal réel 2,3 m.
        // Aucun besoin de le « réparer ».
        //
        // Le risque est ailleurs : sur une trace qui va tout droit pendant
        // des heures (une autoroute), « 2 points » est la BONNE réponse —
        // mais un lecteur qui ne voit que deux points peut croire à un
        // fichier abîmé. Et sur un tracé en zigzag serré, RDP peut retirer
        // beaucoup de forme si la tolérance est grande.
        //
        // Le garde-fou ne s'applique donc QUE si RDP a réellement
        // rapproché des points d'origine de la polyligne : on mesure l'écart
        // maximal AVANT de décider. Sur une droite parfaite il vaut 0 et le
        // garde-fou ne fait rien. C'est la différence entre « se protéger »
        // et « dégrader systématiquement ».
        boolean needGuard = false;
        for (int i = 1; i < n - 1; i++) {
            if (pointToSegmentMeters(pts.get(i), pts.get(0), pts.get(n - 1))
                    > toleranceM) {
                needGuard = true;
                break;
            }
        }
        if (needGuard) {
            // Au moins un point tous les 500 m de corde : on ne descend pas
            // sous une densité qui rendrait la trace illisible, même si la
            // tolérance l'autoriserait.
            double chord = FixGeometry.distanceMeters(pts.get(0).lat, pts.get(0).lon,
                    pts.get(n - 1).lat, pts.get(n - 1).lon);
            int minKeep = (int) Math.min(n, Math.max(2, Math.ceil(chord / 500.0)) + 1);
            int keptCount = 0;
            for (boolean k : keep) if (k) keptCount++;
            if (keptCount < minKeep) {
                for (int j = 1; j < minKeep - 1; j++) {
                    int idx = (int) Math.round((double) j * (n - 1) / (minKeep - 1));
                    keep[Math.min(idx, n - 2)] = true;
                }
            }
        }

        List<P> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) if (keep[i]) out.add(pts.get(i));
        return out;
    }

    private static boolean rdpIterative(List<P> pts, double toleranceM, boolean[] keep) {
        int n = pts.size();
        // Au maximum n-1 intervalles sont empilés. Deux int par intervalle.
        int[] stack = new int[Math.max(4, 2 * n)];
        int top = 0;
        long comparisons = 0L;
        // Limite le pire cas O(n²) des formes pathologiques. Pour une trace
        // normale, le travail est proche de n log(n). Si le budget est dépassé,
        // l'appelant revient à la liste brute plutôt que de livrer un résultat
        // partiellement simplifié.
        long maxComparisons = Math.min(5_000_000L, Math.max(100_000L, (long) n * 32L));
        stack[top++] = 0;
        stack[top++] = n - 1;

        while (top >= 2) {
            int last = stack[--top];
            int first = stack[--top];
            if (last <= first + 1) continue;

            P a = pts.get(first), b = pts.get(last);
            double maxDist = -1.0;
            int index = -1;
            for (int i = first + 1; i < last; i++) {
                if (++comparisons > maxComparisons) return false;
                double d = pointToSegmentMeters(pts.get(i), a, b);
                if (d > maxDist) {
                    maxDist = d;
                    index = i;
                }
            }
            if (maxDist > toleranceM && index > first && index < last) {
                keep[index] = true;
                // Empiler droite puis gauche : même ordre logique que la
                // récursion d'origine, sans consommer la pile d'appels.
                if (top + 4 > stack.length) {
                    throw new IllegalStateException("Pile RDP incohérente");
                }
                stack[top++] = index;
                stack[top++] = last;
                stack[top++] = first;
                stack[top++] = index;
            }
        }
        return true;
    }

    /**
     * Distance perpendiculaire d'un point à un segment, EN MÈTRES.
     *
     * Calcul en projection locale plutôt qu'en degrés : à 48° de latitude,
     * un degré de longitude vaut 74 km et un degré de latitude 111 km. Une
     * distance calculée en degrés serait fausse d'un facteur 1,5, et fausse
     * différemment selon la direction du segment — donc la simplification
     * ne serait pas isotrope.
     *
     * La projection locale (x = Δlon × cos(lat), y = Δlat) est exacte à
     * mieux de 0,1 % sur quelques kilomètres, ce qui est très au-delà de
     * nos tolérances (1 à 10 m).
     */
    static double pointToSegmentMeters(P p, P a, P b) {
        double latRef = Math.toRadians((a.lat + b.lat) / 2.0);
        double mPerDegLat = 111_132.0;
        double mPerDegLon = 111_320.0 * Math.cos(latRef);

        double pDeltaLon = shortestLongitudeDelta(a.lon, p.lon);
        double bDeltaLon = shortestLongitudeDelta(a.lon, b.lon);
        double px = pDeltaLon * mPerDegLon;
        double py = (p.lat - a.lat) * mPerDegLat;
        double bx = bDeltaLon * mPerDegLon;
        double by = (b.lat - a.lat) * mPerDegLat;

        double len2 = bx * bx + by * by;
        if (len2 <= 1e-12) {
            // Segment de longueur nulle : distance au point A.
            return Math.hypot(px, py);
        }
        // Projection scalaire, bornée au segment.
        double t = (px * bx + py * by) / len2;
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        double dx = px - t * bx;
        double dy = py - t * by;
        return Math.hypot(dx, dy);
    }


    /** Différence de longitude la plus courte, en degrés, pour l'antiméridien. */
    private static double shortestLongitudeDelta(double from, double to) {
        double d = (to - from) % 360.0;
        if (d > 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    // ------------------------------------------------------------------
    // Vitesse maximale plausible par mode
    // ------------------------------------------------------------------

    /**
     * Vitesse au-delà de laquelle un point est tenu pour aberrant, selon le
     * mode. Volontairement GÉNÉREUX : le but est d'écarter un saut
     * impossible, pas de juger un déplacement rapide.
     *
     *   MARCHE  :  15 km/h  (un marcheur rapide fait 7 km/h)
     *   VÉLO    :  80 km/h  (descente rapide)
     *   VOITURE : 250 km/h  (autoroute allemande)
     *   AUTRE   : 1200 km/h (Mach 1, avion)
     *
     * Toute valeur plus basse supprimerait de vrais déplacements. C'est le
     * même raisonnement que la suppression de la limite à 200 km/h : un
     * filtre trop serré détruit la trace au lieu de la nettoyer.
     */
    /**
     * Distance minimale pour qu'un point puisse être qualifié d'aberrant.
     *
     * En dessous, c'est du bruit de position (un GNSS à l'arrêt oscille de
     * 2 à 5 m), pas un saut. On ne jette jamais un point d'un mètre, quelle
     * que soit la « vitesse » que son horodatage lui prête.
     */
    public static final double OUTLIER_MIN_DISTANCE_M = 25.0;

    public static double maxSpeedFor(TrackMode.Mode mode) {
        switch (mode) {
            // LA MARCHE AUSSI PEUT ÊTRE RAPIDE (corrigé v5.12).
            // 15 km/h était trop serré : c'est la vitesse d'un coureur. Un
            // randonneur qui dévale une pente, un amateur de trail ou un
            // marcheur à vélo électrique plafonné dépasse 15 km/h par
            // moments. Avec 25 km/h on reste très en dessous de la vitesse
            // d'une voiture : le filtre garde tout son sens tout en ne
            // condamnant plus des traces réelles.
            case WALK: return 25.0 / 3.6;
            case BIKE: return 80.0 / 3.6;
            case CAR:  return 250.0 / 3.6;
            default:   return FixQuality.SPEED_SANITY_MPS;
        }
    }
}
