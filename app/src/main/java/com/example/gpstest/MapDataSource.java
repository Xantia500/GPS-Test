package com.example.gpstest;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Frontière entre les données cartographiques locales et le moteur de rendu.
 *
 * L'interface ne dépend volontairement pas de Mapsforge, osmdroid ou d'un
 * fournisseur réseau. Un moteur peut donc être remplacé sans toucher à la
 * position GNSS, au parsing GPX ou à la gestion des fichiers installés.
 */
public interface MapDataSource {

    /** Retourne les cartes installées et encore valides, triées par libellé. */
    List<OfflineMapRepository.InstalledMap> listInstalledMaps() throws IOException;

    /** Retourne le fichier privé d'une région, ou lève une IOException si absent/invalide. */
    File getMapFile(String regionId) throws IOException;
}
