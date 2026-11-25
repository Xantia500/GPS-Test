package com.example.gpstest;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Orchestrateur sans Android de la publication SAF d'une copie GPX récupérée.
 * Le fournisseur et la persistance sont injectés afin de tester les coupures
 * entre création, écriture, vérification et acquittement.
 */
public final class GpxRecoveryPublisher {
    private static final int BUFFER = 32 * 1024;

    private GpxRecoveryPublisher() { }

    public interface SafAccess {
        boolean hasReadWriteAccess(String treeUri);
        List<Document> findByDisplayName(String treeUri, String displayName) throws IOException;
        String createDocument(String treeUri, String displayName, String mimeType) throws IOException;
        OutputStream openWrite(String documentUri) throws IOException;
        InputStream openRead(String documentUri) throws IOException;
    }

    public interface RegistryStore {
        RecoveryPublicationRegistry read() throws IOException;
        boolean write(RecoveryPublicationRegistry registry);
    }

    public static final class Document {
        public final String uri;
        public final String displayName;
        public Document(String uri, String displayName) {
            this.uri = uri == null ? "" : uri;
            this.displayName = displayName == null ? "" : displayName;
        }
    }

    public enum Status { PUBLISHED, NO_PERMISSION, INVALID_SOURCE, SOURCE_CHANGED,
        REGISTRY_ERROR, DOCUMENT_LOOKUP_FAILED, AMBIGUOUS_DOCUMENTS, CREATE_FAILED,
        WRITE_FAILED, VERIFY_FAILED }

    public static final class Result {
        public final Status status;
        public final String publicationId;
        public final String documentUri;
        public final String displayName;
        public final long bytes;
        public final String sha256;
        public final String message;
        private Result(Status status, String id, String uri, String name, long bytes,
                       String hash, String message) {
            this.status = status; publicationId = id == null ? "" : id;
            documentUri = uri == null ? "" : uri; displayName = name == null ? "" : name;
            this.bytes = bytes; sha256 = hash == null ? "" : hash;
            this.message = message == null ? "" : message;
        }
        public boolean isSuccess() { return status == Status.PUBLISHED; }
    }

