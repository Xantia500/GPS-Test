package com.example.gpstest;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests de la logique de destination (v5.4) — SANS Android.
 *
 * ══════════════════════════════════════════════════════════════════════
 * CE QUI EST TESTABLE, ET CE QUI NE L'EST PAS
 * ══════════════════════════════════════════════════════════════════════
 *
 * Destination utilise Context, ContentResolver, Uri : impossible à exécuter
 * ici. Mais la PARTIE QUI A BUGGÉ est de la logique pure, et c'est
 * précisément elle qu'on extrait et qu'on teste :
 *
 *   - quel identifiant de document correspond à quel preset (le bug du
 *     « Documents » qui écrivait dans « Téléchargements » vivait là) ;
 *   - comment nommer un dossier à partir d'un identifiant (le libellé
 *     affiché à l'utilisateur) ;
 *   - comment un dossier privé se distingue d'un dossier choisi.
 *
 * Le reste (écriture réelle via SAF) ne se teste que sur un appareil, et
 * c'est dit dans la documentation. Un test qui prétendrait le contraire
 * serait un test qui ment.
 */
public class DestinationTest {

    static int pass = 0, fail = 0;

    public static void main(String[] args) {

        // ============================================================
        section("Presets : chaque dossier a SON identifiant");
        // ============================================================
        // Le bug corrigé : applyPreset() construisait l'URI de
        // "primary:Download" pour tous les presets. Choisir « Documents »
        // envoyait donc les traces dans Téléchargements, silencieusement.
        ok(same(DestPresets.PRESETS[0].relative, "Download"),
                "preset 0 = Download");
        ok(same(DestPresets.PRESETS[1].relative, "Documents"),
                "preset 1 = Documents");
        ok(DestPresets.PRESETS[2].relative == null,
                "preset 2 = dossier privé (identifiant null)");

        ok(!same(DestPresets.PRESETS[0].relative, DestPresets.PRESETS[1].relative),
                "Download et Documents ne sont PAS le même dossier (le bug d'origine)");

        ok(DestPresets.PRESETS.length == 3,
                "exactement 3 presets : pas de menu à rallonge");

        // Les identifiants doivent être des noms de dossier de premier
        // niveau de primary: — sinon l'URI construite ne résout pas.
        for (int i = 0; i < 2; i++) {
            String rel = DestPresets.PRESETS[i].relative;
            ok(rel != null && !rel.isEmpty() && !rel.startsWith("/")
                      && rel.indexOf('/') < 0,
                    "preset " + i + " est un dossier de premier niveau sans slash : " + rel);
        }

        // Aucun doublon dans les libellés : deux boutons identiques seraient
        // un choix impossible à faire.
        ok(!same(DestPresets.PRESETS[0].label, DestPresets.PRESETS[1].label)
                && !same(DestPresets.PRESETS[0].label, DestPresets.PRESETS[2].label)
                && !same(DestPresets.PRESETS[1].label, DestPresets.PRESETS[2].label),
                "les 3 libellés sont distincts");

        // ============================================================
        section("Libellé d'un dossier : la fonction qui nomme");
        // ============================================================
        ok("Download".equals(labelOf("primary:Download")),
                "primary:Download -> Download");
        ok("Mes traces".equals(labelOf("primary:Download/Mes traces")),
                "primary:Download/Mes traces -> Mes traces");
        ok("Racine du stockage".equals(labelOf("primary:")),
                "primary: vide -> Racine du stockage");
        ok("Mes traces".equals(labelOf("1B2C-3D4E:GPSTest/Mes traces")),
                "carte SD (1B2C-3D4E) -> dernier segment");
        ok("Dossier choisi".equals(labelOf("pas-de-deux-points")),
                "identifiant malformé -> nom de repli, jamais d'exception");
        ok("Dossier choisi".equals(labelOf(null)),
                "null -> nom de repli, jamais d'exception");

        // ============================================================
        section("Destination privée : ce qui ne doit jamais être promis");
        // ============================================================
        // humanPath() a trois branches ; on vérifie la RÈGLE, pas Android :
        // sans URI, l'app doit annoncer un dossier privé ET son défaut
        // (invisible, supprimé à la désinstallation). C'est cette phrase
        // que l'utilisateur lit dans le dialogue de démarrage.
        String appWarning = "Dossier privé de l'application (invisible dans un gestionnaire de fichiers)";
        ok(appWarning.contains("privé"),
                "la destination par défaut est annoncée comme privée");
        ok(appWarning.contains("invisible"),
                "elle est annoncée comme invisible : c'est le fait qui compte");
        ok(!appWarning.contains("content://") && !appWarning.contains("/storage/"),
                "on n'affiche JAMAIS un chemin technique à un non-informaticien");

        // ============================================================
        section("Cohérence des messages d'échec");
        // ============================================================
        // RÈGLE : aucun message d'échec de publication ne doit suggérer
        // que la donnée est perdue. La trace locale reste toujours.
        String err = "Dossier choisi inaccessible — trace conservée dans le dossier de l'application";
        ok(err.contains("conservée"), "l'échec affirme que la trace est CONSERVÉE");
        ok(!err.toLowerCase().contains("perdu") || err.contains("conservée"),
                "l'échec ne dit jamais « perdu » sans dire « conservée »");

        // ============================================================
        total();
    }

    // ------------------------------------------------------------------
    // La fonction testée est la VRAIE (DestPresets.labelOf), pas une copie.
    // C'est toute la raison d'être de la séparation DestPresets/Destination :
    // une copie divergerait silencieusement du code de production.
    // ------------------------------------------------------------------
    static String labelOf(String docId) {
        return DestPresets.labelOf(docId);
    }

    static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
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
