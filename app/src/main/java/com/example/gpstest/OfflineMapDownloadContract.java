package com.example.gpstest;

/** Contrat persistant partagé entre DownloadManager, le receiver, WorkManager et l'UI. */
public final class OfflineMapDownloadContract {
    public static final String PREFS = "offline_map_download_state";
    public static final String KEY_ID = "download_id";
    public static final String KEY_REGION = "region_id";
    public static final String KEY_NAME = "display_name";
    public static final String KEY_PATH = "relative_path";
    public static final String KEY_VERSION = "source_version";
    public static final String KEY_RESULT_SUCCESS = "last_result_success";
    public static final String KEY_RESULT_MESSAGE = "last_result_message";
    public static final String KEY_RESULT_TIME = "last_result_time";
    public static final String INPUT_DOWNLOAD_ID = "download_id";
    public static final String STAGING_DIR = "offline-map-downloads";
    public static final String UNIQUE_WORK_PREFIX = "offline-map-install-";
    public static final String ACTION_MAPS_UPDATED = "com.example.gpstest.ACTION_OFFLINE_MAPS_UPDATED";
    public static final String KEY_MAPS_REVISION = "maps_revision";
    public static final String KEY_INSTALLING = "installing_map";

    private OfflineMapDownloadContract() { }

    /** Révision monotone utilisée pour rafraîchir les MapFile après install/suppression. */
    public static long nextMapsRevision(long current) {
        if (current < 0L || current == Long.MAX_VALUE) return 1L;
        return current + 1L;
    }

    public static String uniqueWorkName(long downloadId) {
        return UNIQUE_WORK_PREFIX + downloadId;
    }
}