    /**
     * Publie un recovered GPX. Un nom stable lié à session+génération permet
     * de retrouver un document créé juste avant un crash, même si l'URI n'a
     * pas encore pu être commitée dans le registre.
     */
    public static Result publish(File source, SessionJournal identity, String treeUri,
                                 SafAccess access, RegistryStore store) {
        if (source == null || identity == null || access == null || store == null
                || !source.isFile() || !source.canRead() || source.length() <= 0L
                || !safeLeaf(source.getName()) || !contentUri(treeUri)
                || identity.sessionId == null || identity.sessionId.isEmpty()
                || identity.segmentGeneration < 0L || !safeLeaf(identity.file)) {
            return fail(Status.INVALID_SOURCE, "Source locale, identité ou destination invalide.");
        }
        final long sourceLength = source.length();
        final String sourceHash;
        try { sourceHash = sha256(source); }
        catch (IOException e) { return fail(Status.INVALID_SOURCE, "Lecture de la copie locale impossible."); }
        if (!access.hasReadWriteAccess(treeUri))
            return fail(Status.NO_PERMISSION, "Accès SAF en lecture et écriture indisponible.");

        String id = publicationId(identity, source.getName());
        String displayName = displayName(source.getName(), id);
        final RecoveryPublicationRegistry registry;
        try { registry = store.read(); }
        catch (IOException error) { return fail(Status.REGISTRY_ERROR, "Journal de publication illisible ; il est conservé."); }
        if (registry == null) return fail(Status.REGISTRY_ERROR, "Journal de publication corrompu ; il est conservé.");

        RecoveryPublicationRegistry.Entry entry = registry.find(id);
        if (entry == null) {
            entry = RecoveryPublicationRegistry.Entry.prepared(id, identity.sessionId,
                    identity.segmentGeneration, identity.file, source.getName(), sourceLength,
                    sourceHash, treeUri, displayName);
            if (entry == null || !registry.upsert(entry) || !store.write(registry))
                return fail(Status.REGISTRY_ERROR, "Impossible de préparer durablement la publication SAF.");
        } else {
            if (!entry.sessionId.equals(identity.sessionId)
                    || entry.segmentGeneration != identity.segmentGeneration
                    || !entry.sourceName.equals(identity.file)
                    || !entry.recoveredName.equals(source.getName())
                    || entry.sourceLength != sourceLength || !entry.sha256.equals(sourceHash)) {
                return fail(Status.SOURCE_CHANGED, "La copie locale ne correspond plus à l'intention de publication.");
            }
            if (!entry.treeUri.equals(treeUri)) {
                entry = entry.retarget(treeUri);
                if (entry == null || !registry.upsert(entry) || !store.write(registry))
                    return fail(Status.REGISTRY_ERROR, "Impossible de changer la destination SAF en attente.");
            }
        }

        String documentUri = entry.documentUri;
        if (documentUri.isEmpty()) {
            final List<Document> matches;
            try {
                List<Document> found = access.findByDisplayName(treeUri, entry.displayName);
                matches = found == null ? new ArrayList<>() : found;
            } catch (IOException error) {
                return fail(Status.DOCUMENT_LOOKUP_FAILED,
                        "Impossible d'interroger le dossier SAF ; aucune création en double n'est tentée.");
            }
            if (matches.size() > 1) {
                String matching = null;
                int exact = 0;
                for (Document candidate : matches) {
                    if (candidate == null || candidate.uri.isEmpty()) continue;
                    try {
                        if (matchesSource(candidate.uri, sourceLength, sourceHash, access)) {
                            matching = candidate.uri; exact++;
                        }
                    } catch (IOException ignored) { }
                }
                if (exact == 1) documentUri = matching;
                else if (exact > 1) documentUri = matching;
                else return fail(Status.AMBIGUOUS_DOCUMENTS,
                        "Plusieurs documents portent l'identifiant de publication ; aucun n'a été écrasé.");
            } else if (matches.size() == 1) {
                Document found = matches.get(0);
                if (found == null || found.uri.isEmpty())
                    return fail(Status.DOCUMENT_LOOKUP_FAILED, "Le fournisseur SAF a retourné un document sans URI.");
                // PREPARED signifie que l'URI n'a jamais été commitée dans
                // notre registre. Un document homonyme peut être l'artefact
                // vide d'un crash juste après createDocument, mais aussi un
                // fichier préexistant de l'utilisateur. Sans URI mémorisée,
                // on ne peut pas prouver sa propriété : on l'adopte seulement
                // si son contenu complet correspond déjà à notre source.
                // Un document partiel dont l'URI est déjà enregistrée passe
                // par la branche documentUri non vide et reste réparable.
                final boolean exactMatch;
                try { exactMatch = matchesSource(found.uri, sourceLength, sourceHash, access); }
                catch (IOException unreadable) {
                    return fail(Status.DOCUMENT_LOOKUP_FAILED,
                            "Un document porte déjà le nom réservé, mais sa lecture est impossible ; il n'est pas écrasé.");
                }
                if (!exactMatch)
                    return fail(Status.AMBIGUOUS_DOCUMENTS,
                            "Un document homonyme existe sans URI transactionnelle enregistrée et son contenu diffère ; il n'est pas écrasé.");
                documentUri = found.uri;
            } else {
                try {
                    documentUri = access.createDocument(treeUri, entry.displayName, "application/gpx+xml");
                } catch (IOException error) {
                    return fail(Status.CREATE_FAILED, "Création du document SAF impossible.");
                }
                if (documentUri == null || !contentUri(documentUri))
                    return fail(Status.CREATE_FAILED, "Le fournisseur SAF n'a pas créé de document exploitable.");
            }
            entry = entry.withDocument(documentUri);
            if (entry == null || !registry.upsert(entry) || !store.write(registry))
                return fail(Status.REGISTRY_ERROR,
                        "Document créé ou retrouvé, mais son URI n'a pas pu être enregistrée ; la publication sera retrouvée par son nom unique.");
        }

        boolean alreadyMatches = false;
        try { alreadyMatches = matchesSource(documentUri, sourceLength, sourceHash, access); }
        catch (IOException ignored) {
            // Un document vide/partiel peut temporairement refuser la lecture.
            // On tente l'écriture ; seule la relecture APRÈS fermeture décide du succès.
        }
        if (!alreadyMatches) {
            if (!writeSource(source, sourceLength, sourceHash, documentUri, access))
                return new Result(Status.WRITE_FAILED, id, documentUri, entry.displayName,
                        sourceLength, sourceHash, "Écriture SAF interrompue ou source locale modifiée ; nouvelle tentative possible.");
            try {
                if (!matchesSource(documentUri, sourceLength, sourceHash, access))
                    return new Result(Status.VERIFY_FAILED, id, documentUri, entry.displayName,
                            sourceLength, sourceHash, "La relecture SAF ne correspond pas aux octets locaux ; publication non acquittée.");
            } catch (IOException error) {
                return new Result(Status.VERIFY_FAILED, id, documentUri, entry.displayName,
                        sourceLength, sourceHash, "Lecture de vérification SAF impossible : "
                        + error.getClass().getSimpleName() + ". Publication conservée en attente.");
            }
        }

        RecoveryPublicationRegistry.Entry verified = entry.verified();
        if (verified == null || !registry.upsert(verified) || !store.write(registry))
            return new Result(Status.REGISTRY_ERROR, id, documentUri, entry.displayName,
                    sourceLength, sourceHash, "Le document est vérifié, mais l'état VERIFIED n'a pas été enregistré durablement.");
        return new Result(Status.PUBLISHED, id, documentUri, entry.displayName,
                sourceLength, sourceHash, "Document SAF relu et vérifié octet par octet.");
    }

