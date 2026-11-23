package com.example.gpstest;

public final class OfflineMapDownloadContractTest {
    private static int ok, ko;
    private static void check(String label, boolean value) {
        if (value) { ok++; System.out.println("  OK   " + label); }
        else { ko++; System.out.println("  FAIL " + label); }
    }
    public static void main(String[] args) {
        String a = OfflineMapDownloadContract.uniqueWorkName(123456L);
        String b = OfflineMapDownloadContract.uniqueWorkName(123457L);
        check("nom de tâche unique déterministe pour un téléchargement", a.equals("offline-map-install-123456"));
        check("deux téléchargements distincts ne partagent pas leur travail", !a.equals(b));
        check("clés de données de région, chemin et version distinctes",
                !OfflineMapDownloadContract.KEY_REGION.equals(OfflineMapDownloadContract.KEY_PATH)
                        && !OfflineMapDownloadContract.KEY_PATH.equals(OfflineMapDownloadContract.KEY_VERSION));
        check("dossier de staging identique pour Activity et Worker",
                OfflineMapDownloadContract.STAGING_DIR.equals("offline-map-downloads"));
        check("identifiant négatif ne peut produire un nom de tâche valide",
                OfflineMapDownloadContract.uniqueWorkName(-1).endsWith("-1"));
        check("révision des cartes augmente", OfflineMapDownloadContract.nextMapsRevision(8L) == 9L);
        check("révision négative repart à une valeur valide", OfflineMapDownloadContract.nextMapsRevision(-1L) == 1L);
        check("révision évite le débordement signé", OfflineMapDownloadContract.nextMapsRevision(Long.MAX_VALUE) == 1L);
        check("action de notification des cartes est spécifique à l'application",
                OfflineMapDownloadContract.ACTION_MAPS_UPDATED.startsWith("com.example.gpstest."));
        check("clé d'installation distincte de l'identifiant de téléchargement",
                !OfflineMapDownloadContract.KEY_INSTALLING.equals(OfflineMapDownloadContract.KEY_ID));
        System.out.println("RESULTAT : " + ok + " réussis, " + ko + " échoués");
        if (ko != 0) throw new AssertionError(ko + " test(s) échoué(s)");
    }
}
