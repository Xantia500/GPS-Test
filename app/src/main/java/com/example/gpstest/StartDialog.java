package com.example.gpstest;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;


/**
 * LA CONFIRMATION DE DÉMARRAGE — le seul écran que verront vraiment les
 * utilisateurs, et celui qui répond à LA question : « où vont mes fichiers ? ».
 *
 * ══════════════════════════════════════════════════════════════════════
 * POURQUOI CE DIALOGUE EXISTE
 * ══════════════════════════════════════════════════════════════════════
 *
 * Un enregistreur de traces qui démarre sans rien dire produit
 * systématiquement la même conversation :
 *
 *   — « Où est passée ma trace ? »
 *   — « Dans /Android/data/com.example.gpstest/files/ »
 *   — « C'est où ? »
 *
 * On répond AVANT la question, en affichant à l'utilisateur :
 *   1. le dossier, en mots qu'il peut suivre (« Stockage interne /
 *      Téléchargements »), jamais une URI ni un chemin technique ;
 *   2. le NOM EXACT du fichier qui va être créé, calculé avec la même
 *      fonction que le service utilisera (GpxWriter.fileName) — donc sans
 *      risque de mensonge entre ce qu'on annonce et ce qui est écrit ;
 *   3. trois boutons de dossier en un geste, pour ceux qui n'ont pas envie
 *      de naviguer une arborescence.
 *
 * ══════════════════════════════════════════════════════════════════════
 * LE PIÈGE : NE PAS DÉMARRER DEUX FOIS
 * ══════════════════════════════════════════════════════════════════════
 *
 * Un AlertDialog survit à une rotation d'écran mal gérée : l'Activity est
 * détruite, le dialogue peut rester, et son callback référence l'ANCIENNE
 * instance. On peut alors démarrer deux fois si l'utilisateur tourne le
 * téléphone puis appuie sur DÉMARRER. D'où :
 *   - le dialogue est construit avec un Context capturé proprement et
 *     fermé dans tous les cas ;
 *   - « Changer de dossier » ferme le dialogue AVANT de lancer le
 *     sélecteur : on ne laisse jamais deux fenêtres empilées.
 */
public final class StartDialog {

    private StartDialog() { }

    /** Callback appelé quand l'utilisateur confirme le démarrage. */
    public interface OnStart {
        void start();
    }

    /**
     * Affiche la confirmation. Ne démarre RIEN : c'est l'appelant qui reçoit
     * OnStart.start() et lance TrackingService.
     *
     * @param activity Activity hôte (pour le sélecteur de dossier).
     * @param onStart  appelé une seule fois, si l'utilisateur confirme.
     */
    public static void show(final Activity activity, final OnStart onStart) {
        if (activity == null || activity.isFinishing()) return;

        final Context ctx = activity;
        Context themed = activity;

        View v = LayoutInflater.from(themed).inflate(R.layout.dialog_start_confirm, null);
        TextView tvWhere = v.findViewById(R.id.tvStartWhere);
        TextView tvFile = v.findViewById(R.id.tvStartFile);
        TextView tvNote = v.findViewById(R.id.tvStartNote);

        // Le nom du fichier est calculé avec la MÊME méthode que le service.
        // C'est ce qui garantit que ce qu'on affiche est ce qui sera écrit.
        long now = System.currentTimeMillis();
        GpxWriter.Version ver = Prefs.readGpxVersion(ctx);
        String fileName = GpxWriter.fileName(now, ver);

        tvWhere.setText(Destination.humanPath(ctx));
        tvFile.setText(ctx.getString(R.string.start_confirm_file, fileName));

        // On n'affiche la note « le fichier grossit » que si la destination
        // est externe : dans le dossier privé, l'utilisateur ne peut de toute
        // façon pas l'observer, la phrase serait du bruit.
        tvNote.setVisibility(Destination.isExternalActive(ctx) ? View.VISIBLE : View.GONE);
        tvNote.setText(R.string.start_confirm_publish_note);

        // THÈME EXPLICITE POUR LE DIALOGUE.
        //
        // AlertDialog construit à la main n'hérite pas toujours du thème de
        // l'Activity pour ses fenêtres : sur un thème DayNight, il peut
        // apparaître clair sur une app sombre (ou l'inverse) — le titre et
        // les boutons deviennent illisibles. On force donc l'overlay déclaré
        // dans themes.xml, qui lui-même suit le mode nuit.
        final AlertDialog dialog = new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(themed, R.style.ThemeOverlay_GpsTest_Dialog))
                .setTitle(R.string.start_confirm_title)
                .setView(v)
                .setPositiveButton(R.string.start_confirm_go, null)   // null : on gère nous-mêmes
                .setNegativeButton(R.string.start_confirm_cancel, null)
                .create();