    public static String publicationId(SessionJournal identity, String recoveredName) {
        if (identity == null || recoveredName == null) return "";
        String material = identity.sessionId + "\n" + identity.segmentGeneration + "\n"
                + identity.file + "\n" + recoveredName;
        return sha256(material.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String displayName(String recoveredName, String publicationId) {
        if (!safeLeaf(recoveredName) || publicationId == null || publicationId.length() < 12) return "";
        int dot = recoveredName.toLowerCase(Locale.ROOT).lastIndexOf(".gpx");
        String stem = dot > 0 ? recoveredName.substring(0, dot) : recoveredName;
        // Rester sous les limites usuelles des fournisseurs SAF (notamment FAT/SD).
        if (stem.length() > 180) stem = stem.substring(0, 180);
        return stem + "_" + publicationId.substring(0, 12) + ".gpx";
    }

    private static boolean writeSource(File source, long expectedLength, String expectedHash,
                                       String documentUri, SafAccess access) {
        MessageDigest digest = newDigest();
        long written = 0L;
        try (InputStream in = new FileInputStream(source);
             OutputStream out = access.openWrite(documentUri)) {
            if (out == null) return false;
            byte[] buffer = new byte[BUFFER];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (n == 0) continue;
                out.write(buffer, 0, n);
                digest.update(buffer, 0, n);
                written += n;
            }
            out.flush();
        } catch (IOException error) { return false; }
        return written == expectedLength && toHex(digest.digest()).equals(expectedHash);
    }

    private static boolean matchesSource(String documentUri, long expectedLength,
                                         String expectedHash, SafAccess access) throws IOException {
        MessageDigest digest = newDigest();
        long total = 0L;
        try (InputStream in = access.openRead(documentUri)) {
            if (in == null) throw new IOException("Flux de lecture SAF nul");
            byte[] buffer = new byte[BUFFER];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (n == 0) continue;
                digest.update(buffer, 0, n);
                total += n;
                if (total > expectedLength) return false;
            }
        }
        return total == expectedLength && toHex(digest.digest()).equals(expectedHash);
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER]; int n;
            while ((n = in.read(buffer)) != -1) if (n > 0) digest.update(buffer, 0, n);
        }
        return toHex(digest.digest());
    }

    private static String sha256(byte[] bytes) { MessageDigest digest = newDigest(); return toHex(digest.digest(bytes)); }
    private static MessageDigest newDigest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 indisponible", impossible); }
    }
    private static String toHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) { int v = b & 255; out.append(digits[v >>> 4]).append(digits[v & 15]); }
        return out.toString();
    }
    private static boolean safeLeaf(String value) {
        if (value == null || value.isEmpty() || value.length() > 512 || value.equals(".")
                || value.equals("..") || value.contains("/") || value.contains("\\")) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 0x20) return false;
        return true;
    }
    private static boolean contentUri(String value) {
        return value != null && value.startsWith("content://") && value.length() <= 8192;
    }
    private static Result fail(Status status, String message) {
        return new Result(status, "", "", "", 0L, "", message);
    }
}
