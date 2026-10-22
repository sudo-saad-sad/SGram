package org.telegram.messenger.matrix;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class MatrixClient {

    private static volatile MatrixClient instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface MatrixCallback<T> {
        void onSuccess(T result);
        void onError(int code, String message);
    }

    public static class LoginResult {
        public String userId;
        public String accessToken;
        public String homeServer;
        public String deviceId;
        public String displayName;
    }

    public static class SyncResponse {
        public String nextBatch;
        public JSONObject fullJson;

        public SyncResponse(String nextBatch, JSONObject fullJson) {
            this.nextBatch = nextBatch;
            this.fullJson = fullJson;
        }
    }

    public static MatrixClient getInstance() {
        if (instance == null) {
            synchronized (MatrixClient.class) {
                if (instance == null) {
                    instance = new MatrixClient();
                }
            }
        }
        return instance;
    }

    private MatrixClient() {
    }

    // ---------------- Login ----------------

    public void login(String homeserver, String username, String password, MatrixCallback<LoginResult> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String normalizedHs = homeserver.trim();
                if (!normalizedHs.startsWith("http://") && !normalizedHs.startsWith("https://")) {
                    normalizedHs = "https://" + normalizedHs;
                }
                if (normalizedHs.contains("/_matrix")) {
                    normalizedHs = normalizedHs.substring(0, normalizedHs.indexOf("/_matrix"));
                }
                if (normalizedHs.endsWith("/")) {
                    normalizedHs = normalizedHs.substring(0, normalizedHs.length() - 1);
                }

                // Try resolving .well-known if it's a bare domain
                String discoveredHs = resolveWellKnown(normalizedHs);
                if (!TextUtils.isEmpty(discoveredHs)) {
                    normalizedHs = discoveredHs;
                }

                // Try v3 login, fallback to r0 on 404
                LoginAttempt attempt = executeLoginRequest(normalizedHs, "/_matrix/client/v3/login", username, password);
                if (attempt.statusCode == 404) {
                    attempt = executeLoginRequest(normalizedHs, "/_matrix/client/r0/login", username, password);
                }

                if (attempt.statusCode >= 200 && attempt.statusCode < 300) {
                    JSONObject json = new JSONObject(attempt.responseStr);
                    LoginResult result = new LoginResult();
                    result.userId = json.optString("user_id", username);
                    result.accessToken = json.optString("access_token", "");
                    result.homeServer = json.optString("home_server", normalizedHs);
                    result.deviceId = json.optString("device_id", "");
                    result.displayName = username;

                    postSuccess(callback, result);
                } else {
                    String errorMsg = "HTTP " + attempt.statusCode;
                    try {
                        JSONObject errJson = new JSONObject(attempt.responseStr);
                        if (errJson.has("error")) {
                            errorMsg = errJson.getString("error");
                        }
                    } catch (Exception ignore) {
                    }
                    postError(callback, attempt.statusCode, errorMsg);
                }
            } catch (Exception e) {
                FileLog.e("MatrixClient.login error", e);
                postError(callback, -1, e.getMessage() != null ? e.getMessage() : "Network error");
            }
        });
    }

    private static class LoginAttempt {
        int statusCode;
        String responseStr;
    }

    private String resolveWellKnown(String domainUrl) {
        try {
            URL url = new URL(domainUrl + "/.well-known/matrix/client");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() == 200) {
                String resp = readStream(conn.getInputStream());
                JSONObject json = new JSONObject(resp);
                JSONObject hsObj = json.optJSONObject("m.homeserver");
                if (hsObj != null && hsObj.has("base_url")) {
                    String baseUrl = hsObj.getString("base_url").trim();
                    if (baseUrl.endsWith("/")) {
                        baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
                    }
                    return baseUrl;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private LoginAttempt executeLoginRequest(String homeserver, String endpoint, String username, String password) throws Exception {
        URL url = new URL(homeserver + endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);

        JSONObject body = new JSONObject();
        body.put("type", "m.login.password");
        JSONObject identifier = new JSONObject();
        identifier.put("type", "m.id.user");
        identifier.put("user", username);
        body.put("identifier", identifier);
        body.put("password", password);
        body.put("initial_device_display_name", "SGram Android");

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
            os.flush();
        }

        LoginAttempt attempt = new LoginAttempt();
        attempt.statusCode = conn.getResponseCode();
        InputStream is = (attempt.statusCode >= 200 && attempt.statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
        attempt.responseStr = readStream(is);
        return attempt;
    }

    // ---------------- Sync ----------------

    public void sync(String homeserver, String accessToken, String sinceToken, int timeoutSec, MatrixCallback<SyncResponse> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                StringBuilder urlBuilder = new StringBuilder(homeserver);
                urlBuilder.append("/_matrix/client/v3/sync?timeout=").append(timeoutSec * 1000);
                if (!TextUtils.isEmpty(sinceToken)) {
                    urlBuilder.append("&since=").append(URLEncoder.encode(sinceToken, "UTF-8"));
                }

                URL url = new URL(urlBuilder.toString());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout((timeoutSec + 15) * 1000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Accept", "application/json");

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    String nextBatch = json.optString("next_batch", null);
                    postSuccess(callback, new SyncResponse(nextBatch, json));
                } else {
                    String errorMsg = "Sync failed with HTTP " + statusCode;
                    postError(callback, statusCode, errorMsg);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- Send Message ----------------

    public void sendMessage(String homeserver, String accessToken, String roomId, String text, String txnId, MatrixCallback<String> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                String encodedTxn = URLEncoder.encode(txnId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.room.message/" + encodedTxn);

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("msgtype", "m.text");
                body.put("body", text);

                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    String eventId = json.optString("event_id", null);
                    postSuccess(callback, eventId);
                } else {
                    postError(callback, statusCode, "Send message failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                FileLog.e("MatrixClient.sendMessage error", e);
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- Room Messages History ----------------

    public void loadRoomMessages(String homeserver, String accessToken, String roomId, String fromToken, int limit, MatrixCallback<JSONObject> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                StringBuilder urlBuilder = new StringBuilder(homeserver);
                urlBuilder.append("/_matrix/client/v3/rooms/").append(encodedRoom).append("/messages?dir=b&limit=").append(limit);
                if (!TextUtils.isEmpty(fromToken)) {
                    urlBuilder.append("&from=").append(URLEncoder.encode(fromToken, "UTF-8"));
                }

                URL url = new URL(urlBuilder.toString());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Accept", "application/json");

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    postSuccess(callback, json);
                } else {
                    postError(callback, statusCode, "Load messages failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- Read Receipt ----------------

    public void sendReadReceipt(String homeserver, String accessToken, String roomId, String eventId, MatrixCallback<Boolean> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                String encodedEvent = URLEncoder.encode(eventId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/receipt/m.read/" + encodedEvent);

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write("{}".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                if (statusCode >= 200 && statusCode < 300) {
                    postSuccess(callback, true);
                } else {
                    postError(callback, statusCode, "Read receipt failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- Create Rooms ----------------

    public void createDirectRoom(String homeserver, String accessToken, String peerUserId, MatrixCallback<String> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                URL url = new URL(homeserver + "/_matrix/client/v3/createRoom");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("is_direct", true);
                body.put("preset", "trusted_private_chat");
                JSONArray invite = new JSONArray();
                invite.put(peerUserId);
                body.put("invite", invite);

                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    String roomId = json.optString("room_id", null);
                    postSuccess(callback, roomId);
                } else {
                    postError(callback, statusCode, "Create direct room failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                FileLog.e("MatrixClient.createDirectRoom error", e);
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void createGroupRoom(String homeserver, String accessToken, String title, java.util.List<String> inviteUserIds, MatrixCallback<String> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                URL url = new URL(homeserver + "/_matrix/client/v3/createRoom");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("name", title);
                body.put("preset", "private_chat");
                body.put("is_direct", false);
                if (inviteUserIds != null && !inviteUserIds.isEmpty()) {
                    JSONArray invite = new JSONArray();
                    for (String uid : inviteUserIds) {
                        invite.put(uid);
                    }
                    body.put("invite", invite);
                }

                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    String roomId = json.optString("room_id", null);
                    postSuccess(callback, roomId);
                } else {
                    postError(callback, statusCode, "Create group room failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                FileLog.e("MatrixClient.createGroupRoom error", e);
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- Join Room ----------------

    public void joinRoom(String homeserver, String accessToken, String roomIdOrAlias, MatrixCallback<String> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedTarget = URLEncoder.encode(roomIdOrAlias, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/join/" + encodedTarget);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write("{}".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    String roomId = json.optString("room_id", roomIdOrAlias);
                    postSuccess(callback, roomId);
                } else {
                    postError(callback, statusCode, "Join room failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                FileLog.e("MatrixClient.joinRoom error", e);
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- User Search & Profiles ----------------

    public static class MatrixUser {
        public String userId;
        public String displayName;
        public String avatarUrl;

        public MatrixUser(String userId, String displayName, String avatarUrl) {
            this.userId = userId;
            this.displayName = displayName;
            this.avatarUrl = avatarUrl;
        }
    }

    public void searchUsers(String homeserver, String accessToken, String searchTerm, int limit, MatrixCallback<java.util.List<MatrixUser>> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                URL url = new URL(homeserver + "/_matrix/client/v3/user_directory/search");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("search_term", searchTerm);
                body.put("limit", Math.max(limit, 10));

                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    JSONArray results = json.optJSONArray("results");
                    java.util.List<MatrixUser> list = new java.util.ArrayList<>();
                    if (results != null) {
                        for (int i = 0; i < results.length(); i++) {
                            JSONObject u = results.getJSONObject(i);
                            String uid = u.optString("user_id", "");
                            String dn = u.optString("display_name", null);
                            String av = u.optString("avatar_url", null);
                            if (!TextUtils.isEmpty(uid)) {
                                list.add(new MatrixUser(uid, dn, av));
                            }
                        }
                    }
                    postSuccess(callback, list);
                } else {
                    postError(callback, statusCode, "Search users failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                FileLog.e("MatrixClient.searchUsers error", e);
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void getUserProfile(String homeserver, String accessToken, String userId, MatrixCallback<MatrixUser> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedUser = URLEncoder.encode(userId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/profile/" + encodedUser);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                if (!TextUtils.isEmpty(accessToken)) {
                    conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                }
                conn.setRequestProperty("Accept", "application/json");

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    String dn = json.optString("displayname", null);
                    String av = json.optString("avatar_url", null);
                    postSuccess(callback, new MatrixUser(userId, dn, av));
                } else {
                    postError(callback, statusCode, "Get profile failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                FileLog.e("MatrixClient.getUserProfile error", e);
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- Typing ----------------

    public void setTyping(String homeserver, String accessToken, String roomId, String userId, boolean typing, int timeoutMs, MatrixCallback<Boolean> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                String encodedUser = URLEncoder.encode(userId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/typing/" + encodedUser);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("typing", typing);
                if (typing) {
                    body.put("timeout", timeoutMs);
                }

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                if (statusCode >= 200 && statusCode < 300) {
                    postSuccess(callback, true);
                } else {
                    postError(callback, statusCode, "Set typing failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- Redactions (Delete Message) ----------------

    public void redactEvent(String homeserver, String accessToken, String roomId, String eventId, String txnId, String reason, MatrixCallback<String> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                String encodedEvent = URLEncoder.encode(eventId, "UTF-8");
                String encodedTxn = URLEncoder.encode(txnId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/redact/" + encodedEvent + "/" + encodedTxn);

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                if (!TextUtils.isEmpty(reason)) {
                    body.put("reason", reason);
                }

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    postSuccess(callback, json.optString("event_id", eventId));
                } else {
                    postError(callback, statusCode, "Redact failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- Edit Message ----------------

    public void editMessage(String homeserver, String accessToken, String roomId, String originalEventId, String newText, String txnId, MatrixCallback<String> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                String encodedTxn = URLEncoder.encode(txnId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.room.message/" + encodedTxn);

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("msgtype", "m.text");
                body.put("body", "* " + newText);

                JSONObject newContent = new JSONObject();
                newContent.put("msgtype", "m.text");
                newContent.put("body", newText);
                body.put("m.new_content", newContent);

                JSONObject relatesTo = new JSONObject();
                relatesTo.put("rel_type", "m.replace");
                relatesTo.put("event_id", originalEventId);
                body.put("m.relates_to", relatesTo);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    postSuccess(callback, json.optString("event_id", null));
                } else {
                    postError(callback, statusCode, "Edit message failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- Send Message With Reply ----------------

    public void sendMessageWithReply(String homeserver, String accessToken, String roomId, String text, String replyToEventId, String txnId, MatrixCallback<String> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                String encodedTxn = URLEncoder.encode(txnId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.room.message/" + encodedTxn);

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("msgtype", "m.text");
                body.put("body", text);

                if (!TextUtils.isEmpty(replyToEventId)) {
                    JSONObject inReplyTo = new JSONObject();
                    inReplyTo.put("event_id", replyToEventId);
                    JSONObject relatesTo = new JSONObject();
                    relatesTo.put("m.in_reply_to", inReplyTo);
                    body.put("m.relates_to", relatesTo);
                }

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    postSuccess(callback, json.optString("event_id", null));
                } else {
                    postError(callback, statusCode, "Send message failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                FileLog.e("MatrixClient.sendMessageWithReply error", e);
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- Account Data ----------------

    public void setAccountData(String homeserver, String accessToken, String userId, String type, JSONObject content, MatrixCallback<Boolean> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedUser = URLEncoder.encode(userId, "UTF-8");
                String encodedType = URLEncoder.encode(type, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/user/" + encodedUser + "/account_data/" + encodedType);

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(content.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                if (statusCode >= 200 && statusCode < 300) {
                    postSuccess(callback, true);
                } else {
                    postError(callback, statusCode, "Set account data failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- Media Upload ----------------

    public void uploadMedia(String homeserver, String accessToken, byte[] fileBytes, String mimeType, String filename, MatrixCallback<String> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                StringBuilder urlBuilder = new StringBuilder(homeserver);
                urlBuilder.append("/_matrix/media/v3/upload");
                if (!TextUtils.isEmpty(filename)) {
                    urlBuilder.append("?filename=").append(URLEncoder.encode(filename, "UTF-8"));
                }
                URL url = new URL(urlBuilder.toString());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", !TextUtils.isEmpty(mimeType) ? mimeType : "application/octet-stream");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(fileBytes);
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    String contentUri = json.optString("content_uri", null);
                    postSuccess(callback, contentUri);
                } else {
                    postError(callback, statusCode, "Upload media failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                FileLog.e("MatrixClient.uploadMedia error", e);
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- Send Media Message ----------------

    public void sendMediaMessage(String homeserver, String accessToken, String roomId, String mxcUrl, String msgType, String filename, String caption, String txnId, MatrixCallback<String> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                String encodedTxn = URLEncoder.encode(txnId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.room.message/" + encodedTxn);

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("msgtype", !TextUtils.isEmpty(msgType) ? msgType : "m.file");
                body.put("url", mxcUrl);
                body.put("body", !TextUtils.isEmpty(caption) ? caption : (filename != null ? filename : "file"));

                JSONObject info = new JSONObject();
                body.put("info", info);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    postSuccess(callback, json.optString("event_id", null));
                } else {
                    postError(callback, statusCode, "Send media message failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- Emoji Reactions ----------------

    public void sendReaction(String homeserver, String accessToken, String roomId, String targetEventId, String emoji, String txnId, MatrixCallback<String> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                String encodedTxn = URLEncoder.encode(txnId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.reaction/" + encodedTxn);

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                JSONObject relatesTo = new JSONObject();
                relatesTo.put("rel_type", "m.annotation");
                relatesTo.put("event_id", targetEventId);
                relatesTo.put("key", emoji);
                body.put("m.relates_to", relatesTo);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    postSuccess(callback, json.optString("event_id", null));
                } else {
                    postError(callback, statusCode, "Send reaction failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- Room State (Name, Topic, Pinned) ----------------

    public void setRoomState(String homeserver, String accessToken, String roomId, String eventType, String stateKey, JSONObject content, MatrixCallback<String> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                String encodedType = URLEncoder.encode(eventType, "UTF-8");
                String path = homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/state/" + encodedType;
                if (!TextUtils.isEmpty(stateKey)) {
                    path += "/" + URLEncoder.encode(stateKey, "UTF-8");
                }
                URL url = new URL(path);

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(content.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    postSuccess(callback, json.optString("event_id", null));
                } else {
                    postError(callback, statusCode, "Set room state failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- Room Membership (Invite, Kick, Leave, Forget) ----------------

    public void inviteUser(String homeserver, String accessToken, String roomId, String userId, MatrixCallback<Boolean> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/invite");

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("user_id", userId);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                if (statusCode >= 200 && statusCode < 300) {
                    postSuccess(callback, true);
                } else {
                    postError(callback, statusCode, "Invite user failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void kickUser(String homeserver, String accessToken, String roomId, String userId, String reason, MatrixCallback<Boolean> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/kick");

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("user_id", userId);
                if (!TextUtils.isEmpty(reason)) {
                    body.put("reason", reason);
                }

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                if (statusCode >= 200 && statusCode < 300) {
                    postSuccess(callback, true);
                } else {
                    postError(callback, statusCode, "Kick user failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void leaveRoom(String homeserver, String accessToken, String roomId, MatrixCallback<Boolean> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/leave");

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write("{}".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                if (statusCode >= 200 && statusCode < 300) {
                    postSuccess(callback, true);
                } else {
                    postError(callback, statusCode, "Leave room failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void forgetRoom(String homeserver, String accessToken, String roomId, MatrixCallback<Boolean> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/forget");

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write("{}".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                if (statusCode >= 200 && statusCode < 300) {
                    postSuccess(callback, true);
                } else {
                    postError(callback, statusCode, "Forget room failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- User Presence & Profile ----------------

    public void updatePresence(String homeserver, String accessToken, String userId, String presence, String statusMsg, MatrixCallback<Boolean> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedUser = URLEncoder.encode(userId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/presence/" + encodedUser + "/status");

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("presence", !TextUtils.isEmpty(presence) ? presence : "online");
                if (!TextUtils.isEmpty(statusMsg)) {
                    body.put("status_msg", statusMsg);
                }

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                if (statusCode >= 200 && statusCode < 300) {
                    postSuccess(callback, true);
                } else {
                    postError(callback, statusCode, "Update presence failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void updateProfileDisplayName(String homeserver, String accessToken, String userId, String displayName, MatrixCallback<Boolean> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedUser = URLEncoder.encode(userId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/profile/" + encodedUser + "/displayname");

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("displayname", displayName != null ? displayName : "");

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                if (statusCode >= 200 && statusCode < 300) {
                    postSuccess(callback, true);
                } else {
                    postError(callback, statusCode, "Update displayname failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void updateProfileAvatar(String homeserver, String accessToken, String userId, String avatarMxcUrl, MatrixCallback<Boolean> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedUser = URLEncoder.encode(userId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/profile/" + encodedUser + "/avatar_url");

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("avatar_url", avatarMxcUrl != null ? avatarMxcUrl : "");

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                if (statusCode >= 200 && statusCode < 300) {
                    postSuccess(callback, true);
                } else {
                    postError(callback, statusCode, "Update avatar failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void downloadMedia(String homeserver, String accessToken, String mxcUrl, MatrixCallback<byte[]> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (TextUtils.isEmpty(mxcUrl) || !mxcUrl.startsWith("mxc://")) {
                    postError(callback, 400, "Invalid mxc URL");
                    return;
                }
                String path = mxcUrl.substring(6);
                int slash = path.indexOf('/');
                if (slash <= 0) {
                    postError(callback, 400, "Invalid mxc URL format");
                    return;
                }
                String server = path.substring(0, slash);
                String mediaId = path.substring(slash + 1);

                String hs = homeserver;
                if (hs.endsWith("/")) {
                    hs = hs.substring(0, hs.length() - 1);
                }

                URL url = new URL(hs + "/_matrix/media/v3/download/" + server + "/" + mediaId);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                if (!TextUtils.isEmpty(accessToken)) {
                    conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                }

                int statusCode = conn.getResponseCode();
                if (statusCode >= 200 && statusCode < 300) {
                    try (InputStream is = conn.getInputStream();
                         java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
                        byte[] data = new byte[8192];
                        int nRead;
                        while ((nRead = is.read(data, 0, data.length)) != -1) {
                            buffer.write(data, 0, nRead);
                        }
                        postSuccess(callback, buffer.toByteArray());
                    }
                } else {
                    postError(callback, statusCode, "Download media failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                FileLog.e("MatrixClient.downloadMedia error", e);
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void downloadMediaThumbnail(String homeserver, String accessToken, String mxcUrl, int width, int height, String method, MatrixCallback<byte[]> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                if (TextUtils.isEmpty(mxcUrl) || !mxcUrl.startsWith("mxc://")) {
                    postError(callback, 400, "Invalid mxc URL");
                    return;
                }
                String path = mxcUrl.substring(6);
                int slash = path.indexOf('/');
                if (slash <= 0) {
                    postError(callback, 400, "Invalid mxc URL format");
                    return;
                }
                String server = path.substring(0, slash);
                String mediaId = path.substring(slash + 1);

                String hs = homeserver;
                if (hs.endsWith("/")) {
                    hs = hs.substring(0, hs.length() - 1);
                }

                String m = TextUtils.isEmpty(method) ? "scale" : method;
                URL url = new URL(hs + "/_matrix/media/v3/thumbnail/" + server + "/" + mediaId + "?width=" + width + "&height=" + height + "&method=" + m);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);
                if (!TextUtils.isEmpty(accessToken)) {
                    conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                }

                int statusCode = conn.getResponseCode();
                if (statusCode >= 200 && statusCode < 300) {
                    try (InputStream is = conn.getInputStream();
                         java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
                        byte[] data = new byte[8192];
                        int nRead;
                        while ((nRead = is.read(data, 0, data.length)) != -1) {
                            buffer.write(data, 0, nRead);
                        }
                        postSuccess(callback, buffer.toByteArray());
                    }
                } else {
                    postError(callback, statusCode, "Download thumbnail failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                FileLog.e("MatrixClient.downloadMediaThumbnail error", e);
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void banUser(String homeserver, String accessToken, String roomId, String userId, String reason, MatrixCallback<Boolean> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/ban");

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("user_id", userId);
                if (!TextUtils.isEmpty(reason)) {
                    body.put("reason", reason);
                }

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                if (statusCode >= 200 && statusCode < 300) {
                    postSuccess(callback, true);
                } else {
                    postError(callback, statusCode, "Ban user failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void unbanUser(String homeserver, String accessToken, String roomId, String userId, MatrixCallback<Boolean> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/unban");

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("user_id", userId);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                if (statusCode >= 200 && statusCode < 300) {
                    postSuccess(callback, true);
                } else {
                    postError(callback, statusCode, "Unban user failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public static class PublicRoom {
        public String roomId;
        public String name;
        public String topic;
        public String canonicalAlias;
        public int numJoinedMembers;
        public String avatarUrl;
    }

    public void searchPublicRooms(String homeserver, String accessToken, String searchTerm, int limit, MatrixCallback<java.util.List<PublicRoom>> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                URL url = new URL(homeserver + "/_matrix/client/v3/publicRooms");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                if (!TextUtils.isEmpty(accessToken)) {
                    conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                }
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                if (!TextUtils.isEmpty(searchTerm)) {
                    JSONObject filter = new JSONObject();
                    filter.put("generic_search_term", searchTerm);
                    body.put("filter", filter);
                }
                body.put("limit", limit > 0 ? limit : 20);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    JSONArray chunk = json.optJSONArray("chunk");
                    java.util.List<PublicRoom> rooms = new java.util.ArrayList<>();
                    if (chunk != null) {
                        for (int i = 0; i < chunk.length(); i++) {
                            JSONObject r = chunk.optJSONObject(i);
                            if (r != null) {
                                PublicRoom pr = new PublicRoom();
                                pr.roomId = r.optString("room_id");
                                pr.name = r.optString("name", "");
                                pr.topic = r.optString("topic", "");
                                pr.canonicalAlias = r.optString("canonical_alias", "");
                                pr.numJoinedMembers = r.optInt("num_joined_members", 0);
                                pr.avatarUrl = r.optString("avatar_url", null);
                                rooms.add(pr);
                            }
                        }
                    }
                    postSuccess(callback, rooms);
                } else {
                    postError(callback, statusCode, "Search public rooms failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                FileLog.e("MatrixClient.searchPublicRooms error", e);
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void sendMessageWithFormattedBody(String homeserver, String accessToken, String roomId, String plainText, String formattedHtml, String replyToEventId, String txnId, MatrixCallback<String> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                String encodedTxn = URLEncoder.encode(txnId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.room.message/" + encodedTxn);

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("msgtype", "m.text");
                body.put("body", plainText != null ? plainText : "");
                if (!TextUtils.isEmpty(formattedHtml)) {
                    body.put("format", "org.matrix.custom.html");
                    body.put("formatted_body", formattedHtml);
                }

                if (!TextUtils.isEmpty(replyToEventId)) {
                    JSONObject relatesTo = new JSONObject();
                    JSONObject inReplyTo = new JSONObject();
                    inReplyTo.put("event_id", replyToEventId);
                    relatesTo.put("m.in_reply_to", inReplyTo);
                    body.put("m.relates_to", relatesTo);
                }

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    postSuccess(callback, json.optString("event_id", null));
                } else {
                    postError(callback, statusCode, "Send formatted message failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void sendLocationMessage(String homeserver, String accessToken, String roomId, double lat, double lon, String description, String txnId, MatrixCallback<String> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                String encodedTxn = URLEncoder.encode(txnId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/m.room.message/" + encodedTxn);

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("msgtype", "m.location");
                body.put("geo_uri", "geo:" + lat + "," + lon);
                body.put("body", !TextUtils.isEmpty(description) ? description : ("Location: " + lat + ", " + lon));

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    postSuccess(callback, json.optString("event_id", null));
                } else {
                    postError(callback, statusCode, "Send location failed: HTTP " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void logout(String homeserver, String accessToken, MatrixCallback<Boolean> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                URL url = new URL(homeserver + "/_matrix/client/v3/logout");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write("{}".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                int statusCode = conn.getResponseCode();
                postSuccess(callback, statusCode >= 200 && statusCode < 300);
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void getMutualRooms(String homeserver, String accessToken, String targetUserId, MatrixCallback<java.util.List<String>> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedUser = URLEncoder.encode(targetUserId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v1/mutual_rooms?user_id=" + encodedUser);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Accept", "application/json");

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    JSONArray rooms = json.optJSONArray("joined_rooms");
                    java.util.List<String> list = new java.util.ArrayList<>();
                    if (rooms != null) {
                        for (int i = 0; i < rooms.length(); i++) {
                            list.add(rooms.getString(i));
                        }
                    }
                    postSuccess(callback, list);
                } else {
                    postError(callback, statusCode, "Get mutual rooms failed: " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void uploadKeys(String homeserver, String accessToken, JSONObject payload, MatrixCallback<JSONObject> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                URL url = new URL(homeserver + "/_matrix/client/v3/keys/upload");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    postSuccess(callback, json);
                } else {
                    postError(callback, statusCode, "Upload keys failed: " + statusCode + " " + responseStr);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void queryKeys(String homeserver, String accessToken, java.util.List<String> userIds, MatrixCallback<JSONObject> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                URL url = new URL(homeserver + "/_matrix/client/v3/keys/query");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                JSONObject deviceKeys = new JSONObject();
                if (userIds != null) {
                    for (String uid : userIds) {
                        deviceKeys.put(uid, new JSONArray());
                    }
                }
                body.put("device_keys", deviceKeys);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    postSuccess(callback, json);
                } else {
                    postError(callback, statusCode, "Query keys failed: " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void sendToDevice(String homeserver, String accessToken, String eventType, String txnId, JSONObject messages, MatrixCallback<Boolean> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedType = URLEncoder.encode(eventType, "UTF-8");
                String encodedTxn = URLEncoder.encode(txnId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/sendToDevice/" + encodedType + "/" + encodedTxn);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("messages", messages);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                postSuccess(callback, statusCode >= 200 && statusCode < 300);
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- Devices & Sessions Management ----------------

    public void getDevices(String homeserver, String accessToken, MatrixCallback<JSONArray> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                URL url = new URL(homeserver + "/_matrix/client/v3/devices");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Accept", "application/json");

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    JSONArray devices = json.optJSONArray("devices");
                    postSuccess(callback, devices != null ? devices : new JSONArray());
                } else {
                    postError(callback, statusCode, "Get devices failed: " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void deleteDevice(String homeserver, String accessToken, String deviceId, MatrixCallback<Boolean> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedDevice = URLEncoder.encode(deviceId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/devices/" + encodedDevice);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("DELETE");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Accept", "application/json");

                int statusCode = conn.getResponseCode();
                postSuccess(callback, statusCode >= 200 && statusCode < 300);
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- Read Markers ----------------

    public void setReadMarkers(String homeserver, String accessToken, String roomId, String fullyReadEventId, String readEventId, MatrixCallback<Boolean> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/read_markers");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                if (!TextUtils.isEmpty(fullyReadEventId)) {
                    body.put("m.fully_read", fullyReadEventId);
                }
                if (!TextUtils.isEmpty(readEventId)) {
                    body.put("m.read", readEventId);
                }

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                postSuccess(callback, statusCode >= 200 && statusCode < 300);
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- Polls (MSC3381) ----------------

    public void sendPoll(String homeserver, String accessToken, String roomId, String question, List<String> options, boolean multipleChoice, String txnId, MatrixCallback<String> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                String encodedTxn = URLEncoder.encode(txnId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/org.matrix.msc3381.poll.start/" + encodedTxn);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                StringBuilder fallback = new StringBuilder(question).append("\n");
                JSONArray answersArr = new JSONArray();
                for (int i = 0; i < options.size(); i++) {
                    String opt = options.get(i);
                    fallback.append(i + 1).append(". ").append(opt).append("\n");
                    JSONObject ansObj = new JSONObject();
                    ansObj.put("id", "ans_" + i);
                    JSONObject ansText = new JSONObject();
                    ansText.put("org.matrix.msc1767.text", opt);
                    ansObj.put("org.matrix.msc1767.text", opt);
                    answersArr.put(ansObj);
                }
                body.put("org.matrix.msc1767.text", fallback.toString());

                JSONObject pollStart = new JSONObject();
                JSONObject qObj = new JSONObject();
                qObj.put("org.matrix.msc1767.text", question);
                pollStart.put("question", qObj);
                pollStart.put("kind", "org.matrix.msc3381.poll.disclosed");
                pollStart.put("max_selections", multipleChoice ? options.size() : 1);
                pollStart.put("answers", answersArr);

                body.put("org.matrix.msc3381.poll.start", pollStart);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    postSuccess(callback, json.optString("event_id", null));
                } else {
                    postError(callback, statusCode, "Send poll failed: " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    public void sendPollVote(String homeserver, String accessToken, String roomId, String pollEventId, List<String> answerIds, String txnId, MatrixCallback<String> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedRoom = URLEncoder.encode(roomId, "UTF-8");
                String encodedTxn = URLEncoder.encode(txnId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/rooms/" + encodedRoom + "/send/org.matrix.msc3381.poll.response/" + encodedTxn);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                JSONObject relatesTo = new JSONObject();
                relatesTo.put("rel_type", "m.reference");
                relatesTo.put("event_id", pollEventId);
                body.put("m.relates_to", relatesTo);

                JSONObject respObj = new JSONObject();
                JSONArray ansArr = new JSONArray();
                if (answerIds != null) {
                    for (String a : answerIds) {
                        ansArr.put(a);
                    }
                }
                respObj.put("answers", ansArr);
                body.put("org.matrix.msc3381.poll.response", respObj);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    postSuccess(callback, json.optString("event_id", null));
                } else {
                    postError(callback, statusCode, "Send poll vote failed: " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- User Profile ----------------

    public void getUserProfile(String homeserver, String accessToken, String userId, MatrixCallback<JSONObject> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String encodedUser = URLEncoder.encode(userId, "UTF-8");
                URL url = new URL(homeserver + "/_matrix/client/v3/profile/" + encodedUser);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                if (!TextUtils.isEmpty(accessToken)) {
                    conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                }
                conn.setRequestProperty("Accept", "application/json");

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    postSuccess(callback, json);
                } else {
                    postError(callback, statusCode, "Get profile failed: " + statusCode);
                }
            } catch (Exception e) {
                postError(callback, -1, e.getMessage());
            }
        });
    }

    // ---------------- Helper Methods ----------------

    private String readStream(InputStream is) throws Exception {
        if (is == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();
        return sb.toString();
    }

    private <T> void postSuccess(MatrixCallback<T> callback, T result) {
        if (callback != null) {
            mainHandler.post(() -> callback.onSuccess(result));
        }
    }

    private <T> void postError(MatrixCallback<T> callback, int code, String message) {
        if (callback != null) {
            mainHandler.post(() -> callback.onError(code, message));
        }
    }
}
