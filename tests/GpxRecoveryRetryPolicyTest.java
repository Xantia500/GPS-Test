package com.example.gpstest;

/** Tests JVM de la classification des reprises automatiques et des cas à intervention. */
public final class GpxRecoveryRetryPolicyTest {
    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        section("Permissions SAF : attendre une action utilisateur");
        expect(GpxRecoveryRetryPolicy.Decision.WAIT_FOR_USER,
                "PENDING_PERMISSION", "", "", "workflow permission");
        expect(GpxRecoveryRetryPolicy.Decision.WAIT_FOR_USER,
                "PENDING_PUBLICATION", "NO_PERMISSION", "", "permission révoquée pendant la publication");

        section("Erreurs transitoires : retries bornés");
        expect(GpxRecoveryRetryPolicy.Decision.RETRY_AUTOMATICALLY,
                "PENDING_PUBLICATION", "WRITE_FAILED", "", "écriture SAF transitoire");
        expect(GpxRecoveryRetryPolicy.Decision.RETRY_AUTOMATICALLY,
                "JOURNAL_NOT_ACKNOWLEDGED", "", "", "journal à acquitter après vérification");
        expect(GpxRecoveryRetryPolicy.Decision.RETRY_AUTOMATICALLY,
                "RECOVERY_FAILED", "", "IO_ERROR", "erreur I/O de récupération");
        ok(GpxRecoveryRetryPolicy.MAX_AUTOMATIC_ATTEMPTS == 5,
                "la série de tentatives automatiques est limitée à cinq exécutions");
        ok(GpxRecoveryRetryPolicy.shouldRetry(true, 0), "première nouvelle tentative autorisée");
        ok(GpxRecoveryRetryPolicy.shouldRetry(true, 3), "dernière tentative de la série autorisée");
        ok(!GpxRecoveryRetryPolicy.shouldRetry(true, 4), "aucune sixième tentative automatique");
        ok(!GpxRecoveryRetryPolicy.shouldRetry(false, 0), "une erreur non transitoire ne déclenche pas de retry");
        ok(!GpxRecoveryRetryPolicy.shouldRetry(true, -1), "un compteur d'essai invalide est rejeté");

        section("Corruption, conflit ou source incohérente : intervention");
        expect(GpxRecoveryRetryPolicy.Decision.NEEDS_INTERVENTION,
                "PUBLICATION_CONFLICT", "", "", "conflit de publication");
        expect(GpxRecoveryRetryPolicy.Decision.NEEDS_INTERVENTION,
                "PENDING_PUBLICATION", "AMBIGUOUS_DOCUMENTS", "", "documents ambigus");
        expect(GpxRecoveryRetryPolicy.Decision.NEEDS_INTERVENTION,
                "PENDING_PUBLICATION", "SOURCE_CHANGED", "", "source modifiée");
        expect(GpxRecoveryRetryPolicy.Decision.NEEDS_INTERVENTION,
                "PENDING_PUBLICATION", "INVALID_SOURCE", "", "source invalide");
        expect(GpxRecoveryRetryPolicy.Decision.NEEDS_INTERVENTION,
                "PENDING_PUBLICATION", "REGISTRY_ERROR", "", "registre SAF illisible");
        expect(GpxRecoveryRetryPolicy.Decision.NEEDS_INTERVENTION,
                "RECOVERY_FAILED", "", "INVALID_PREFIX", "préfixe GPX invalide");
        expect(GpxRecoveryRetryPolicy.Decision.NEEDS_INTERVENTION,
                "UNKNOWN_FUTURE_STATUS", "", "", "état futur inconnu traité de façon conservatrice");

        section("États terminés");
        expect(GpxRecoveryRetryPolicy.Decision.COMPLETE,
                "PUBLISHED", "", "", "publication vérifiée et acquittée");
        expect(GpxRecoveryRetryPolicy.Decision.COMPLETE,
                "LOCAL_ONLY", "", "", "copie privée vérifiée");

        System.out.println("\n---------------------------------------------");
        System.out.println("RESULTAT : " + pass + " reussis, " + fail + " echoues");
        if (fail > 0) System.exit(1);
    }

    private static void expect(GpxRecoveryRetryPolicy.Decision expected,
                               String workflow, String publisher, String recovery,
                               String label) {
        ok(GpxRecoveryRetryPolicy.classify(workflow, publisher, recovery) == expected,
                label + " => " + expected);
    }
    private static void section(String title) { System.out.println("\n=== " + title + " ==="); }
    private static void ok(boolean condition, String label) {
        if (condition) pass++;
        else { fail++; System.out.println("  ECHEC " + label); }
    }
}
