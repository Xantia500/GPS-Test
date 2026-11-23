package com.example.gpstest;

/**
 * Tests de la v5 : politique d'arrière-plan et segmentation de trace.
 *
 * Ces tests ne compilent PAS Android : BackgroundPolicy est 100 % pur.
 * C'est tout l'intérêt d'avoir séparé la décision (BackgroundPolicy) du
 * stockage (Prefs) et des effets (TrackingService).
 *
 * Exécution :
 *   javac -encoding UTF-8 -d out app/src/main/java/com/example/gpstest/*.java tests/*.java
 *   java -cp out com.example.gpstest.BackgroundPolicyTest
 */
public class BackgroundPolicyTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {

        // ============================================================
        section("Robustesse de l'enum (persistance)");
        // ============================================================
        ok(BackgroundPolicy.Policy.fromId(0) == BackgroundPolicy.Policy.CONTINUE,
                "id 0 -> CONTINUE");
        ok(BackgroundPolicy.Policy.fromId(1) == BackgroundPolicy.Policy.KEEP_SCREEN_ON,
                "id 1 -> KEEP_SCREEN_ON");
        ok(BackgroundPolicy.Policy.fromId(2) == BackgroundPolicy.Policy.SUSPEND_ON_SCREEN_OFF,
                "id 2 -> SUSPEND_ON_SCREEN_OFF");
        ok(BackgroundPolicy.Policy.fromId(99) == BackgroundPolicy.Policy.CONTINUE,
                "id inconnu -> CONTINUE (jamais d'exception)");
        ok(BackgroundPolicy.Policy.fromId(-7) == BackgroundPolicy.Policy.CONTINUE,
                "id négatif -> CONTINUE");
        // Les ids doivent être distincts : sinon la persistance est ambiguë.
        ok(BackgroundPolicy.Policy.CONTINUE.id
                        != BackgroundPolicy.Policy.KEEP_SCREEN_ON.id
                        && BackgroundPolicy.Policy.KEEP_SCREEN_ON.id
                        != BackgroundPolicy.Policy.SUSPEND_ON_SCREEN_OFF.id
                        && BackgroundPolicy.Policy.CONTINUE.id
                        != BackgroundPolicy.Policy.SUSPEND_ON_SCREEN_OFF.id,
                "les 3 ids sont distincts");

        // ============================================================
        section("wantsKeepScreenOn : un seul mode le demande");
        // ============================================================
        ok(!BackgroundPolicy.wantsKeepScreenOn(BackgroundPolicy.Policy.CONTINUE),
                "CONTINUE ne force pas l'écran allumé");
        ok(BackgroundPolicy.wantsKeepScreenOn(BackgroundPolicy.Policy.KEEP_SCREEN_ON),
                "KEEP_SCREEN_ON force l'écran allumé");
        ok(!BackgroundPolicy.wantsKeepScreenOn(BackgroundPolicy.Policy.SUSPEND_ON_SCREEN_OFF),
                "SUSPEND ne force pas l'écran allumé");

        // ============================================================
        section("suspendsOnScreenOff : un seul mode suspend");
        // ============================================================
        ok(!BackgroundPolicy.suspendsOnScreenOff(BackgroundPolicy.Policy.CONTINUE),
                "CONTINUE ne suspend pas");
        ok(!BackgroundPolicy.suspendsOnScreenOff(BackgroundPolicy.Policy.KEEP_SCREEN_ON),
                "KEEP_SCREEN_ON ne suspend pas");
        ok(BackgroundPolicy.suspendsOnScreenOff(BackgroundPolicy.Policy.SUSPEND_ON_SCREEN_OFF),
                "SUSPEND suspend");

