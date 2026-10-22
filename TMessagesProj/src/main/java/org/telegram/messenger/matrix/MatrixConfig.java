package org.telegram.messenger.matrix;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import org.telegram.messenger.ApplicationLoader;

public class MatrixConfig {

    public static final String DEFAULT_HOMESERVER = "https://matrix.org";

    private static final String PREF_NAME_PREFIX = "matrix_config_";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_HOMESERVER = "homeserver";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_SYNC_BATCH = "sync_batch";
    private static final String KEY_DISPLAY_NAME = "display_name";

    private static SharedPreferences getPrefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME_PREFIX + account, Context.MODE_PRIVATE);
    }

    public static boolean isMatrixActive(int account) {
        return getPrefs(account).getBoolean(KEY_ACTIVE, false) && !TextUtils.isEmpty(getAccessToken(account));
    }

    public static String getHomeserver(int account) {
        String hs = getPrefs(account).getString(KEY_HOMESERVER, DEFAULT_HOMESERVER);
        if (TextUtils.isEmpty(hs)) {
            return DEFAULT_HOMESERVER;
        }
        return hs.endsWith("/") ? hs.substring(0, hs.length() - 1) : hs;
    }

    public static String getUserId(int account) {
        return getPrefs(account).getString(KEY_USER_ID, "");
    }

    public static String getAccessToken(int account) {
        return getPrefs(account).getString(KEY_ACCESS_TOKEN, "");
    }

    public static String getDeviceId(int account) {
        return getPrefs(account).getString(KEY_DEVICE_ID, "");
    }

    public static String getDisplayName(int account) {
        return getPrefs(account).getString(KEY_DISPLAY_NAME, "");
    }

    public static void setDisplayName(int account, String displayName) {
        getPrefs(account).edit().putString(KEY_DISPLAY_NAME, displayName).commit();
    }

    public static String getSyncBatch(int account) {
        return getPrefs(account).getString(KEY_SYNC_BATCH, null);
    }

    public static void setSyncBatch(int account, String nextBatch) {
        getPrefs(account).edit().putString(KEY_SYNC_BATCH, nextBatch).commit();
    }

    public static void saveSession(int account, String homeserver, String userId, String accessToken, String deviceId, String displayName) {
        if (homeserver.endsWith("/")) {
            homeserver = homeserver.substring(0, homeserver.length() - 1);
        }
        getPrefs(account).edit()
                .putBoolean(KEY_ACTIVE, true)
                .putString(KEY_HOMESERVER, homeserver)
                .putString(KEY_USER_ID, userId)
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_DEVICE_ID, deviceId != null ? deviceId : "")
                .putString(KEY_DISPLAY_NAME, displayName != null ? displayName : userId)
                .commit();
    }

    public static void clearSession(int account) {
        getPrefs(account).edit().clear().commit();
    }

    public static void clear(int account) {
        clearSession(account);
    }

    public static String getHttpUrlForMxc(String homeserver, String mxcUrl) {
        return getHttpThumbnailUrlForMxc(homeserver, mxcUrl, 160, 160);
    }

    public static String getHttpDownloadUrlForMxc(String homeserver, String mxcUrl) {
        if (TextUtils.isEmpty(mxcUrl) || !mxcUrl.startsWith("mxc://")) {
            return null;
        }
        String path = mxcUrl.substring(6);
        int slash = path.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        String server = path.substring(0, slash);
        String mediaId = path.substring(slash + 1);
        if (TextUtils.isEmpty(homeserver)) {
            homeserver = DEFAULT_HOMESERVER;
        }
        if (homeserver.endsWith("/")) {
            homeserver = homeserver.substring(0, homeserver.length() - 1);
        }
        return homeserver + "/_matrix/media/v3/download/" + server + "/" + mediaId;
    }

    public static String getHttpThumbnailUrlForMxc(String homeserver, String mxcUrl, int width, int height) {
        if (TextUtils.isEmpty(mxcUrl) || !mxcUrl.startsWith("mxc://")) {
            return null;
        }
        String path = mxcUrl.substring(6);
        int slash = path.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        String server = path.substring(0, slash);
        String mediaId = path.substring(slash + 1);
        if (TextUtils.isEmpty(homeserver)) {
            homeserver = DEFAULT_HOMESERVER;
        }
        if (homeserver.endsWith("/")) {
            homeserver = homeserver.substring(0, homeserver.length() - 1);
        }
        return homeserver + "/_matrix/media/v3/thumbnail/" + server + "/" + mediaId + "?width=" + width + "&height=" + height + "&method=scale";
    }
}
