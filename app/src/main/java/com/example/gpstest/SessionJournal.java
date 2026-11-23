package com.example.gpstest;

/**
 * JOURNAL DE SESSION : la seule chose qui survit à un « kill -9 ».
 *
 * Un fichier de ~200 octets, écrit aux TRANSITIONS (ouverture de trace,
 * fermeture, extinction d'écran, changement de réglage, arrêt) et jamais à
 * chaque point : à 1 point/seconde, réécrire un fichier 3 600 fois par heure
 * userait la flash pour rien.
 *
 * À quoi ça sert : au démarrage suivant, si le journal dit encore
 * « dirty=true », c'est qu'une session n'a pas été terminée proprement. On
 * peut alors le DIRE à l'utilisateur au lieu de faire comme si de rien
 * n'était — c'est la différence entre un outil et un jouet.
 *
 * Classe 100 % PURE : aucune dépendance Android, donc testable hors appareil.
 * C'est pour cette raison que le format texte (clé=valeur) est ici et pas
 * dans le service : un format non testé finit toujours par se corrompre.
 */
public final class SessionJournal {

    /** Nom du fichier, dans getExternalFilesDir(null). */
    public static final String FILE_NAME = "session.state";

    /** true tant que la session n'a pas été terminée proprement. */
    public boolean dirty = true;
    /**
     * La session était-elle encore ATTENDUE à la dernière transition ?
     *
     * Trois fins possibles, et il faut les distinguer :
     *   1. arrêt voulu (bouton)      -> dirty=false -> aucune notice ;
     *   2. session EN COURS          -> dirty=true  -> un `kill -9` donnera
     *                                   lieu à une notice ;
     *   3. session SUSPENDUE         -> dirty=false -> l'app s'est arrêtée
     *      (mode économie)              exprès ; constater une « interruption »
     *                                   au retour serait un FAUX POSITIF.
     *
     * Sans ce champ, un simple passage écran éteint en mode économie faisait
     * afficher « session interrompue » à l'ouverture de l'app. Un message qui
     * crie au loup se fait ignorer, puis ne sert plus à rien le jour où il a
     * raison.
     */
    public boolean expectedAlive = true;
    public long startMs;
    public long savedAtMs;
    public boolean recording;
    public boolean suspended;
    public int points;
    public int segment;
    public int segmentPoints;
    public int policyId;
    /** Nom du dernier fichier GPX concerné (jamais null, éventuellement vide). */
    public String file = "";

    private SessionJournal() { }

    /** Construit un instantané. Tous les paramètres sont obligatoires. */
    public static SessionJournal of(boolean dirty, long startMs, long savedAtMs,
                                    boolean recording, boolean suspended,
                                    int points, int segment, int segmentPoints,
                                    int policyId, String file) {
        SessionJournal j = new SessionJournal();
        j.dirty = dirty;
        j.expectedAlive = !suspended;
        j.startMs = startMs;
        j.savedAtMs = savedAtMs;
        j.recording = recording;
        j.suspended = suspended;
        j.points = points;
        j.segment = segment;
        j.segmentPoints = segmentPoints;
        j.policyId = policyId;
        j.file = file != null ? file : "";
        return j;
    }

    /** Sérialisation. Format clé=valeur, une par ligne, UTF-8. */
    public String toText() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("start=").append(startMs).append('\n');
        sb.append("recording=").append(recording).append('\n');
        sb.append("points=").append(points).append('\n');
        sb.append("file=").append(file).append('\n');
        sb.append("segment=").append(segment).append('\n');
        sb.append("segmentPoints=").append(segmentPoints).append('\n');
        sb.append("policy=").append(policyId).append('\n');
        sb.append("suspended=").append(suspended).append('\n');
        sb.append("savedAt=").append(savedAtMs).append('\n');
        sb.append("dirty=").append(dirty).append('\n');
        sb.append("expectedAlive=").append(expectedAlive).append('\n');
        return sb.toString();
    }

    /**
     * Analyse tolérante : jamais d'exception, jamais de null pour du texte
     * valide. Une ligne illisible est ignorée, pas fatale.
     *
     * Renvoie null seulement si le texte est vide ou nul : dans ce cas il n'y
     * a rien à signaler à l'utilisateur, et inventer une « session
     * interrompue » serait un faux positif.
     */
    public static SessionJournal parse(String text) {
        if (text == null || text.trim().isEmpty()) return null;

        SessionJournal j = new SessionJournal();
        boolean sawAnyKey = false;

        for (String raw : text.split("\n")) {
            String line = raw.trim();
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            sawAnyKey = true;
            try {
                switch (key) {
                    case "start":         j.startMs = Long.parseLong(val); break;
                    case "savedAt":       j.savedAtMs = Long.parseLong(val); break;
                    case "points":        j.points = parseIntSafely(val, 0); break;
                    case "segment":       j.segment = parseIntSafely(val, 0); break;
                    case "segmentPoints": j.segmentPoints = parseIntSafely(val, 0); break;
                    case "policy":        j.policyId = parseIntSafely(val, 0); break;
                    case "recording":     j.recording = Boolean.parseBoolean(val); break;
                    case "suspended":     j.suspended = Boolean.parseBoolean(val); break;
                    case "dirty":         j.dirty = Boolean.parseBoolean(val); break;
                    case "expectedAlive": j.expectedAlive = Boolean.parseBoolean(val); break;
                    case "file":          j.file = val; break;
                    default:              /* clé inconnue : ignorée, pas d'erreur */ break;
                }
            } catch (NumberFormatException ignored) {
                // valeur numérique corrompue : on garde la valeur par défaut
            }
        }
        return sawAnyKey ? j : null;
    }

    private static int parseIntSafely(String v, int def) {
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * Résumé lisible pour l'utilisateur, ou null s'il n'y a rien à signaler.
     *
     * C'est volontairement une méthode de cette classe et pas du service :
     * la formulation (« fichier lisible ») est une promesse faite à
     * l'utilisateur, elle mérite d'être testée.
     */
    public String interruptionNotice() {
        // Une session suspendue volontairement n'est pas une interruption.
        if (!dirty || !expectedAlive) return null;
        if (file == null || file.isEmpty()) return null;
        int n = segmentPoints > 0 ? segmentPoints : points;
        return "Session précédente interrompue : " + file
                + " (" + n + " points, fichier lisible)";
    }
}
