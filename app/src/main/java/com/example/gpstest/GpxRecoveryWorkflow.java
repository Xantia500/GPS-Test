package com.example.gpstest;

import android.content.Context;
import android.net.Uri;

import java.io.File;

/** Coordonne récupération locale, publication SAF vérifiée et acquittement du registre. */
public final class GpxRecoveryWorkflow {
    private static final Object LOCK = new Object();

    private GpxRecoveryWorkflow() { }

    public enum Status { PUBLISHED, LOCAL_ONLY, PENDING_PERMISSION, PENDING_PUBLICATION,
        RECOVERY_FAILED, JOURNAL_NOT_ACKNOWLEDGED }

    public static final class Result {
        public final Status status;
        public final String recoveredFileName;
        public final String publishedDisplayName;
        public final String message;
        public final GpxRecovery.Result recovery;
        public final GpxRecoveryPublisher.Result publication;
        private Result(Status status, String recovered, String published, String message,
                       GpxRecovery.Result recovery, GpxRecoveryPublisher.Result publication) {
            this.status = status;
            recoveredFileName = recovered == null ? "" : recovered;
            publishedDisplayName = published == null ? "" : published;
            this.message = message == null ? "" : message;
            this.recovery = recovery;
            this.publication = publication;
        }
        public boolean isRecoveredLocally() {
            return status != Status.RECOVERY_FAILED && recovery != null && recovery.isSuccess();
        }
        /** Le document externe a passé la relecture longueur + SHA-256. */
        public boolean isPublishedExternally() {
            return publication != null && publication.isSuccess();
        }
        /** La publication est faite, mais l'acquittement du journal reste à terminer. */
        public boolean isJournalAcknowledgementPending() {
            return status == Status.JOURNAL_NOT_ACKNOWLEDGED
                    || (status == Status.PENDING_PUBLICATION && isPublishedExternally());
        }
        /** Une permission ou le transfert SAF lui-même reste nécessaire. */
        public boolean isExternalPublicationPending() {
            return (status == Status.PENDING_PERMISSION || status == Status.PENDING_PUBLICATION)
                    && !isPublishedExternally();
        }
        /** Copie privée sûre, sans publication externe aboutie ou nécessaire. */
        public boolean isRecoveredLocallyOnly() {
            return status == Status.LOCAL_ONLY
                    || (status == Status.JOURNAL_NOT_ACKNOWLEDGED && !isPublishedExternally());
        }
        public boolean isFinalized() { return status == Status.PUBLISHED || status == Status.LOCAL_ONLY; }
    }

    /**
     * À appeler sur un worker. Le verrou process-wide sérialise les appels venant
     * de MainActivity et TrackingService ; aucune écriture SAF ne bloque ioFile.
     */
    public static Result recoverAndPublish(Context context, File directory, SessionJournal expected) {
        if (context == null || expected == null) {
            return failed("Contexte ou snapshot de récupération absent.", null);
        }
        synchronized (LOCK) {
            GpxRecovery.Result recovery = GpxRecovery.recover(directory,
                    expected.file, expected.safeLengthBytes);
            if (!recovery.isSuccess()) {
                return failed(recovery.message, recovery);
            }

            File recoveredFile = new File(directory, recovery.recoveredFileName);
            Uri tree = Destination.treeUri(context);
            RecoveryPublicationStore store = new RecoveryPublicationStore(context);
            String publicationId = GpxRecoveryPublisher.publicationId(expected,
                    recovery.recoveredFileName);

            if (tree == null) {
                // Le dossier privé est le choix explicite de l'utilisateur : la
                // copie locale vérifiée est la destination finale suffisante.
                if (!store.removeAny(publicationId)) {
                    return result(Status.PENDING_PUBLICATION, recovery, "",
                            "Copie récupérée localement ; un ancien état de publication reste à résoudre.", null);
                }
                if (!SessionStore.clearRecoveryIfUnchanged(context, expected)) {
                    return result(Status.JOURNAL_NOT_ACKNOWLEDGED, recovery, "",
                            "Copie locale récupérée ; le journal a changé et reste conservé pour vérification.", null);
                }
                return result(Status.LOCAL_ONLY, recovery, "",
                        "Trace récupérée dans le stockage privé de GPS-Test. Le fichier original est conservé.", null);
            }

            SafRecoveryAccess access = new SafRecoveryAccess(context);
            if (!access.hasReadWriteAccess(tree.toString())) {
                return result(Status.PENDING_PERMISSION, recovery, "",
                        "Copie récupérée localement ; publication externe en attente car l'accès SAF en lecture/écriture n'est pas disponible.", null);
            }

            GpxRecoveryPublisher.Result published = GpxRecoveryPublisher.publish(
                    recoveredFile, expected, tree.toString(), access, store);
            if (!published.isSuccess()) {
                return result(Status.PENDING_PUBLICATION, recovery, "",
                        "Copie récupérée localement ; publication SAF non acquittée : "
                                + published.message, published);
            }

            // Supprimer d'abord l'état VERIFIED : si le processus meurt avant
            // l'acquittement du registre de récupération, le retry retrouve le
            // document par son nom déterministe, le relit et le vérifie à nouveau.
            if (!store.removeVerified(published.publicationId)) {
                return result(Status.PENDING_PUBLICATION, recovery, published.displayName,
                        "Le document SAF est vérifié, mais l'état transactionnel ne peut pas être acquitté ; nouvelle vérification au prochain démarrage.", published);
            }
            if (!SessionStore.clearRecoveryIfUnchanged(context, expected)) {
                return result(Status.JOURNAL_NOT_ACKNOWLEDGED, recovery, published.displayName,
                        "Document SAF publié et vérifié ; le journal de récupération a évolué et reste conservé.", published);
            }
            return result(Status.PUBLISHED, recovery, published.displayName,
                    "Trace récupérée et publiée dans le dossier choisi. Le document SAF a été relu et vérifié.", published);
        }
    }

    private static Result failed(String message, GpxRecovery.Result recovery) {
        return new Result(Status.RECOVERY_FAILED, "", "", message, recovery, null);
    }
    private static Result result(Status status, GpxRecovery.Result recovery,
                                 String displayName, String message,
                                 GpxRecoveryPublisher.Result publication) {
        return new Result(status, recovery == null ? "" : recovery.recoveredFileName,
                displayName, message, recovery, publication);
    }
}
