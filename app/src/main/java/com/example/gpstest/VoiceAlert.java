package com.example.gpstest;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/**
 * SYNTHÈSE VOCALE — l'effet, isolé de la décision.
 *
 * ══════════════════════════════════════════════════════════════════════
 * SÉPARATION STRICTE : LossAnnouncer DÉCIDE, VoiceAlert PARLE
 * ══════════════════════════════════════════════════════════════════════
 *
 * Toute la logique « quand parler / combien de fois / quand se taire » est
 * dans LossAnnouncer, testable sans téléphone. Cette classe ne fait que
 * prononcer un texte déjà décidé. C'est ce qui permet de vérifier les cas
 * limites (grâce, plafond, réarmement) sans aucun appareil.
 *
 * ══════════════════════════════════════════════════════════════════════
 * LES PIÈGES DE TextToSpeech, TOUS RÉELS
 * ══════════════════════════════════════════════════════════════════════
 *
 * 1. L'INITIALISATION EST ASYNCHRONE ET LENTE (100 ms à 2 s).
 *    speak() appelé avant onInit() échoue en silence. On file donc les
 *    demandes en attente jusqu'à l'initialisation.
 *
 * 2. ELLE PEUT NE JAMAIS ABOUTIR. Sur un appareil sans moteur vocal
 *    installé, onInit() renvoie ERROR — ou n'est jamais appelé. Sans
 *    garde-fou, l'app garderait un objet mort en mémoire en croyant
 *    pouvoir parler.
 *
 * 3. LA LANGUE PEUT MANQUER. Un moteur TTS sans voix française prononce
 *    du français avec un accent anglais, ou ne dit rien. On vérifie
 *    isLanguageAvailable() et on se rabat sur la langue par défaut.
 *
 * 4. La synthèse ne doit JAMAIS bloquer ni faire tomber le service. Toute
 *    exception est absorbée : une alerte vocale manquée est un désagrément,
 *    un crash pendant un enregistrement est une perte de données.
 *
 * 5. Il faut appeler shutdown() à la fin. Un TextToSpeech non libéré
 *    garde un service lié actif : fuite de mémoire et batterie.
 */
public final class VoiceAlert {

    // PAS de langue cible imposée. La langue de l'alerte est celle du
    // téléphone : un utilisateur hispanophone, germanophone ou arabophone
    // doit entendre l'alerte DANS SA LANGUE, et surtout pas du français
    // prononcé avec la phonétique de sa propre langue.
    //
    // On laisse donc TextToSpeech sur la langue par défaut du système, et
    // on laisse Android choisir la variante de ressource correspondante.

    private TextToSpeech tts = null;
    private volatile boolean ready = false;
    private volatile boolean failed = false;
    /** Vrai si le moteur n'a pas de voix pour la langue du téléphone. */
    private volatile boolean languageMissing = false;

    /** Texte demandé avant l'initialisation. Un seul : c'est une alerte,
     *  pas une file de messages. Le plus récent gagne. */
    private String pending = null;

    public VoiceAlert(Context ctx) {
        try {
            tts = new TextToSpeech(ctx.getApplicationContext(),
                    new TextToSpeech.OnInitListener() {
                        @Override
                        public void onInit(int status) {
                            if (status != TextToSpeech.SUCCESS) {
                                failed = true;
                                tts = null;
                                return;
                            }
                            configureLanguage();
                            ready = true;
                            String p = pending;
                            pending = null;
                            if (p != null) speak(p);
                        }
                    });
        } catch (Exception e) {
            tts = null;
            failed = true;
        }
    }

    /**
     * Vérifie seulement qu'une voix existe pour la langue du téléphone.
     *
     * On ne force AUCUNE langue. Le seul cas à traiter est celui où le
     * moteur n'a pas de voix pour la langue par défaut (appareil vendu avec
     * une seule voix anglaise, par exemple) : dans ce cas on le SIGNALE
     * plutôt que de prononcer du français avec une bouche anglaise.
     */
    private void configureLanguage() {
        if (tts == null) return;
        try {
            Locale loc = Locale.getDefault();
            int r = tts.isLanguageAvailable(loc);
            if (r < TextToSpeech.LANG_AVAILABLE) {
                // Aucune voix pour la langue du téléphone. On le note : les
                // réglages afficheront l'avertissement, et speak() continuera
                // silencieusement (une alerte manquée n'est pas un crash).
                languageMissing = true;
            }
            tts.setSpeechRate(1.0f);
            tts.setPitch(1.0f);
        } catch (Exception ignored) { }
    }

    /**
     * Prononce un texte. Non bloquant, ne lève jamais.
     *
     * Si le moteur n'est pas encore prêt, le texte est mémorisé et sera
     * prononcé à l'initialisation — à condition qu'il soit encore pertinent.
     * Si une nouvelle demande arrive avant, elle REMPLACE la précédente :
     * on ne veut pas entendre « perte de signal depuis 15 s » puis
     * « perte de signal depuis 45 s » en rafale à l'initialisation.
     */
    public void speak(String text) {
        if (text == null || text.isEmpty()) return;
        if (failed) return;
        if (!ready) { pending = text; return; }
        if (tts == null) return;
        try {
            // QUEUE_FLUSH : une alerte remplace la précédente. Une alerte
            // GPS périmée n'a aucune valeur — mieux vaut couper.
            Bundle params = new Bundle();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params,
                    "gps-alert-" + System.currentTimeMillis());
        } catch (Exception ignored) {
            // Une alerte vocale manquée ne doit jamais interrompre le suivi.
        }
    }

    /** Prêt à parler ? Utile pour l'afficher dans les réglages. */
    public boolean isReady() { return ready; }

    /** Moteur indisponible ? L'interface doit alors masquer l'option. */
    public boolean hasFailed() { return failed || languageMissing; }

    /** Vrai si le moteur n'a pas de voix pour la langue du téléphone. */
    public boolean hasLanguage() { return !languageMissing; }

    /** À appeler dans stopTracking / onDestroy. Idempotent. */
    public void shutdown() {
        if (tts == null) return;
        try {
            tts.stop();
            tts.shutdown();
        } catch (Exception ignored) { }
        tts = null;
        ready = false;
    }
}
