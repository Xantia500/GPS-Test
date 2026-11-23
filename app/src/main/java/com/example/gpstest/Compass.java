package com.example.gpstest;

import java.util.Locale;

/**
 * BOUSSOLE — logique pure : nord de référence, déclinaison, caps.
 *
 * ══════════════════════════════════════════════════════════════════════
 * MAGNÉTIQUE OU VRAI ? LES DEUX SONT UTILES, ET NE PAS LE DIRE EST UN BUG
 * ══════════════════════════════════════════════════════════════════════
 *
 * Le capteur de rotation donne un azimut **magnétique** : l'angle entre
 * l'axe de l'appareil et le nord pointé par l'aiguille, c'est-à-dire le
 * champ magnétique terrestre. Or le nord magnétique n'est pas le nord
 * géographique — l'écart est la DÉCLINAISON, qui vaut :
 *
 *      +1,5°  à Paris      −14°  à Montréal      −24°  au Cap
 *      +15°  à Vancouver   −7°   à Tokyo         +12°  à Los Angeles
 *
 * ATTENTION AU SENS DE L'EXEMPLE : le Canada de l'EST et l'Afrique du Sud
 * sont à déclinaison NÉGATIVE (le nord magnétique y est à l'OUEST du vrai).
 * C'est l'inverse de ce que répète la formule mnémotechnique anglaise
 * « declination east is positive », souvent mal recopiée.
 *
 * Une boussole embarquée qui n'en tient pas compte n'est pas seulement
 * imprécise : elle est FAUSSE d'un angle croissant avec la latitude, sans
 * le moindre avertissement. Sur un outil de navigation, c'est le genre
 * d'erreur qui fait arriver au mauvais endroit.
 *
 * Deux corrections possibles, et ce fichier implémente les deux :
 *
 *   1. afficher au nord magnétique, en le DISANT (« MAG ») ;
 *   2. afficher au nord vrai, en ajoutant la déclinaison du lieu.
 *
 * Le choix est une préférence utilisateur (voir {@link North}). Le calcul
 * de la déclinaison elle-même vit dans MainActivity, parce qu'il repose sur
 * android.hardware.GeomagneticField, absent d'un JVM de bureau. Tout ce qui
 * peut être testé sans Android est ICI, dans une classe sans dépendance.
 *
 * ══════════════════════════════════════════════════════════════════════
 * CONVENTION DE SIGNE (source de bugs classiques)
 * ══════════════════════════════════════════════════════════════════════
 *
 * La déclinaison est EST-POSITIVE, comme la renvoie GeomagneticField :
 *
 *      nordVrai = nordMagnétique + déclinaison
 *
 * À Paris, lire 10° au magnétique donne 11,5° au vrai. À Montréal, les
 * mêmes 10° au magnétique donnent −4°, c'est-à-dire 356° une fois ramenés
 * dans [0, 360). Le sens du signe est inversé dans beaucoup de
 * documentation grand public : on ne code donc jamais un signe « au
 * feeling », on passe par {@link #toReference} — et on vérifie par le cas
 * limite « viser le nord magnétique » (voir CompassTest).
 */
public final class Compass {

    private Compass() { }

    // ------------------------------------------------------------------
    // Préférence : nord de référence
    // ------------------------------------------------------------------

    /**
     * Nord de référence choisi par l'utilisateur.
     *
     * Même patron que {@link Units.System} : un identifiant stable en
     * minuscules persisté dans les préférences, un libellé affichable, et
     * un {@code fromId} qui ne lève jamais. Une préférence illisible (clé
     * absente, valeur d'une version future) retombe sur le défaut au lieu
     * de planter au démarrage.
     */
    public enum North {
        /** Azimut brut du capteur, sans correction. Défaut : aucun calcul. */
        MAGNETIC("magnetic", "Nord magnétique", "MAG"),
        /** Azimut corrigé de la déclinaison du lieu. */
        TRUE("true", "Nord vrai (géographique)", "VRAI");

        public final String id;
        public final String label;
        /** Étiquette courte affichée sous le cadran. */
        public final String shortLabel;

        North(String id, String label, String shortLabel) {
            this.id = id;
            this.label = label;
            this.shortLabel = shortLabel;
        }

        /** Lecture d'une préférence. Ne lève jamais. */
        public static North fromId(String id) {
            if (id != null) {
                for (North n : values()) {
                    if (n.id.equals(id)) return n;
                }
            }
            return MAGNETIC;
        }
    }

    // ------------------------------------------------------------------
    // Géométrie angulaire
    // ------------------------------------------------------------------

    /**
     * Ramène un angle quelconque dans [0, 360).
     *
     * « Quelconque » veut dire : négatif (-30 → 330), supérieur à un tour
     * (370 → 10), ou déjà bon. NaN et l'infini traversent tels quels : un
     * azimut absent doit rester absent, pas devenir 0 (qui est un cap
     * parfaitement valide, le nord — on ne veut pas le fabriquer).
     */
    public static float normalize(float deg) {
        if (Float.isNaN(deg) || Float.isInfinite(deg)) return Float.NaN;
        float d = deg % 360f;
        if (d < 0f) d += 360f;
        // Un angle comme 359,99999 % 360 vaut 359,99999 : on n'y touche
        // pas. Mais 360f % 360f vaut 0 côté float, et -0.0f passe le test
        // « < 0 » ; ce garde-fou couvre l'arrondi d'un +360 sur un décimal
        // négatif infime.
        if (d >= 360f) d -= 360f;
        return d;
    }

