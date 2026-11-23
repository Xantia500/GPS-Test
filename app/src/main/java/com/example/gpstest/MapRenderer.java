package com.example.gpstest;

import android.view.View;

import java.util.List;

/** Contrat des moteurs cartographiques strictement locaux.
 * Mapsforge affiche les régions .map installées ; le canevas local est le repli
 * sans fond cartographique, conservant position et traces sans tuiles réseau.
 */
public interface MapRenderer {
    View getView();
    boolean isOffline();
    int getInstalledRegionCount();
    boolean coversCoordinate(double latitude, double longitude);
    void setMyLocation(MapPoint point, boolean fresh);
    void animateTo(MapPoint point);
    /** Applique un thème si le moteur propose des styles vectoriels. */
    void setMapStyle(MapStyle style);
    /** Oriente la carte en degrés ; zéro conserve le nord en haut. */
    void setMapOrientation(float degrees);
    /** Signale un geste manuel sur la carte afin de suspendre le recentrage automatique. */
    void setOnUserInteractionListener(Runnable listener);
    void replaceTrace(List<List<MapPoint>> segments, MapPoint lastPoint, String title,
                      double minLatitude, double maxLatitude,
                      double westLongitude, double eastLongitude,
                      double centerLongitude, boolean pointLike,
                      int color, float strokeWidthPx);
    void onResume();
    void onPause();
    void destroy();
}