        // ============================================================
        section("shouldSuspend : table de vérité complète (3 modes x 2 écrans)");
        // ============================================================
        // Écran allumé : jamais de suspension, quelle que soit la politique.
        for (BackgroundPolicy.Policy p : BackgroundPolicy.Policy.values()) {
            ok(!BackgroundPolicy.shouldSuspend(p, true),
                    "écran allumé, " + p.name() + " -> aucun suspend");
        }
        // Écran éteint : suspension uniquement en mode économie.
        ok(!BackgroundPolicy.shouldSuspend(BackgroundPolicy.Policy.CONTINUE, false),
                "écran éteint, CONTINUE -> on continue (c'est le but)");
        ok(!BackgroundPolicy.shouldSuspend(BackgroundPolicy.Policy.KEEP_SCREEN_ON, false),
                "écran éteint, KEEP_SCREEN_ON -> on continue");
        ok(BackgroundPolicy.shouldSuspend(BackgroundPolicy.Policy.SUSPEND_ON_SCREEN_OFF, false),
                "écran éteint, SUSPEND -> suspend");

        // ============================================================
        section("Idempotence de la décision");
        // ============================================================
        // Appeler shouldSuspend plusieurs fois avec les mêmes entrées doit
        // renvoyer la même chose : le service appelle cette fonction depuis
        // trois sources d'événements différentes, dans un ordre imprévisible.
        for (int i = 0; i < 10; i++) {
            if (!BackgroundPolicy.shouldSuspend(
                    BackgroundPolicy.Policy.SUSPEND_ON_SCREEN_OFF, false)) {
                ok(false, "shouldSuspend non déterministe à l'itération " + i);
                break;
            }
        }
        ok(true, "shouldSuspend : 10 appels identiques, même résultat");

        // ============================================================
        section("Segmentation GPX : nommage des fichiers");
        // ============================================================
        ok(BackgroundPolicy.segmentSuffix(BackgroundPolicy.Policy.CONTINUE, 0) == null,
                "CONTINUE : pas de suffixe (un seul fichier, comme avant)");
        ok(BackgroundPolicy.segmentSuffix(BackgroundPolicy.Policy.KEEP_SCREEN_ON, 3) == null,
                "KEEP_SCREEN_ON : pas de suffixe");
        ok("-p1".equals(BackgroundPolicy.segmentSuffix(
                        BackgroundPolicy.Policy.SUSPEND_ON_SCREEN_OFF, 0)),
                "SUSPEND, premier segment -> -p1");
        ok("-p2".equals(BackgroundPolicy.segmentSuffix(
                        BackgroundPolicy.Policy.SUSPEND_ON_SCREEN_OFF, 1)),
                "SUSPEND, deuxième segment -> -p2");
        ok("-p12".equals(BackgroundPolicy.segmentSuffix(
                        BackgroundPolicy.Policy.SUSPEND_ON_SCREEN_OFF, 11)),
                "SUSPEND, segment 12 -> -p12 (pas de limite arbitraire)");

        // ============================================================
        section("Cohérence des libellés (affichés à l'utilisateur)");
        // ============================================================
        for (BackgroundPolicy.Policy p : BackgroundPolicy.Policy.values()) {
            ok(p.label != null && p.label.length() > 5,
                    p.name() + " : libellé présent");
            ok(p.summary != null && p.summary.length() > 20,
                    p.name() + " : explication présente (pas juste un mot)");
        }
        // L'explication du mode économie DOIT prévenir de la perte de points :
        // c'est le seul mode qui perd des données. Mentir là-dessus serait grave.
        String s = BackgroundPolicy.Policy.SUSPEND_ON_SCREEN_OFF.summary.toUpperCase();
        ok(s.contains("TROU") || s.contains("PAUSE"),
                "SUSPEND : l'explication mentionne explicitement la pause/trou");

        System.out.println("\n---------------------------------------------");
        System.out.println("RESULTAT : " + pass + " reussis, " + fail + " echoues");
        if (fail > 0) System.exit(1);
    }

    static void section(String name) {
        System.out.println("\n=== " + name + " ===");
    }

    static void ok(boolean cond, String label) {
        if (cond) { pass++; System.out.println("  OK   " + label); }
        else { fail++; System.out.println("  ECHEC " + label); }
    }
}
