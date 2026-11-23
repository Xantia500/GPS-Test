package com.example.gpstest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * BANDEAU D'ÉTAT PERMANENT — v5.10
 *
 * ══════════════════════════════════════════════════════════════════════
 * CE QUE L'UTILISATEUR DEMANDAIT
 * ══════════════════════════════════════════════════════════════════════
 *
 *   « en haut en affichage permanent le nombre de satellites in fix /
 *     satellites in View, à côté le type de fix (3D, 2D, No fix), l'heure
 *     et la charge de la batterie : 18:01:07 / 3D fix / 4/10 satellites /
 *     57% (en charge/no charge) — ou bien quelque chose de plus élégant »
 *
 * ══════════════════════════════════════════════════════════════════════
 * CE QUI EST PLUS ÉLÉGANT, ET POURQUOI
 * ══════════════════════════════════════════════════════════════════════
 *
 * Une ligne unique de texte brut oblige à lire en entier pour trouver
 * l'information. Quatre éléments courts, alignés et de largeur stable se
 * balaient d'un coup d'œil :
 *
 *   18:01:07   3D fix   4/10 sats   57% ⚡
 *
 * Trois décisions :
 *
 *  1. LARGEUR STABLE. Les quatre champs ont une largeur minimale fixe
 *     (monospace tabulaire). Sans cela, la ligne « saute » à chaque
 *     changement de chiffre et le regard doit la retrouver.
 *
 *  2. COULEUR DE FIX, PAS DE COULEUR PARTOUT. Seul l'état du fix change de
 *     couleur : vert = 3D déclaré par GSA frais, ambre = 2D ou dimension
 *     inconnue, rouge = aucun fix. Le reste reste neutre.
 *     Une ligne multicolore est une ligne qu'on n'apprend pas.
 *
 *  3. BATTERIE EN DERNIER. C'est l'information la moins volatile et la
 *     moins critique. Elle a sa place, pas la vedette.
 *
 * ══════════════════════════════════════════════════════════════════════
 * POURQUOI UNE CLASSE ET PAS UN BLOC DE CODE DANS L'ACTIVITY
 * ══════════════════════════════════════════════════════════════════════
 *
 * La mise en forme du texte est PURE : elle prend des nombres, elle rend
 * une chaîne. Elle est donc testable sans appareil. Seule la lecture de la
 * batterie touche à Android — et elle est isolée dans une méthode.
 *
 * C'est ce qui permet de vérifier « 4 sur 10 satellites » ou « 57 % en
 * charge » par un test, plutôt que par un coup d'œil sur un téléphone.
 */
public final class StatusHud {

    private StatusHud() { }

    // ------------------------------------------------------------------
    // Textes purs
    // ------------------------------------------------------------------

    /**
     * Libellé du fix. Le type GSA est prioritaire lorsqu'il est récent.
     * Location.hasAltitude() prouve seulement que le champ altitude existe ;
     * sans GSA frais, on n'affirme donc pas formellement « 3D ».
     */
    public static String fixLabel(int receiverFixType, boolean hasAltitude, boolean hasAnyFix) {
        return FixStatePresentation.fixLabel(receiverFixType, hasAltitude, hasAnyFix);
    }

    /** Compatibilité : sans information GSA, ne prétend pas connaître la dimension. */
    public static String fixLabel(boolean hasAltitude, boolean hasAnyFix) {
        return fixLabel(0, hasAltitude, hasAnyFix);
    }

    /** « 4/10 sats » — utilisé / en vue. */
    public static String satsLabel(int used, int inView) {
        if (inView <= 0 && used <= 0) return "\u2014";
        // Le nombre de sats UTILISÉS ne peut pas dépasser celui en vue.
        // S'il le dépasse (GnssStatus en retard d'un cycle), on affiche la
        // valeur cohérente la plus grande : mieux vaut un chiffre cohérent
        // qu'un « 12/10 » qui ferait douter de toute l'application.
        int view = Math.max(inView, used);
        return used + "/" + view + " sats";
    }

    /** « 57% » ou « 57% ⚡ » si en charge ; « — » si inconnu. */
    public static String batteryLabel(int percent, boolean charging) {
        if (percent < 0) return "\u2014";
        int p = Math.max(0, Math.min(100, percent));
        return charging ? (p + "% \u26A1") : (p + "%");
    }

    /** Heure locale « 18:01:07 ». */
    public static String clockLabel(long wallMs) {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date(wallMs));
    }

    /**
     * Largeur minimale d'affichage, en caractères, pour chaque champ.
     *
     * Elles sont fixes POUR QUE LA LIGNE NE BOUGE PAS quand les valeurs
     * changent : sans cela, le passage de « 9/10 sats » à « 10/12 sats »
     * décale tout ce qui est à droite, et le regard doit rescanner la ligne
     * à chaque seconde.
     */
    public static int clockMinChars()   { return 8; }   // 18:01:07
    public static int fixMinChars()     { return 6; }   // "3D fix"
    public static int satsMinChars()    { return 10; }  // "10/12 sats"
    public static int batteryMinChars() { return 7; }   // "100% ⚡"

    // ------------------------------------------------------------------
    // Couleur du fix (ressource, pour suivre le thème)
    // ------------------------------------------------------------------

    /**
     * Ressource de couleur à appliquer au champ « fix ».
     *
     * On ne renvoie PAS une couleur, mais un identifiant : les couleurs
     * vivent dans colors.xml et colors-night.xml, et changer le thème doit
     * changer le bandeau sans une ligne de code.
     */
    public static int fixColorRes(int receiverFixType, boolean hasAnyFix, boolean hasAltitude) {
        return FixStatePresentation.fixColorRes(receiverFixType, hasAnyFix, hasAltitude);
    }

    public static int fixColorRes(boolean hasAnyFix, boolean hasAltitude) {
        return fixColorRes(0, hasAnyFix, hasAltitude);
    }

    // ------------------------------------------------------------------
    // Batterie : seule partie liée à Android
    // ------------------------------------------------------------------

    /** Valeur lue une fois. -1 = inconnu. */
    public static final class Battery {
        public final int percent;
        public final boolean charging;

        Battery(int percent, boolean charging) {
            this.percent = percent;
            this.charging = charging;
        }

        public String label() { return batteryLabel(percent, charging); }
    }

    /**
     * Lit l'état de la batterie.
     *
     * ACTION_BATTERY_CHANGED est une diffusion « collante » : la lire
     * renvoie immédiatement le dernier état connu, sans attendre un
     * changement. C'est la bonne méthode pour un affichage permanent —
     * enregistrer un BroadcastReceiver serait utile seulement pour être
     * NOTIFIÉ des changements, ce qui n'est pas nécessaire ici : le bandeau
     * se rafraîchit déjà une fois par seconde.
     *
     * Ne lève jamais, même si le système ne répond pas (certains appareils
     * modifiés).
     */
    public static Battery readBattery(Context ctx) {
        try {
            Intent i = ctx.getApplicationContext().registerReceiver(
                    null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return new Battery(-1, false);
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (level < 0 || scale <= 0) return new Battery(-1, false);
            int pct = Math.round(level * 100f / scale);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            return new Battery(pct, charging);
        } catch (Exception e) {
            return new Battery(-1, false);
        }
    }

    /** Instant monotone, pour que l'appelant puisse espacer les relectures. */
    public static long nowElapsed() { return SystemClock.elapsedRealtime(); }
}
