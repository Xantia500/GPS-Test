package com.example.gpstest;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Accès DISQUE au journal de session.
 *
 * POURQUOI CETTE CLASSE EXISTE, alors que SessionJournal sait déjà sérialiser :
 *   - SessionJournal est PUR (aucun Android), donc testable hors appareil ;
 *   - le format et la formulation y vivent, et sont testés ;
 *   - ici, uniquement l'entrée/sortie de fichier, qui a besoin d'un Context.
 *
 * Et surtout, cette classe est appelée depuis DEUX endroits :
 *   - MainActivity.onCreate() : pour afficher la notice dès l'ouverture de
 *     l'app (c'est là que l'utilisateur regarde, pas quand il appuie sur
 *     DÉMARRER) ;
 *   - TrackingService.onCreate() : pour ne pas perdre l'information si
 *     l'activité n'a jamais été ouverte.
 * La première version ne le faisait que dans le service : la notice
 * n'apparaissait donc qu'au moment où l'utilisateur relançait une session,
 * c'est-à-dire trop tard pour être utile.
 */
public final class SessionStore {

    private SessionStore() { }

    private static File file(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, SessionJournal.FILE_NAME);
    }

    /** Lit le journal. Renvoie null s'il n'existe pas ou est illisible. */
    public static SessionJournal read(Context ctx) {
        File f = file(ctx);
        if (f == null || !f.exists()) return null;

        StringBuilder sb = new StringBuilder(256);
        try (InputStreamReader r = new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8)) {
            char[] buf = new char[512];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        } catch (Exception e) {
            return null;   // fichier illisible : on ne prétend rien savoir
        }
        return SessionJournal.parse(sb.toString());
    }

    /**
     * Lit puis SUPPRIME le journal.
     *
     * Supprimer après lecture est volontaire : la notice ne doit s'afficher
     * qu'une fois. Un message qui revient à chaque ouverture devient du bruit
     * et l'utilisateur cesse de le lire.
     */
    public static SessionJournal consume(Context ctx) {
        SessionJournal j = read(ctx);
        File f = file(ctx);
        if (f != null && f.exists()) f.delete();
        return j;
    }

    public static void clear(Context ctx) {
        File f = file(ctx);
        if (f != null && f.exists()) f.delete();
    }

    public static boolean exists(Context ctx) {
        File f = file(ctx);
        return f != null && f.exists();
    }
}
