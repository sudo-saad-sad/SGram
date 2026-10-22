package org.telegram.messenger.matrix;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import org.telegram.messenger.ApplicationLoader;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class MatrixIdMapper {

    private static final String PREF_PREFIX = "matrix_ids_";
    private static volatile MatrixIdMapper[] instances = new MatrixIdMapper[4];

    private final int currentAccount;
    private final SharedPreferences prefs;

    // In-memory caches for fast lookup
    private final Map<String, Long> userToNumericCache = new HashMap<>();
    private final Map<Long, String> numericToUserCache = new HashMap<>();
    private final Map<String, Long> roomToNumericCache = new HashMap<>();
    private final Map<Long, String> numericToRoomCache = new HashMap<>();
    private final Map<Long, String> dmUserToRoomCache = new HashMap<>();
    private final Map<String, Long> dmRoomToUserCache = new HashMap<>();
    private final Map<String, Integer> eventToNumericCache = new HashMap<>();
    private final Map<String, String> numericToEventCache = new HashMap<>();

    private final AtomicInteger txnCounter = new AtomicInteger(1);

    public static MatrixIdMapper getInstance(int account) {
        if (account < 0 || account >= instances.length) {
            account = 0;
        }
        MatrixIdMapper local = instances[account];
        if (local == null) {
            synchronized (MatrixIdMapper.class) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new MatrixIdMapper(account);
                }
            }
        }
        return local;
    }

    private MatrixIdMapper(int account) {
        this.currentAccount = account;
        this.prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREF_PREFIX + account, Context.MODE_PRIVATE);
    }

    public SharedPreferences getPrefs() {
        return prefs;
    }

    // ---------------- User ID Mappings ----------------

    public synchronized long getNumericUserId(String matrixUserId) {
        if (TextUtils.isEmpty(matrixUserId)) {
            return 0;
        }
        Long cached = userToNumericCache.get(matrixUserId);
        if (cached != null) {
            return cached;
        }
        String key = "u_" + matrixUserId;
        long id = prefs.getLong(key, 0);
        if (id == 0) {
            long nextId = prefs.getLong("seq_user_id", 100_000_001L);
            id = nextId;
            prefs.edit()
                    .putLong(key, id)
                    .putString("ru_" + id, matrixUserId)
                    .putLong("seq_user_id", nextId + 1)
                    .apply();
        }
        userToNumericCache.put(matrixUserId, id);
        numericToUserCache.put(id, matrixUserId);
        return id;
    }

    public synchronized String getMatrixUserId(long numericUserId) {
        if (numericUserId <= 0) {
            return null;
        }
        String cached = numericToUserCache.get(numericUserId);
        if (cached != null) {
            return cached;
        }
        String val = prefs.getString("ru_" + numericUserId, null);
        if (val != null) {
            userToNumericCache.put(val, numericUserId);
            numericToUserCache.put(numericUserId, val);
        }
        return val;
    }

    // ---------------- Room ID Mappings ----------------

    public synchronized long getNumericChatId(String matrixRoomId) {
        if (TextUtils.isEmpty(matrixRoomId)) {
            return 0;
        }
        Long cached = roomToNumericCache.get(matrixRoomId);
        if (cached != null) {
            return cached;
        }
        String key = "r_" + matrixRoomId;
        long id = prefs.getLong(key, 0);
        if (id == 0) {
            long nextId = prefs.getLong("seq_chat_id", 200_000_001L);
            id = nextId;
            prefs.edit()
                    .putLong(key, id)
                    .putString("rr_" + id, matrixRoomId)
                    .putLong("seq_chat_id", nextId + 1)
                    .apply();
        }
        roomToNumericCache.put(matrixRoomId, id);
        numericToRoomCache.put(id, matrixRoomId);
        return id;
    }

    public synchronized String getMatrixRoomId(long numericChatId) {
        if (numericChatId <= 0) {
            return null;
        }
        String cached = numericToRoomCache.get(numericChatId);
        if (cached != null) {
            return cached;
        }
        String val = prefs.getString("rr_" + numericChatId, null);
        if (val != null) {
            roomToNumericCache.put(val, numericChatId);
            numericToRoomCache.put(numericChatId, val);
        }
        return val;
    }

    public synchronized void registerDirectRoom(String matrixRoomId, long peerNumericUserId) {
        if (TextUtils.isEmpty(matrixRoomId) || peerNumericUserId <= 0) {
            return;
        }
        dmUserToRoomCache.put(peerNumericUserId, matrixRoomId);
        dmRoomToUserCache.put(matrixRoomId, peerNumericUserId);
        prefs.edit()
                .putString("dm_u_" + peerNumericUserId, matrixRoomId)
                .putLong("dm_r_" + matrixRoomId, peerNumericUserId)
                .apply();
    }

    public synchronized boolean isDirectRoom(String matrixRoomId) {
        if (TextUtils.isEmpty(matrixRoomId)) {
            return false;
        }
        if (dmRoomToUserCache.containsKey(matrixRoomId)) {
            return true;
        }
        return prefs.contains("dm_r_" + matrixRoomId);
    }

    public synchronized long getDirectRoomPeerUser(String matrixRoomId) {
        Long cached = dmRoomToUserCache.get(matrixRoomId);
        if (cached != null) {
            return cached;
        }
        long id = prefs.getLong("dm_r_" + matrixRoomId, 0);
        if (id > 0) {
            dmRoomToUserCache.put(matrixRoomId, id);
        }
        return id;
    }

    public synchronized String getMatrixRoomIdForDialog(long dialogId) {
        if (dialogId < 0) {
            // Group chat: dialogId = -chatId
            return getMatrixRoomId(-dialogId);
        } else if (dialogId > 0) {
            // DM chat: dialogId = userId
            String cached = dmUserToRoomCache.get(dialogId);
            if (cached != null) {
                return cached;
            }
            String val = prefs.getString("dm_u_" + dialogId, null);
            if (val != null) {
                dmUserToRoomCache.put(dialogId, val);
            }
            return val;
        }
        return null;
    }

    public synchronized void setUserNameAndAvatar(long numericUserId, String displayName, String avatarUrl) {
        if (numericUserId <= 0) return;
        SharedPreferences.Editor edit = prefs.edit();
        if (displayName != null) {
            edit.putString("dn_" + numericUserId, displayName);
        }
        if (avatarUrl != null) {
            edit.putString("av_" + numericUserId, avatarUrl);
        }
        edit.apply();
    }

    public synchronized String getUserDisplayName(long numericUserId) {
        return prefs.getString("dn_" + numericUserId, null);
    }

    public synchronized String getUserAvatarUrl(long numericUserId) {
        return prefs.getString("av_" + numericUserId, null);
    }

    public synchronized java.util.List<Long> getAllDirectUserIds() {
        java.util.List<Long> list = new java.util.ArrayList<>();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("dm_u_")) {
                try {
                    long uid = Long.parseLong(key.substring(5));
                    if (uid > 0 && !list.contains(uid)) {
                        list.add(uid);
                    }
                } catch (Exception ignore) {}
            }
        }
        return list;
    }

    // ---------------- Event ID Mappings ----------------

    public synchronized int getNumericMessageId(String matrixRoomId, String eventId) {
        if (TextUtils.isEmpty(matrixRoomId) || TextUtils.isEmpty(eventId)) {
            return 0;
        }
        String cacheKey = matrixRoomId + ":" + eventId;
        Integer cached = eventToNumericCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String key = "e_" + cacheKey;
        int id = prefs.getInt(key, 0);
        if (id == 0) {
            int nextId = prefs.getInt("seq_msg_" + matrixRoomId, 1);
            id = nextId;
            prefs.edit()
                    .putInt(key, id)
                    .putString("re_" + matrixRoomId + ":" + id, eventId)
                    .putInt("seq_msg_" + matrixRoomId, nextId + 1)
                    .apply();
        }
        eventToNumericCache.put(cacheKey, id);
        numericToEventCache.put(matrixRoomId + ":" + id, eventId);
        return id;
    }

    public synchronized String getMatrixEventId(String matrixRoomId, int numericMsgId) {
        if (TextUtils.isEmpty(matrixRoomId) || numericMsgId <= 0) {
            return null;
        }
        String cacheKey = matrixRoomId + ":" + numericMsgId;
        String cached = numericToEventCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String val = prefs.getString("re_" + cacheKey, null);
        if (val != null) {
            numericToEventCache.put(cacheKey, val);
            eventToNumericCache.put(matrixRoomId + ":" + val, numericMsgId);
        }
        return val;
    }

    public String getNextTxnId() {
        return "m" + System.currentTimeMillis() + "_" + txnCounter.getAndIncrement();
    }
}
