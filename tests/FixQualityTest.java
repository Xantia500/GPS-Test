package com.example.gpstest;

/**
 * Tests du moteur de réception — v5.7 (sans Android).
 *
 * Les sections 8, 9 et 10 sont nouvelles : elles couvrent les trois
 * corrections demandées par l'utilisateur (0,0 systématique, suppression de
 * la limite de vitesse, temps pare-chocs). Les sections 11+ couvrent la
 * politique d'annonce configurable.
 */
public class FixQualityTest {

    static int pass = 0, fail = 0;
    static final long T0 = 1_700_000_000_000L;      // heure murale de référence
    static final long E0 = 5_000_000L;              // horloge monotone

    public static void main(String[] args) {

        // ============================================================
        section("1. Microdegrés : ±180.000000 en int32");
        // ============================================================
        ok(FixGeometry.toMicro(152.321574) == 152_321_574,
                "152.321574 -> +152321574 (l'exemple de l'utilisateur)");
        ok(FixGeometry.fromMicro(152_321_574) == 152.321574, "aller-retour exact");
        ok(FixGeometry.toMicro(-152.321574) == -152_321_574, "négatif symétrique");
        ok(FixGeometry.toMicro(0.0) == 0, "0° -> 0");
        ok(FixGeometry.toMicro(180.0) == 180_000_000, "+180° tient dans int32");
        ok(FixGeometry.toMicro(-180.0) == -180_000_000, "-180° tient dans int32");
        ok(180_000_000 < Integer.MAX_VALUE, "180M < 2 147 483 647 : aucune saturation");
        ok(throwsIAE(180.0000001), "180.0000001° -> exception (jamais rogné)");
        ok(throwsIAE(200.0), "200° -> exception");
        ok(throwsIAE(Double.NaN), "NaN -> exception");
        // La sentinelle de toLatMicro ne sert pas à marquer 0,0 (latitude 0
        // est l'équateur, une latitude parfaitement valide) mais à signaler
        // NaN et hors bornes sans lever d'exception.
        ok(FixGeometry.toLatMicro(Double.NaN) == Integer.MIN_VALUE, "latitude NaN -> sentinelle");
        ok(FixGeometry.toLatMicro(95.0) == Integer.MIN_VALUE, "latitude 95° -> sentinelle");
        ok(FixGeometry.toLatMicro(0.0) == 0, "latitude 0 (équateur) -> 0, pas une sentinelle");
        ok(FixGeometry.toLonMicro(0.0) == 0, "longitude 0 -> 0 (pas de sentinelle)");
        ok(FixGeometry.sameFixMicro(48.8566, 2.3522, 48.8566, 2.3522),
                "même position = même entier");
        ok(!FixGeometry.sameFixMicro(48.8566, 2.3522, 48.85661, 2.3522),
                "1e-5° d'écart = entier différent (précision 1 m)");

        // ============================================================
        section("2. Q3.6 binaire : 1,7 km, inutilisable");
        // ============================================================
        double step36 = FixGeometry.toBinaryQ3_6(0.015625);
        ok(step36 == 1.0, "0,015625° = 1 quanta (2^-6)");
        ok(FixGeometry.fromBinaryQ3_6(1) == 0.015625, "quanta -> 0,015625°");
        ok(0.015625 * 111_320 > 1700, "soit 1740 m : trace impossible");

        // ============================================================
        section("3. Haversine : distances et antiméridien");
        // ============================================================
        double d1deg = FixGeometry.distanceMeters(48.0, 2.0, 49.0, 2.0);
        ok(d1deg > 111_000 && d1deg < 111_500, "1° de latitude = 111,2 km");
        ok(FixGeometry.distanceMeters(48.0, 2.0, 48.0, 2.0) == 0.0, "point identique = 0");
        // ANTIMÉRIDIEN : votre question 3. Le trajet de +179,9 à -179,9 ne
        // fait que 0,2° réels (22 km), pas 359,8°.
        double anti = FixGeometry.distanceMeters(0.0, 179.9, 0.0, -179.9);
        ok(anti > 21_000 && anti < 23_000,
                String.format("antiméridien : %.0f m (0,2° réels, pas 39 800 km)", anti));
        // Le calcul NAÏF donnerait une catastrophe. On le montre.
        double naive = Math.abs(-179.9 - 179.9) * 111_320;
        ok(naive > 39_000_000, String.format("calcul naïf = %.0f m (faux)", naive));
        // ANTIMÉRIDIEN EN LATITUDE HAUTE : c'est là que les formules en
        // degrés se cassent vraiment (convergence des méridiens).
        double highLat = FixGeometry.distanceMeters(60.0, 179.95, 60.0, -179.95);
        double expected = 2 * Math.PI * 6_371_008.8
                * Math.cos(Math.toRadians(60.0)) * (0.1 / 360.0);
        ok(Math.abs(highLat - expected) < 50,
                String.format("à 60°N : %.0f m (théorique %.0f m) — méridiens convergents",
                        highLat, expected));

        // ============================================================
        section("4. Porte de validité : conditions 1 et 2");
        // ============================================================
        ok(FixQuality.checkFix(Double.NaN, 2.0, 5f, T0, T0)
                == FixQuality.RejectReason.NON_FINITE, "latitude NaN -> refusé");
        ok(FixQuality.checkFix(48.0, Double.POSITIVE_INFINITY, 5f, T0, T0)
                == FixQuality.RejectReason.NON_FINITE, "longitude Inf -> refusé");
        ok(FixQuality.checkFix(91.0, 2.0, 5f, T0, T0)
                == FixQuality.RejectReason.OUT_OF_BOUNDS, "latitude 91° -> refusé");
        ok(FixQuality.checkFix(48.85, 180.5, 5f, T0, T0)
                == FixQuality.RejectReason.OUT_OF_BOUNDS, "longitude 180,5° -> refusé");

        // ============================================================
        section("5. Échelle de précision (votre question « 50, 100 m ? »)");
        // ============================================================
        ok(FixQuality.band(8f) == FixQuality.AccuracyBand.GOOD, "8 m -> GOOD");
        ok(FixQuality.band(50f) == FixQuality.AccuracyBand.GOOD, "50 m -> GOOD (limite incluse)");
        ok(FixQuality.band(50.1f) == FixQuality.AccuracyBand.DEGRADED, "50,1 m -> DEGRADED");
        ok(FixQuality.band(100f) == FixQuality.AccuracyBand.DEGRADED, "100 m -> DEGRADED");
        ok(FixQuality.band(100.1f) == FixQuality.AccuracyBand.USELESS, "100,1 m -> USELESS");
        ok(FixQuality.band(500f) == FixQuality.AccuracyBand.USELESS, "500 m -> USELESS");
        ok(FixQuality.band(Float.NaN) == FixQuality.AccuracyBand.USELESS,
                "précision inconnue -> USELESS (jamais « on ne sait pas donc OK »)");
        ok(FixQuality.band(0f) == FixQuality.AccuracyBand.USELESS,
                "précision 0 m -> USELESS (champ non renseigné)");

        ok(FixQuality.checkFix(48.85, 2.35, 75f, T0, T0) == FixQuality.RejectReason.OK,
                "75 m : fix VALIDE intrinsèquement (mais non enregistrable)");
        ok(FixQuality.checkFix(48.85, 2.35, 150f, T0, T0)
                == FixQuality.RejectReason.USELESS_ACCURACY, "150 m -> rejeté");
        ok(FixQuality.checkFix(48.85, 2.35, Float.NaN, T0, T0)
                == FixQuality.RejectReason.NO_ACCURACY, "précision NaN -> rejeté");
        ok(FixQuality.checkFix(48.85, 2.35, 0f, T0, T0)
                == FixQuality.RejectReason.NO_ACCURACY, "précision 0 -> rejeté");

        // ============================================================
        section("6. (0,0) : TOUJOURS un défaut (correction utilisateur)");
        // ============================================================
        // La v5.6 acceptait (0,0) avec une bonne précision. Faux : en phase
        // d'acquisition un récepteur annonce 5 m sur un (0,0) non résolu.
        ok(FixQuality.checkFix(0.0, 0.0, 8f, T0, T0)
                == FixQuality.RejectReason.NULL_ISLAND,
                "(0,0) avec 8 m de précision -> REFUSÉ (v5.6 l'acceptait : erreur)");
        ok(FixQuality.checkFix(0.0, 0.0, 500f, T0, T0)
                == FixQuality.RejectReason.NULL_ISLAND, "(0,0) à 500 m -> refusé");
        ok(FixQuality.checkFix(0.0, 0.0, Float.NaN, T0, T0)
                == FixQuality.RejectReason.NULL_ISLAND, "(0,0) sans précision -> refusé");
        ok(FixQuality.checkFix(0.0, 0.0000001, 8f, T0, T0) == FixQuality.RejectReason.OK,
                "0,0000001° n'est plus (0,0) : accepté");
        // L'île Null ne bloque pas éternellement : après 2 rejets, un vrai
        // fix accepté. Sinon l'utilisateur serait bloqué pour toujours.
        FixQuality.Tracker tNull = new FixQuality.Tracker();
        tNull.onFix(0.0, 0.0, 5f, E0, E0);
        tNull.onFix(0.0, 0.0, 5f, E0 + 1000, E0 + 1000);
        ok(tNull.state() == FixQuality.SignalState.NO_SIGNAL,
                "deux (0,0) -> toujours pas de signal");
        ok(tNull.lastReject() == FixQuality.RejectReason.NULL_ISLAND,
                "raison = île Null (la raison est DITE, pas avalée)");
        tNull.onFix(48.85, 2.35, 5f, E0 + 2000, E0 + 2000);
        ok(tNull.state() == FixQuality.SignalState.REACQUIRING,
                "vraie position après (0,0) -> réacquisition normale");

        // ============================================================
        section("7. Porte de validité : temps");
        // ============================================================
        ok(FixQuality.checkFix(48.85, 2.35, 5f, E0 + 60_000, E0)
                == FixQuality.RejectReason.FUTURE, "point 60 s dans le futur (monotone) -> rejeté");
        // 15 s de retard : refusé. Un fix vieux de 15 s décrit le passé.
        ok(FixQuality.checkFix(48.85, 2.35, 5f, E0 - 15_000, E0)
                == FixQuality.RejectReason.STALE, "fix vieux de 15 s (monotone) -> périmé");
        ok(FixQuality.checkFix(48.85, 2.35, 5f, E0 + 1000, E0)
                == FixQuality.RejectReason.OK, "fix 1 s dans le futur : toléré");

        // ============================================================
        section("8. NOUVEAU : plus de limite de vitesse");
        // ============================================================
        // L'utilisateur : « on retire la limite des 200 km/h, on peut même
        // utiliser son GPS dans un avion ». Les trois cas ci-dessous étaient
        // REFUSÉS en v5.6 et doivent maintenant passer.
        // 250 m en 1 s = 250 m/s = 900 km/h : vitesse de croisière d'un
        // avion de ligne. Refusée en v5.6 (limite 200 km/h).
        double latAvion = 48.0 + (250.0 / 111_320.0);
        FixQuality.Decision dAvion = FixQuality.acceptAsNextPoint(
                latAvion, 2.0, 5f, E0, E0, 48.0, 2.0, E0 - 1000);
        ok(dAvion.accept, String.format(
                "250 m/s = %.0f km/h (avion) -> ACCEPTÉ (refusé en v5.6)",
                dAvion.impliedSpeedMps * 3.6));
        ok(!dAvion.recordable == false, "et enregistrable (précision 5 m)");

        // 2 km en 60 s : 120 km/h. Accepté en v5.6 et toujours accepté.
        double lat2km = 48.8566 + 2000.0 / 111_320.0;
        FixQuality.Decision d2km60 = FixQuality.acceptAsNextPoint(
                lat2km, 2.3522, 5f, E0 + 60_000, E0 + 60_000,
                48.8566, 2.3522, E0);
        ok(d2km60.accept, "2 km en 60 s (120 km/h) -> accepté");

        // Le MÊME saut de 2 km en 1 s : 7200 km/h. C'est un artefact, mais
        // est-ce que le nouveau détecteur (Mach 1) l'attrape ? NON — et
        // c'est voulu. Voir la section suivante pour la vraie protection.
        FixQuality.Decision d2km1s = FixQuality.acceptAsNextPoint(
                lat2km, 2.3522, 5f, E0 + 1000, E0 + 1000,
                48.8566, 2.3522, E0);
        ok(!d2km1s.accept && d2km1s.reason == FixQuality.RejectReason.INCOHERENT_MOVE,
                "2 km en 1 s (7200 km/h = 2 000 m/s) -> refusé par le détecteur "
                + "de bug (au-delà de Mach 1), pas par une limite de plausibilité");

        // Le détecteur de bug attrape l'impossible absolu.
        double lat500km = 48.8566 + 500_000.0 / 111_320.0;
        FixQuality.Decision dBug = FixQuality.acceptAsNextPoint(
                lat500km, 2.3522, 5f, E0 + 1000, E0 + 1000,
                48.8566, 2.3522, E0);
        ok(!dBug.accept && dBug.reason == FixQuality.RejectReason.INCOHERENT_MOVE,
                "500 km en 1 s (1,8 M km/h) -> refusé : défaut du récepteur");
        // Hors de la fenêtre de 30 s, même un énorme déplacement est normal.
        FixQuality.Decision dFar = FixQuality.acceptAsNextPoint(
                lat500km, 2.3522, 5f, E0 + 60_000, E0 + 60_000,
                48.8566, 2.3522, E0);
        ok(dFar.accept, "500 km en 60 s -> accepté : hors fenêtre de 30 s, "
                + "aucune conclusion possible sur la vitesse instantanée");

        // ============================================================
        section("9. NOUVEAU : l'horloge monotone, pas l'heure murale");
        // ============================================================
        // L'heure murale RECULE (NTP, réglage manuel) mais l'horloge
        // monotone avance : la vitesse doit rester calculable et le point
        // accepté.
        // Le point est identique géométriquement ; seule l'heure murale a
        // reculé d'une heure. Comme AUCUNE décision ne dépend plus d'elle,
        // le résultat doit être strictement le même qu'avec une horloge saine.
        FixQuality.Decision dClk = FixQuality.acceptAsNextPoint(
                48.8567, 2.3523, 5f, E0 + 1000, E0 + 1000,
                48.8566, 2.3522, E0);
        ok(dClk.accept, "heure murale qui recule d'1 h : point ACCEPTÉ");
        ok(!Double.isNaN(dClk.impliedSpeedMps),
                "et la vitesse est calculée (horloge monotone)");
        ok(dClk.impliedSpeedMps > 1.0 && dClk.impliedSpeedMps < 20.0,
                String.format("vitesse = %.1f m/s : cohérente (≈13 m en 1 s)",
                        dClk.impliedSpeedMps));

        // Le temps ne recule jamais en monotone : dt <= 0 -> pas de vitesse,
        // mais le point reste accepté (on ne jette pas une position vraie).
        FixQuality.Decision dDt0 = FixQuality.acceptAsNextPoint(
                48.8567, 2.3523, 5f, E0, E0,
                48.8566, 2.3522, E0);
        ok(dDt0.accept, "dt = 0 : point accepté");
        ok(Double.isNaN(dDt0.impliedSpeedMps), "dt = 0 : vitesse indéterminée (NaN)");

        // ============================================================
        section("10. NOUVEAU : heure GPX forcée croissante");
        // ============================================================
        FixQuality.Tracker tClk = new FixQuality.Tracker();
        long w1 = tClk.wallTimeForWriting(T0);
        long w2 = tClk.wallTimeForWriting(T0 - 500);   // l'horloge recule
        long w3 = tClk.wallTimeForWriting(T0 - 400);
        ok(w1 == T0, "1re écriture : heure inchangée");
        ok(w2 == T0 + 1, "heure qui recule -> +1 ms (2e écriture)");
        ok(w3 == T0 + 2, "elle recule encore -> +2 ms cumulés");
        ok(w1 < w2 && w2 < w3, "GPX strictement croissant malgré l'horloge folle");
        ok(tClk.clampedTimes() == 2, "2 corrections comptées (visible dans l'écran expert)");
        // Sans recul, aucune correction.
        long w4 = tClk.wallTimeForWriting(T0 + 100_000);
        ok(w4 == T0 + 100_000, "heure normale : aucun recalage");

        // ============================================================
        section("11. Machine à états : perte et reprise");
        // ============================================================
        FixQuality.Tracker tr = new FixQuality.Tracker();
        ok(tr.state() == FixQuality.SignalState.NO_SIGNAL, "état initial : NO_SIGNAL");
        tr.onFix(48.8566, 2.3522, 5f, E0, E0);
        ok(tr.state() == FixQuality.SignalState.REACQUIRING, "1 fix -> REACQUIRING");
        tr.onFix(48.8567, 2.3523, 5f, E0 + 1000, E0 + 1000);
        ok(tr.state() == FixQuality.SignalState.REACQUIRING, "2 fixes -> REACQUIRING");
        tr.onFix(48.8568, 2.3524, 5f, E0 + 2000, E0 + 2000);
        ok(tr.state() == FixQuality.SignalState.OK, "3 fixes -> OK");

        // Silence prolongé -> perte
        long lost = E0 + 2000 + FixQuality.LOSS_TIMEOUT_MS + 1;
        ok(tr.tick(lost, true), "silence de 5 s -> l'état CHANGE");
        ok(tr.isLost(), "-> NO_SIGNAL");
        ok(tr.lostForMs(lost) > 0, "durée de perte mesurée");

        // GPS désactivé : détection accélérée
        FixQuality.Tracker trOff = new FixQuality.Tracker();
        trOff.onFix(48.0, 2.0, 5f, E0, E0);
        ok(!trOff.tick(E0 + FixQuality.GPS_OFF_TIMEOUT_MS - 100, false),
                "GPS coupé : pas encore de perte à 1,4 s");
        ok(trOff.tick(E0 + FixQuality.GPS_OFF_TIMEOUT_MS + 100, false),
                "GPS coupé : perte détectée à 1,6 s (au lieu de 5 s)");
        // Contrôle : avec le GPS actif, il n'y a pas encore de perte à 1,6 s.
        FixQuality.Tracker trOn = new FixQuality.Tracker();
        trOn.onFix(48.0, 2.0, 5f, E0, E0);
        ok(!trOn.tick(E0 + 1600, true), "GPS actif : 1,6 s de silence n'est pas une perte");

        // L'état ne redescend pas tout seul : seul tick le fait.
        FixQuality.Tracker trLive = new FixQuality.Tracker();
        trLive.onFix(48.0, 2.0, 5f, E0, E0);
        trLive.onFix(48.0001, 2.0, 5f, E0 + 1000, E0 + 1000);
        trLive.onFix(48.0002, 2.0, 5f, E0 + 2000, E0 + 2000);
        ok(trLive.isOk(), "3 fixes -> OK");
        ok(!trLive.tick(E0 + 2500, true), "0,5 s de silence : toujours OK");

        // Un fix rejeté fait retomber en réacquisition.
        FixQuality.Tracker trRej = new FixQuality.Tracker();
        for (int i = 0; i < 3; i++)
            trRej.onFix(48.0 + i * 1e-4, 2.0, 5f, E0 + i * 1000, E0 + i * 1000);
        ok(trRej.isOk(), "établi à OK");
        trRej.onFix(48.0, 2.0, 120f, E0 + 4000, E0 + 4000);
        ok(trRej.state() == FixQuality.SignalState.REACQUIRING,
                "fix à 120 m de précision -> REACQUIRING (pas NO_SIGNAL : il parle encore)");

        // ============================================================
        section("12. Précision dégradée : affiché mais pas enregistré");
        // ============================================================
        FixQuality.Tracker tDeg = new FixQuality.Tracker();
        for (int i = 0; i < 3; i++)
            tDeg.onFix(48.0 + i * 1e-4, 2.0, 5f, E0 + i * 1000, E0 + i * 1000);
        ok(tDeg.isOk(), "préalable : état OK");
        FixQuality.Decision dDeg = tDeg.onFix(48.001, 2.0, 70f,
                E0 + 4000, E0 + 4000);
        ok(dDeg.accept, "70 m : le fix est ACCEPTÉ (il est utilisable)");
        ok(!dDeg.recordable, "70 m : mais NON ENREGISTRABLE");
        ok(tDeg.state() == FixQuality.SignalState.REACQUIRING,
                "70 m : l'état retombe en réacquisition (écran grisé)");
        ok(!tDeg.shouldWrite(), "70 m : rien n'est écrit dans le GPX");
        // Et le retour à une bonne précision demande la séquence complète.
        tDeg.onFix(48.0011, 2.0, 5f, E0 + 5000, E0 + 5000);
        ok(tDeg.state() == FixQuality.SignalState.REACQUIRING,
                "retour à 5 m : 1 fix ne suffit pas");
        tDeg.onFix(48.0012, 2.0, 5f, E0 + 6000, E0 + 6000);
        ok(tDeg.state() == FixQuality.SignalState.REACQUIRING, "2 fixes : toujours pas");
        tDeg.onFix(48.0013, 2.0, 5f, E0 + 7000, E0 + 7000);
        ok(tDeg.isOk(), "3 fixes consécutifs : OK rétabli");

        // ============================================================
        section("13. reset / forceLoss");
        // ============================================================
        FixQuality.Tracker tR = new FixQuality.Tracker();
        for (int i = 0; i < 3; i++)
            tR.onFix(48.0 + i * 1e-4, 2.0, 5f, E0 + i * 1000, E0 + i * 1000);
        tR.forceLoss();
        ok(tR.isLost(), "forceLoss -> NO_SIGNAL immédiat");
        tR.reset();
        ok(tR.isLost() && tR.consecutiveGood() == 0, "reset -> état initial");
        ok(tR.lostEpisodes() == 0, "reset efface le compteur d'épisodes");

        // ============================================================
        section("14. NOUVEAU : politique d'annonce configurable");
        // ============================================================
        // « Avertir toutes les 30 s »
        LossAnnouncer.Policy p30 = new LossAnnouncer.Policy();
        p30.configure(true, true, LossAnnouncer.REPEAT_30S, 0);   // illimité
        ok(p30.tick(true, 0, false, 0) == LossAnnouncer.Action.SILENT,
                "30 s : pas d'alerte à 0 s");
        ok(p30.tick(true, 29_000, false, 29_000) == LossAnnouncer.Action.SILENT,
                "30 s : pas d'alerte à 29 s");
        ok(p30.tick(true, 30_000, false, 30_000) == LossAnnouncer.Action.ANNOUNCE_LOSS,
                "30 s : PREMIÈRE alerte à 30 s (le 1er intervalle est la grâce)");
        ok(p30.tick(true, 59_000, false, 59_000) == LossAnnouncer.Action.SILENT,
                "30 s : rien à 59 s");
        ok(p30.tick(true, 60_000, false, 60_000) == LossAnnouncer.Action.ANNOUNCE_LOSS,
                "30 s : rappel à 60 s");

        // « Avertir toutes les 5 min »
        LossAnnouncer.Policy p5 = new LossAnnouncer.Policy();
        p5.configure(true, true, LossAnnouncer.REPEAT_5MIN, 0);
        ok(p5.tick(true, 299_000, false, 299_000) == LossAnnouncer.Action.SILENT,
                "5 min : silence à 4 min 59");
        ok(p5.tick(true, 300_000, false, 300_000) == LossAnnouncer.Action.ANNOUNCE_LOSS,
                "5 min : alerte à 5 min");

        // « Jamais de rappel » : UNE annonce, tout de suite.
        LossAnnouncer.Policy pOnce = new LossAnnouncer.Policy();
        pOnce.configure(true, true, LossAnnouncer.REPEAT_NEVER, 0);
        ok(pOnce.tick(true, 6_000, false, 6_000) == LossAnnouncer.Action.ANNOUNCE_LOSS,
                "jamais : annonce dès la détection");
        ok(pOnce.tick(true, 60_000, false, 60_000) == LossAnnouncer.Action.SILENT,
                "jamais : silence total ensuite");
        ok(pOnce.tick(true, 3_600_000, false, 3_600_000) == LossAnnouncer.Action.SILENT,
                "jamais : encore silencieux 1 h après");

        // Plafond « nombre de fois »
        LossAnnouncer.Policy pCap = new LossAnnouncer.Policy();
        pCap.configure(true, true, LossAnnouncer.REPEAT_30S, 3);
        int n = 0;
        for (long t = 0; t <= 200_000; t += 1000)
            if (pCap.tick(true, t, false, t) == LossAnnouncer.Action.ANNOUNCE_LOSS) n++;
        ok(n == 3, "plafond 3 : exactement 3 annonces (reçu " + n + ")");

        // Désactivé : silence
        LossAnnouncer.Policy pOff = new LossAnnouncer.Policy();
        pOff.configure(false, true, LossAnnouncer.REPEAT_30S, 0);
        ok(pOff.tick(true, 60_000, false, 60_000) == LossAnnouncer.Action.SILENT,
                "désactivé : silence");
        // GPS coupé par l'utilisateur : silence, même activé
        LossAnnouncer.Policy pUser = new LossAnnouncer.Policy();
        pUser.configure(true, true, LossAnnouncer.REPEAT_30S, 0);
        ok(pUser.tick(true, 120_000, true, 120_000) == LossAnnouncer.Action.SILENT,
                "GPS coupé par l'utilisateur : jamais un mot");

        // ============================================================
        section("15. Reprise : annoncée une fois, après confirmation");
        // ============================================================
        LossAnnouncer.Policy pRec = new LossAnnouncer.Policy();
        pRec.configure(true, true, LossAnnouncer.REPEAT_30S, 0);
        pRec.tick(true, 30_000, false, 30_000);         // perte annoncée
        ok(pRec.tick(false, -1, false, 31_000) == LossAnnouncer.Action.SILENT,
                "reprise : rien au 1er tick de retour");
        ok(pRec.tick(false, -1, false, 33_000) == LossAnnouncer.Action.SILENT,
                "reprise : rien à +2 s");
        ok(pRec.tick(false, -1, false, 34_000) == LossAnnouncer.Action.ANNOUNCE_RECOVERY,
                "reprise : « Signal GPS valide » à +3 s confirmées");
        ok(pRec.tick(false, -1, false, 40_000) == LossAnnouncer.Action.SILENT,
                "reprise : annoncée UNE seule fois");

        // Reprise non demandée
        LossAnnouncer.Policy pNoRec = new LossAnnouncer.Policy();
        pNoRec.configure(true, false, LossAnnouncer.REPEAT_30S, 0);
        pNoRec.tick(true, 30_000, false, 30_000);
        ok(pNoRec.tick(false, -1, false, 34_000) == LossAnnouncer.Action.SILENT,
                "reprise désactivée : silence à la reprise");

        // Réarmement : une nouvelle perte après reprise réannonce.
        LossAnnouncer.Policy pRe = new LossAnnouncer.Policy();
        pRe.configure(true, true, LossAnnouncer.REPEAT_30S, 0);
        pRe.tick(true, 30_000, false, 30_000);
        pRe.tick(false, -1, false, 34_000);             // reprise annoncée
        ok(pRe.tick(true, 1_000, false, 35_000) == LossAnnouncer.Action.SILENT,
                "nouvelle perte : la grâce repart à zéro");
        ok(pRe.tick(true, 30_000, false, 64_000) == LossAnnouncer.Action.ANNOUNCE_LOSS,
                "nouvelle perte : réannoncée après 30 s");

        // ============================================================
        section("16. Textes et durée");
        // ============================================================
        // Le texte n'est plus une chaîne en dur : c'est un identifiant de
        // ressource, pour que la voix suive la LANGUE DU TÉLÉPHONE.
        ok(LossAnnouncer.textResId(LossAnnouncer.Action.ANNOUNCE_LOSS)
                == R.string.voice_text_loss, "perte -> ressource voice_text_loss");
        ok(LossAnnouncer.textResId(LossAnnouncer.Action.ANNOUNCE_RECOVERY)
                == R.string.voice_text_recovery, "reprise -> ressource voice_text_recovery");
        ok(LossAnnouncer.textResId(LossAnnouncer.Action.SILENT) == 0, "silence -> 0");
        ok(LossAnnouncer.durationLabel(45_000).equals("depuis 45 s"), "écran : 45 s");
        ok(LossAnnouncer.durationLabel(60_000).equals("depuis 1 min"), "écran : 1 min");
        ok(LossAnnouncer.durationLabel(105_000).equals("depuis 1 min 45 s"),
                "écran : 1 min 45 s");
        ok(LossAnnouncer.durationLabel(3_725_000).equals("depuis 62 min 5 s"),
                "écran : au-delà d'une heure, minutes (pas « 1 h » ambigu)");

        // ============================================================
        section("17. Le scénario (0,0) -> position réelle (votre cas terrain)");
        // ============================================================
        // « on passe de 0,0 à une position réelle et ça fait des drôles de
        // choses comme des distances et vitesses XXL »
        FixQuality.Tracker tNull2 = new FixQuality.Tracker();
        // Le récepteur établit une vraie position.
        for (int i = 0; i < 3; i++)
            tNull2.onFix(48.0 + i * 1e-4, 2.0, 5f, E0 + i * 1000L, E0 + i * 1000L);
        ok(tNull2.isOk(), "position établie à Paris");
        // Puis il crache (0,0) une fois (bug connu des modules).
        FixQuality.Decision dNull = tNull2.onFix(0.0, 0.0, 5f, E0 + 4000, E0 + 4000);
        ok(!dNull.accept, "(0,0) en cours de session -> REJETÉ");
        ok(dNull.reason == FixQuality.RejectReason.NULL_ISLAND, "raison : île Null");
        ok(!tNull2.shouldWrite(), "RIEN n'est écrit : la trace ne saute pas à (0,0)");
        // La vraie position revient.
        FixQuality.Decision dBack = tNull2.onFix(48.0004, 2.0, 5f, E0 + 5000, E0 + 5000);
        ok(dBack.accept, "vraie position suivante -> acceptée");
        // LA DISTANCE EST CELLE DU DÉPLACEMENT RÉEL, pas 5000 km.
        ok(!Double.isNaN(dBack.distanceM) && dBack.distanceM < 100,
                String.format("distance depuis le dernier point VALIDE = %.0f m "
                        + "(au lieu de ~5 000 km si (0,0) avait été accepté)",
                        dBack.distanceM));
        ok(tNull2.state() == FixQuality.SignalState.REACQUIRING,
                "et l'écran revient en réacquisition : 3 fixes exigés");

        // ============================================================
        section("18. Précision inutilisable = perte de signal");
        // ============================================================
        // Une précision de 500 m n'est pas « une position », c'est du bruit.
        // Elle doit être traitée exactement comme une perte.
        FixQuality.Tracker tUseless = new FixQuality.Tracker();
        for (int i = 0; i < 3; i++)
            tUseless.onFix(48.0 + i * 1e-4, 2.0, 5f, E0 + i * 1000L, E0 + i * 1000L);
        ok(tUseless.isOk(), "préalable OK");
        FixQuality.Decision dUseless = tUseless.onFix(48.001, 2.0, 500f,
                E0 + 4000, E0 + 4000);
        ok(!dUseless.accept && dUseless.reason == FixQuality.RejectReason.USELESS_ACCURACY,
                "500 m de précision -> refusé comme inutilisable");
        ok(!tUseless.shouldWrite(), "et rien n'est écrit");

        // ============================================================
        section("19. GPS désactivé : détection en 1,5 s, pas 5 s");
        // ============================================================
        FixQuality.Tracker tOff2 = new FixQuality.Tracker();
        tOff2.onFix(48.0, 2.0, 5f, E0, E0);
        // Le fournisseur vient d'être désactivé : le prochain tick doit
        // franchir le seuil accéléré.
        ok(tOff2.tick(E0 + 1600, false), "GPS coupé : perte à 1,6 s");
        ok(tOff2.isLost(), "état NO_SIGNAL");

        // ============================================================
        section("20. Antiméridien : distances ET vitesse");
        // ============================================================
        // Votre question : « il s'agit de calculer les distances ou vitesse
        // lorsque l'on passe par exemple un tour de +180 à -180 ? » OUI,
        // exactement. Et la vitesse implicite en dépend aussi.
        double lonA = 179.9995, lonB = -179.9995;   // 0,001° de part et d'autre
        double dAnti = FixGeometry.distanceMeters(48.0, lonA, 48.0, lonB);
        ok(dAnti > 50 && dAnti < 150,
                String.format("traversée de l'antiméridien : %.0f m (à 48°N)", dAnti));
        FixQuality.Decision dAntiV = FixQuality.acceptAsNextPoint(
                48.0, lonB, 5f, E0 + 3000, E0 + 3000, 48.0, lonA, E0);
        ok(dAntiV.accept, "le point de l'autre côté est accepté");
        ok(!Double.isNaN(dAntiV.impliedSpeedMps) && dAntiV.impliedSpeedMps < 100,
                String.format("vitesse = %.0f m/s : normale (pas 20 000 km/s)",
                        dAntiV.impliedSpeedMps));

        // ============================================================
        section("21. Satellites UTILISÉS : indicateur, pas filtre de rejet");
        // Le moteur GNSS Android a déjà résolu la position. GnssStatus peut
        // arriver en retard ou être incomplet : on expose le compte utilisé,
        // mais on ne jette pas un fix valide fourni par Location.
        ok(FixQuality.checkFix(48.85, 2.35, 5f, E0, E0, 3, true)
                == FixQuality.RejectReason.OK,
                "3 satellites utilisés : fix accepté, compte affichable en avertissement");
        ok(FixQuality.checkFix(48.85, 2.35, 5f, E0, E0, 4, true)
                == FixQuality.RejectReason.OK, "4 utilisés -> accepté");
        ok(FixQuality.checkFix(48.85, 2.35, 5f, E0, E0, 0, true)
                == FixQuality.RejectReason.OK,
                "0 = information indisponible -> aucune pénalité");
        ok(FixQuality.checkFix(48.85, 2.35, 5f, E0, E0, 12, true)
                == FixQuality.RejectReason.OK, "12 utilisés -> accepté");

        // ============================================================
        section("22. Fix 2D vs 3D : position horizontale conservée");
        // Le fix 2D est utile pour latitude/longitude. L'altitude est séparée
        // et l'interface la présente en ambre, sans supprimer la position.
        ok(FixQuality.checkFix(48.85, 2.35, 5f, E0, E0, 8, false)
                == FixQuality.RejectReason.OK,
                "fix 2D -> accepté horizontalement (altitude absente)");
        ok(FixQuality.checkFix(48.85, 2.35, 5f, E0, E0, 8, true)
                == FixQuality.RejectReason.OK, "fix 3D -> accepté");
        ok(FixQuality.checkFix(48.85, 2.35, 1f, E0, E0, 8, false)
                == FixQuality.RejectReason.OK,
                "fix 2D à précision annoncée de 1 m : reste accepté, altitude signalée absente");

        FixQuality.Tracker t2d = new FixQuality.Tracker();
        for (int i = 0; i < 4; i++)
            t2d.onFix(48.0 + i * 1e-4, 2.0, 5f, E0 + i * 1000L, E0 + i * 1000L, 8, false);
        ok(t2d.isOk() && t2d.shouldWrite(),
                "4 fixes 2D cohérents -> état OK et trace horizontale enregistrable");
        ok(t2d.lastReject() == FixQuality.RejectReason.OK,
                "raison = OK ; l'absence d'altitude reste un attribut distinct");

        // ============================================================
        section("23. Époque de trace : t = heure1 + (elapsed - t0)");
        // ============================================================
        FixQuality.Tracker tE = new FixQuality.Tracker();
        final long W1 = 1_766_000_000_000L;    // heure murale du 1er fix
        final long T0E = 9_000_000L;           // t0 monotone
        tE.beginEpoch(W1, T0E);
        ok(tE.isEpochSet(), "ancre établie");
        ok(tE.epochTimeMs(T0E) == W1, "à t0 : t = heure du premier fix");
        ok(tE.epochTimeMs(T0E + 1000) == W1 + 1000, "+1 s monotone -> +1 s écrit");
        ok(tE.epochTimeMs(T0E + 60_000) == W1 + 60_000, "+60 s -> +60 s");
        // L'horloge murale recule pendant la session : AUCUN effet.
        ok(tE.epochTimeMs(T0E + 61_000) == W1 + 61_000,
                "recul de l'horloge murale : temps de trace inchangé");
        // Deux points dans la même milliseconde
        long a = tE.epochTimeMs(T0E + 62_000);
        long b = tE.epochTimeMs(T0E + 62_000);
        ok(b == a + 1, "deux points dans la même ms -> +1 ms (GPX valide)");
        ok(tE.clampedTimes() == 1, "1 correction comptée");
        // Dérive
        ok(tE.driftMs(W1 + 61_000, T0E + 61_000) == 0, "dérive nulle : horloge stable");
        // Le téléphone a reculé de 2 s : la trace, elle, reste continue.
        long drift = tE.driftMs(W1 + 55_000, T0E + 61_000);
        ok(drift == 6000, "téléphone 6 s en arrière -> dérive mesurée de +6 s");
        ok(Math.abs(drift) > FixQuality.Tracker.EPOCH_DRIFT_WARN_MS == false,
                "6 s < seuil d'avertissement (5 min) : pas d'alerte inutile");

        // ============================================================
        section("24. Règle 3 + n constellations (correction utilisateur)");
        // ============================================================
        ok(FixQuality.minSatellitesFor(1) == 4, "1 constellation  -> 4 minimum");
        ok(FixQuality.minSatellitesFor(2) == 5, "2 constellations -> 5 minimum");
        ok(FixQuality.minSatellitesFor(3) == 6, "3 constellations -> 6 minimum");
        ok(FixQuality.minSatellitesFor(4) == 7, "4 constellations -> 7 minimum");
        ok(FixQuality.minSatellitesFor(0) == 4,
                "0 (inconnu) -> 4 : on ne retombe PAS sur 0, sinon tout passe");

        // Les cas du document, un par un.
        // 4 satellites GPS seuls : résoluble (4 inconnues, 4 équations).
        ok(FixQuality.checkFix(48.85, 2.35, 5f, E0, E0, 4, 1, true)
                == FixQuality.RejectReason.OK, "4 GPS seuls -> VALIDE");
        // Répartition multi-constellation : utile pour afficher une réserve
        // géométrique, mais ne remplace pas la validation du fix Android.
        ok(FixQuality.checkFix(48.85, 2.35, 5f, E0, E0, 4, 2, true)
                == FixQuality.RejectReason.OK,
                "3 GPS + 1 Galileo : fix Android conservé, réserve géométrique affichable");
        // 3 GPS + 3 Galileo : 6 satellites, 5 inconnues.
        ok(FixQuality.checkFix(48.85, 2.35, 5f, E0, E0, 6, 2, true)
                == FixQuality.RejectReason.OK, "3 GPS + 3 Galileo -> VALIDE");
        // 3 GPS + 1 Galileo + 1 GLONASS : 5 sats, 3 constellations -> 6 requis.
        ok(FixQuality.checkFix(48.85, 2.35, 5f, E0, E0, 5, 3, true)
                == FixQuality.RejectReason.OK,
                "3 GPS + 1 Gal + 1 Glo : fix Android conservé");
        // 2 + 2 + 2 : 6 satellites, 3 constellations.
        ok(FixQuality.checkFix(48.85, 2.35, 5f, E0, E0, 6, 3, true)
                == FixQuality.RejectReason.OK, "2 GPS + 2 Gal + 2 Glo -> VALIDE");

        // La règle théorique reste disponible pour un avertissement DOP :
        // aucun seuil satellite ne rejette la position déjà calculée par Android.
        ok(FixQuality.checkFix(48.85, 2.35, 5f, E0, E0, 5, 2, true)
                == FixQuality.RejectReason.OK, "5 sats / 2 constellations -> VALIDE");
        ok(FixQuality.checkFix(48.85, 2.35, 5f, E0, E0, 5, 3, true)
                == FixQuality.RejectReason.OK,
                "5 sats / 3 constellations -> fix conservé, seuil théorique non bloquant");

        // Le tracker expose le minimum courant, pour pouvoir l'AFFICHER.
        FixQuality.Tracker tMin = new FixQuality.Tracker();
        tMin.onFix(48.0, 2.0, 5f, E0, E0, 5, 3, true);
        ok(tMin.requiredSatellites() == 6, "tracker : exige 6 pour 3 constellations");
        ok(tMin.lastReject() == FixQuality.RejectReason.OK,
                "tracker : compte satellites exposé sans rejet du fix résolu");
        tMin.reset();
        tMin.onFix(48.0, 2.0, 5f, E0, E0, 4, 1, true);
        ok(tMin.requiredSatellites() == 4, "tracker : exige 4 pour 1 constellation");

        // Et la précision reste à 50 m : c'est un choix arbitraire assumé.
        ok(FixQuality.ACCURACY_RECORD_MAX_M == 50f, "précision max = 50 m (arbitraire)");
        ok(FixQuality.checkFix(48.85, 2.35, 50f, E0, E0, 6, 1, true)
                == FixQuality.RejectReason.OK, "50 m pile -> accepté (limite incluse)");

        total();
    }

    static boolean throwsIAE(double v) {
        try { FixGeometry.toMicro(v); return false; }
        catch (IllegalArgumentException e) { return true; }
    }

    static void section(String name) { System.out.println("\n=== " + name + " ==="); }

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
