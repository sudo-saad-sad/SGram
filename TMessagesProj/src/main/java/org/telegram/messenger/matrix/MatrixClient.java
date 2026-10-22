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
                if (normalizedHs.endsWith("/")) {
                    normalizedHs = normalizedHs.substring(0, normalizedHs.length() - 1);
                }

                URL url = new URL(normalizedHs + "/_matrix/client/v3/login");
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

                int statusCode = conn.getResponseCode();
                InputStream is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                String responseStr = readStream(is);

                if (statusCode >= 200 && statusCode < 300) {
                    JSONObject json = new JSONObject(responseStr);
                    LoginResult result = new LoginResult();
                    result.userId = json.optString("user_id", username);
                    result.accessToken = json.optString("access_token", "");
                    result.homeServer = json.optString("home_server", normalizedHs);
                    result.deviceId = json.optString("device_id", "");
                    result.displayName = username;

                    postSuccess(callback, result);
                } else {
                    String errorMsg = "HTTP " + statusCode;
                    try {
                        JSONObject errJson = new JSONObject(responseStr);
                        if (errJson.has("error")) {
                            errorMsg = errJson.getString("error");
                        }
                    } catch (Exception ignore) {
                    }
                    postError(callback, statusCode, errorMsg);
                }
            } catch (Exception e) {
                FileLog.e("MatrixClient.login error", e);
                postError(callback, -1, e.getMessage() != null ? e.getMessage() : "Network error");
            }
        });
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