    /**
     * Écart le plus court entre deux caps, dans ]-180, +180].
     *
     * Sert à l'animation du cadran : passer de 350° à 10° doit tourner de
     * **20° vers la droite**, pas de 340° vers la gauche. Sans cette
     * fonction, l'aiguille fait un tour complet à chaque passage par le
     * nord — le défaut visuel le plus courant des boussoles logicielles.
     */
    public static float shortestDelta(float fromDeg, float toDeg) {
        if (Float.isNaN(fromDeg) || Float.isNaN(toDeg)) return Float.NaN;
        float d = (toDeg - fromDeg) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /**
     * Azimut du capteur (magnétique) vers l'azimut affiché.
     *
     * C'est LE seul endroit qui applique la déclinaison, dans le bon sens.
     * Si le nord vrai est demandé mais que la déclinaison est inconnue
     * (pas encore de position), on retombe sur le magnétique : mieux vaut
     * un cap exact au magnétique et étiqueté comme tel qu'un cap vrai
     * fabriqué avec une déclinaison supposée nulle.
     */
    public static float toReference(float magneticDeg, float declinationDeg, North ref) {
        if (Float.isNaN(magneticDeg)) return Float.NaN;
        if (ref == North.TRUE && !Float.isNaN(declinationDeg)) {
            return normalize(magneticDeg + declinationDeg);
        }
        return normalize(magneticDeg);
    }

    /** Le nord vrai est-il réellement appliqué, ou demandé sans données ? */
    public static boolean trueNorthApplied(North ref, float declinationDeg) {
        return ref == North.TRUE && !Float.isNaN(declinationDeg);
    }

    // ------------------------------------------------------------------
    // Affichage
    // ------------------------------------------------------------------

    /**
     * Rose des vents à 16 secteurs (22,5° chacun).
     *
     * 16 et pas 8 : à 8 secteurs, tout ce qui est entre 337,5° et 22,5°
     * s'appelle « Nord », soit 45° d'incertitude — trop grossier pour lire
     * un cap. 16 secteurs ramènent l'erreur d'arrondi à ±11,25°, ce qui
     * correspond à la précision d'une boussole à main.
     */
    public static final String[] CARDINALS = {
            "Nord", "Nord-Nord-Est", "Nord-Est", "Est-Nord-Est",
            "Est", "Est-Sud-Est", "Sud-Est", "Sud-Sud-Est",
            "Sud", "Sud-Sud-Ouest", "Sud-Ouest", "Ouest-Sud-Ouest",
            "Ouest", "Ouest-Nord-Ouest", "Nord-Ouest", "Nord-Nord-Ouest"
    };

    /** Index dans {@link #CARDINALS}, ou -1 si l'angle est absent. */
    public static int cardinalIndex(float deg) {
        float n = normalize(deg);
        if (Float.isNaN(n)) return -1;
        // & 15 fait le tour : 360/22,5 = 16 → 16 & 15 = 0 → Nord.
        return Math.round(n / 22.5f) & 15;
    }

    /** Nom du secteur, ou "—" si absent. */
    public static String cardinal(float deg) {
        int i = cardinalIndex(deg);
        return i < 0 ? "—" : CARDINALS[i];
    }

    /**
     * Cap en degrés, TOUJOURS sur trois chiffres : "007°", "045°", "359°".
     *
     * Trois chiffres et pas un : c'est la convention des instruments
     * (girouettes, compas, cap au compas d'un bateau). "7°" se lit mal et
     * ne s'aligne pas dans une colonne.
     *
     * 359,7° s'affiche "000°" et non "360°" : un cap est un point du
     * cercle, pas une mesure de longueur ; 360 n'existe pas, c'est 0.
     */
    public static String formatDegrees(float deg) {
        float n = normalize(deg);
        if (Float.isNaN(n)) return "—";
        int d = Math.round(n) % 360;   // 359,7 -> 360 -> 0
        return String.format(Locale.FRANCE, "%03d°", d);
    }

    /** "Est-Nord-Est (067°)" — ce qu'affiche la ligne de texte. */
    public static String describe(float deg) {
        int i = cardinalIndex(deg);
        if (i < 0) return "—";
        return CARDINALS[i] + " (" + formatDegrees(deg) + ")";
    }

    /**
     * Abrège un nom de secteur trop long pour un cadran.
     *
     * « Ouest-Sud-Ouest » → « O-S-O », « Nord-Nord-Est » → « N-N-E ».
     * Les noms sans trait d'union (Nord, Est, Sud, Ouest) traversent
     * inchangés : rien à abréger.
     *
     * Vit ici et non dans CompassView parce que c'est de la logique pure,
     * testable sans Android — le dessin n'a aucune raison de la connaître.
     */
    public static String abbreviate(String card) {
        if (card == null || card.indexOf('-') < 0) return card;
        StringBuilder sb = new StringBuilder(8);
        char prev = 0;
        for (int i = 0; i < card.length(); i++) {
            char ch = card.charAt(i);
            if (ch == '-') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') sb.append('-');
            } else if (prev == '-' || prev == 0) {
                sb.append(ch);      // première lettre d'un mot
            }
            prev = ch;
        }
        return sb.toString();
    }

    /**
     * Déclinaison signée : "+1,5°", "−12,3°", "0,0°".
     *
     * Le signe est TOUJOURS écrit, y compris positif : c'est le seul moyen
     * de distinguer « +0,4° » (négligeable) d'une absence de valeur. Un
     * « 1,5° » sans signe laisse croire à une magnitude.
     */
    public static String formatDeclination(float deg) {
        if (Float.isNaN(deg)) return "—";
        if (deg >= 0f) return String.format(Locale.FRANCE, "+%.1f°", deg);
        // Le signe moins typographique se confond avec un tiret dans
        // certaines polices : on garde le moins ASCII, plus sûr en rendu.
        return String.format(Locale.FRANCE, "-%.1f°", -deg);
    }
}
