package com.example.gpstest;

import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Adaptateur Android SAF : recherche, création et flux d'un document récupéré. */
public final class SafRecoveryAccess implements GpxRecoveryPublisher.SafAccess {
    /** Évite de parcourir sans borne un dossier SAF contenant une quantité anormale d'entrées. */
    private static final int MAX_SCANNED_CHILDREN = 20_000;
    private final Context context;

    public SafRecoveryAccess(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
    }

    @Override public boolean hasReadWriteAccess(String treeUri) {
        if (context == null || treeUri == null) return false;
        final Uri expected;
        try { expected = Uri.parse(treeUri); }
        catch (Exception invalid) { return false; }
        try {
            for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
                if (expected.equals(permission.getUri()) && permission.isReadPermission()
                        && permission.isWritePermission()) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    @Override public List<GpxRecoveryPublisher.Document> findByDisplayName(
            String treeUri, String displayName) throws IOException {
        Uri tree = parseTree(treeUri);
        String treeDocumentId;
        try { treeDocumentId = DocumentsContract.getTreeDocumentId(tree); }
        catch (Exception error) { throw new IOException("URI de dossier SAF invalide", error); }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocumentId);
        String[] projection = new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        Cursor cursor = null;
        ArrayList<GpxRecoveryPublisher.Document> matches = new ArrayList<>();
        try {
            cursor = context.getContentResolver().query(children, projection, null, null, null);
            if (cursor == null) throw new IOException("Le fournisseur SAF ne permet pas de lister le dossier");
            int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            if (idColumn < 0 || nameColumn < 0) throw new IOException("Colonnes SAF manquantes");
            int scanned = 0;
            while (cursor.moveToNext()) {
                if (++scanned > MAX_SCANNED_CHILDREN)
                    throw new IOException("Dossier SAF trop volumineux pour une recherche exhaustive sûre");
                String name = cursor.getString(nameColumn);
                if (!displayName.equals(name)) continue;
                String id = cursor.getString(idColumn);
                if (id == null || id.isEmpty()) throw new IOException("Document SAF sans identifiant");
                Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                matches.add(new GpxRecoveryPublisher.Document(document.toString(), name));
                // Une liste complète est nécessaire : ne jamais créer un second document
                // parce qu'une requête tronquée a omis le premier.
                if (matches.size() > 8) throw new IOException("Trop de collisions SAF sur le nom réservé");
            }
            return matches;
        } catch (SecurityException denied) {
            throw new IOException("Permission de lecture SAF refusée", denied);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    @Override public String createDocument(String treeUri, String displayName, String mimeType)
            throws IOException {
        Uri tree = parseTree(treeUri);
        try {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            Uri created = DocumentsContract.createDocument(context.getContentResolver(),
                    parent, mimeType, displayName);
            if (created == null) throw new IOException("Le fournisseur SAF a refusé la création");
            return created.toString();
        } catch (SecurityException denied) {
            throw new IOException("Permission d'écriture SAF refusée", denied);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Dossier SAF invalide", invalid);
        }
    }

    @Override public OutputStream openWrite(String documentUri) throws IOException {
        Uri document = parseDocument(documentUri);
        try {
            OutputStream out = context.getContentResolver().openOutputStream(document, "wt");
            if (out == null) throw new IOException("Flux de sortie SAF nul");
            return out;
        } catch (SecurityException denied) {
            throw new IOException("Permission d'écriture SAF refusée", denied);
        }
    }

    @Override public InputStream openRead(String documentUri) throws IOException {
        Uri document = parseDocument(documentUri);
        try {
            InputStream in = context.getContentResolver().openInputStream(document);
            if (in == null) throw new IOException("Flux de lecture SAF nul");
            return in;
        } catch (SecurityException denied) {
            throw new IOException("Permission de lecture SAF refusée", denied);
        }
    }

    private static Uri parseTree(String text) throws IOException {
        if (text == null || !text.startsWith("content://")) throw new IOException("URI SAF invalide");
        try { return Uri.parse(text); }
        catch (Exception error) { throw new IOException("URI SAF illisible", error); }
    }

    private static Uri parseDocument(String text) throws IOException {
        if (text == null || !text.startsWith("content://")) throw new IOException("URI document SAF invalide");
        try { return Uri.parse(text); }
        catch (Exception error) { throw new IOException("URI document SAF illisible", error); }
    }
}