        dialog.setOnShowListener(d -> {
            Button go = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            go.setOnClickListener(bv -> {
                dialog.dismiss();
                // OnStart est appelé APRÈS la fermeture, pour que le
                // démarrage ne se fasse jamais sous une fenêtre modale.
                onStart.start();
            });
        });

        // Les presets : un geste = une destination. « Choisir un autre
        // dossier » ouvre le sélecteur système et ferme ce dialogue — deux
        // fenêtres empilées seraient une source de confusion et, sur
        // certains appareils, la seconde masquerait le bouton de validation
        // de la première.
        LinearLayout presets = v.findViewById(R.id.llDestPresets);
        Button other = new Button(themed);
        other.setText(R.string.dest_choose_other);
        other.setAllCaps(false);
        other.setOnClickListener(x -> {
            dialog.dismiss();
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).chooseDestinationFolder();
            }
        });
        presets.addView(other, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        for (final DestPresets.Preset p : DestPresets.PRESETS) {
            Button b = new Button(themed);
            // Libellé depuis la source unique (DestPresets), jamais dupliqué.
            b.setText(p.label);
            b.setAllCaps(false);
            b.setOnClickListener(x -> {
                boolean ok = Destination.applyPreset(ctx, p);
                if (!ok) {
                    tvWhere.setText(R.string.dest_set_failed);
                    return;
                }
                // ID stable + libellé : sans l'ID, l'écran des réglages ne
                // saurait pas quel bouton cocher au retour.
                Prefs.writeDestPreset(ctx, p.id);
                Prefs.writeDestLabel(ctx, p.isAppPrivate() ? null : p.label);
                // Recalcul immédiat de l'affichage : l'utilisateur doit VOIR
                // que son geste a changé quelque chose, dans le même écran.
                tvWhere.setText(Destination.humanPath(ctx));
                tvNote.setVisibility(
                        Destination.isExternalActive(ctx) ? View.VISIBLE : View.GONE);
            });
            presets.addView(b, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        activity.findViewById(android.R.id.content).post(() -> {
            if (!activity.isFinishing()) dialog.show();
        });
    }

    /**
     * Texte à afficher dans la barre de statut de l'écran principal quand un
     * enregistrement est en cours, pour que la destination soit visible en
     * permanence et pas seulement au démarrage.
     *
     * C'est un choix assumé : une ligne d'information en plus, mais
     * l'utilisateur n'a plus jamais à se demander où va sa trace.
     */
    public static String statusLine(Context ctx) {
        String st = TrackingState.get().getPublishStatus();
        String label = TrackingState.get().getDestLabel();
        if (label == null) return null;             // destination privée
        if (st == null) return ctx.getString(R.string.publish_wait);
        if ("ok".equals(st)) return ctx.getString(R.string.publish_ok);
        // st commence par "err:" -> on affiche l'explication utile, pas le
        // code technique, et surtout on rassure : rien n'est perdu.
        return ctx.getString(R.string.publish_err);
    }
}
