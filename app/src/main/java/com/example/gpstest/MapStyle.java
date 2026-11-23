package com.example.gpstest;

/** Styles cartographiques réellement livrés avec Mapsforge 0.25.0. */
public enum MapStyle {
    STANDARD("standard", "Standard"),
    HIKING("hiking", "Vélo / randonnée"),
    MOTORIDER("motorider", "Moto"),
    CLASSIC("classic", "OSM classique");

    public final String id;
    public final String label;

    MapStyle(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public static MapStyle fromId(String id) {
        if (id != null) {
            for (MapStyle style : values()) if (style.id.equals(id)) return style;
        }
        return STANDARD;
    }
}
