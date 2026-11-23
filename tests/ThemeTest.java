package com.example.gpstest;

/**
 * Tests de la logique de thème (v5.5) — SANS Android.
 *
 * ══════════════════════════════════════════════════════════════════════
 * CE QUI EST VÉRIFIÉ ICI, ET POURQUOI C'EST CETTE PARTIE-LÀ
 * ══════════════════════════════════════════════════════════════════════
 *
 * ThemeManager contient deux choses de nature très différente :
 *
 *   1. une RÈGLE PURE (quel mode correspond à quel identifiant, comment
 *      interpréter une valeur absente) — testable ici ;
 *   2. des EFFETS Android (updateConfiguration, resources) — non testables
 *      hors appareil.
 *
 * Le bug le plus probable d'un sélecteur de thème n'est pas dans l'effet,
 * il est dans l'INTERPRÉTATION : une préférence absente, corrompue ou
 * écrite par une version précédente. C'est exactement ce que ce fichier
 * couvre.
 *
 * Également vérifié : les identifiants persistés sont STABLES. Les changer
 * ferait perdre son réglage à tout utilisateur existant.
 */
public class ThemeTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {

        // ============================================================
        section("Lecture d'un mode persisté");
        // ============================================================
        ok(ThemeMode.Mode.fromId("system") == ThemeMode.Mode.SYSTEM,
                "\"system\" -> SYSTEM");
        ok(ThemeMode.Mode.fromId("light") == ThemeMode.Mode.LIGHT,
                "\"light\" -> LIGHT");
        ok(ThemeMode.Mode.fromId("dark") == ThemeMode.Mode.DARK,
                "\"dark\" -> DARK");

        ok(ThemeMode.Mode.fromId(null) == ThemeMode.Mode.SYSTEM,
                "null (première installation) -> SYSTEM, pas d'écrasement");
        ok(ThemeMode.Mode.fromId("") == ThemeMode.Mode.SYSTEM,
                "chaîne vide -> SYSTEM");
        ok(ThemeMode.Mode.fromId("DARK") == ThemeMode.Mode.SYSTEM,
                "casse différente -> SYSTEM (comparaison stricte, jamais d'exception)");
        ok(ThemeMode.Mode.fromId("violet") == ThemeMode.Mode.SYSTEM,
                "valeur inconnue -> SYSTEM");
        ok(ThemeMode.Mode.fromId("light; DROP TABLE") == ThemeMode.Mode.SYSTEM,
                "valeur hostile -> SYSTEM, jamais d'exception");

        // ============================================================
        section("Identifiants persistés : stabilité");
        // ============================================================
        // Ces trois chaînes sont écrites sur le disque de l'utilisateur.
        // Les changer dans une future version ferait perdre le réglage de
        // tous ceux qui l'ont déjà choisi. Ce test les verrouille.
        ok("system".equals(ThemeMode.Mode.SYSTEM.id),
                "id SYSTEM figé à \"system\"");
        ok("light".equals(ThemeMode.Mode.LIGHT.id),
                "id LIGHT figé à \"light\"");
        ok("dark".equals(ThemeMode.Mode.DARK.id),
                "id DARK figé à \"dark\"");

        // Les trois ids doivent être distincts, sinon la persistance est
        // ambiguë : deux modes indiscernables au rechargement.
        ok(!ThemeMode.Mode.SYSTEM.id.equals(ThemeMode.Mode.LIGHT.id)
                && !ThemeMode.Mode.LIGHT.id.equals(ThemeMode.Mode.DARK.id)
                && !ThemeMode.Mode.SYSTEM.id.equals(ThemeMode.Mode.DARK.id),
                "les 3 ids sont distincts");

        // Et aucun ne doit contenir d'espace ni d'accent : une valeur
        // persistée peut traverser un export/import de réglages.
        for (ThemeMode.Mode m : ThemeMode.Mode.values()) {
            ok(m.id.equals(m.id.trim())
                    && m.id.indexOf(' ') < 0
                    && m.id.matches("[a-z]+"),
                    "id propre et sans accent : \"" + m.id + "\"");
        }

        // ============================================================
        section("Libellés : présence et distinction");
        // ============================================================
        for (ThemeMode.Mode m : ThemeMode.Mode.values()) {
            ok(m.label != null && !m.label.trim().isEmpty(),
                    "libellé non vide pour " + m.id + " : \"" + m.label + "\"");
        }
        ok(!ThemeMode.Mode.SYSTEM.label.equals(ThemeMode.Mode.LIGHT.label)
                && !ThemeMode.Mode.LIGHT.label.equals(ThemeMode.Mode.DARK.label)
                && !ThemeMode.Mode.SYSTEM.label.equals(ThemeMode.Mode.DARK.label),
                "les 3 libellés sont distincts (trois boutons différents)");

        ok(ThemeMode.Mode.values().length == 3,
                "exactement 3 modes : suivre / clair / sombre");

        ok(ThemeMode.Mode.values()[0] == ThemeMode.Mode.SYSTEM,
                "SYSTEM est le PREMIER : c'est le défaut proposé à l'utilisateur");

        // ============================================================
        section("Presets de destination : identifiants stables");
        // ============================================================
        // Même problème que pour le thème, et même correctif : avant la
        // v5.5, le preset était identifié par son LIBELLÉ, qui contient une
        // apostrophe et est traduisible. Une clé de préférence doit être
        // stable et sans caractère sensible.
        ok("download".equals(DestPresets.PRESETS[0].id),
                "preset 0 : id \"download\"");
        ok("documents".equals(DestPresets.PRESETS[1].id),
                "preset 1 : id \"documents\"");
        ok("app".equals(DestPresets.PRESETS[2].id),
                "preset 2 : id \"app\"");

        for (DestPresets.Preset p : DestPresets.PRESETS) {
            ok(p.id.indexOf(' ') < 0 && p.id.indexOf('\'') < 0
                            && p.id.matches("[a-z]+"),
                    "id de preset propre (ni espace ni apostrophe) : \"" + p.id + "\"");
        }

        ok(DestPresets.byId("download") == DestPresets.PRESETS[0],
                "byId retrouve le preset par son identifiant");
        ok(DestPresets.byId("Téléchargements") == null,
                "byId ne prend PAS le libellé pour un identifiant");
        ok(DestPresets.byId(null) == null,
                "byId(null) -> null, jamais d'exception");
        ok(DestPresets.byId("inexistant") == null,
                "identifiant inconnu (préférence corrompue) -> null, pas de crash");

        total();
    }

    static void section(String name) {
        System.out.println("\n=== " + name + " ===");
    }

    static void ok(boolean cond, String label) {
        if (cond) { pass++; System.out.println("  OK   " + label); }
        else { fail++; System.out.println("  ECHEC " + label); }
    }

    static void total() {
        System.out.println("\n=============================================");
        System.out.println("RESULTAT : " + pass + " reussis, " + fail + " echoues");
        if (fail > 0) System.exit(1);
    }
}
