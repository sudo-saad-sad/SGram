package org.telegram.messenger.matrix;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

public class MatrixKeyBackupManager {

    private static volatile MatrixKeyBackupManager[] instances = new MatrixKeyBackupManager[4];
    private final int currentAccount;
    private final SharedPreferences prefs;

    private static final String PREF_PREFIX = "matrix_key_backup_";
    private static final String KEY_VERSION = "backup_version";
    private static final String KEY_ALGORITHM = "backup_algorithm";
    private static final String KEY_PUBKEY = "backup_pubkey";
    private static final String KEY_KEYS_COUNT = "backup_keys_count";
    private static final String KEY_ACTIVE = "backup_active";
    private static final String KEY_STORED_RECOVERY_KEY = "stored_recovery_key";

    public static class BackupStatus {
        public boolean active;
        public String version;
        public String algorithm;
        public String publicKey;
        public int keysCount;
        public String etag;
    }

    public static MatrixKeyBackupManager getInstance(int account) {
        if (account < 0 || account >= instances.length) {
            account = 0;
        }
        MatrixKeyBackupManager local = instances[account];
        if (local == null) {
            synchronized (MatrixKeyBackupManager.class) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new MatrixKeyBackupManager(account);
                }
            }
        }
        return local;
    }

    private MatrixKeyBackupManager(int account) {
        this.currentAccount = account;
        this.prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREF_PREFIX + account, Context.MODE_PRIVATE);
    }

    public boolean isBackupActive() {
        return prefs.getBoolean(KEY_ACTIVE, false) && !TextUtils.isEmpty(prefs.getString(KEY_VERSION, null));
    }

    public String getBackupVersion() {
        return prefs.getString(KEY_VERSION, "");
    }

    public int getBackupKeysCount() {
        return prefs.getInt(KEY_KEYS_COUNT, 0);
    }

    public String getStoredRecoveryKey() {
        return prefs.getString(KEY_STORED_RECOVERY_KEY, null);
    }

    // ---------------- Check Status ----------------

    public void checkBackupStatus(MatrixClient.MatrixCallback<BackupStatus> callback) {
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        if (TextUtils.isEmpty(homeserver) || TextUtils.isEmpty(accessToken)) {
            if (callback != null) {
                callback.onError(-1, "Not logged in to Matrix");
            }
            return;
        }

        MatrixClient.getInstance().getKeyBackupVersion(homeserver, accessToken, new MatrixClient.MatrixCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject json) {
                try {
                    BackupStatus status = new BackupStatus();
                    status.version = json.optString("version", "1");
                    status.algorithm = json.optString("algorithm", "m.megolm_backup.v1.curve25519-aes-sha2");
                    status.keysCount = json.optInt("count", 0);
                    status.etag = json.optString("etag", "");
                    JSONObject authData = json.optJSONObject("auth_data");
                    if (authData != null) {
                        status.publicKey = authData.optString("public_key", "");
                    }
                    status.active = true;

                    prefs.edit()
                            .putBoolean(KEY_ACTIVE, true)
                            .putString(KEY_VERSION, status.version)
                            .putString(KEY_ALGORITHM, status.algorithm)
                            .putString(KEY_PUBKEY, status.publicKey != null ? status.publicKey : "")
                            .putInt(KEY_KEYS_COUNT, status.keysCount)
                            .commit();

                    if (callback != null) {
                        callback.onSuccess(status);
                    }
                } catch (Exception e) {
                    if (callback != null) {
                        callback.onError(-1, e.getMessage());
                    }
                }
            }

            @Override
            public void onError(int code, String message) {
                if (code == 404) {
                    prefs.edit().putBoolean(KEY_ACTIVE, false).remove(KEY_VERSION).commit();
                    BackupStatus status = new BackupStatus();
                    status.active = false;
                    if (callback != null) {
                        callback.onSuccess(status);
                    }
                } else {
                    if (callback != null) {
                        callback.onError(code, message);
                    }
                }
            }
        });
    }

    // ---------------- Create New Backup ----------------

    public void createKeyBackup(MatrixClient.MatrixCallback<String> callback) {
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        if (TextUtils.isEmpty(homeserver) || TextUtils.isEmpty(accessToken)) {
            if (callback != null) {
                callback.onError(-1, "Not logged in to Matrix");
            }
            return;
        }

        try {
            // 1. Generate 32-byte Curve25519 private key
            byte[] privKey = MatrixRecoveryKeyHelper.generateRandomPrivateKey();
            byte[] pubKey = MatrixKeyManager.x25519Public(privKey);

            String pubKeyB64 = Base64.encodeToString(pubKey, Base64.NO_WRAP | Base64.NO_PADDING);
            String recoveryKeyStr = MatrixRecoveryKeyHelper.encodeRecoveryKey(privKey);

            // 2. Prepare auth_data and sign
            JSONObject body = new JSONObject();
            body.put("algorithm", "m.megolm_backup.v1.curve25519-aes-sha2");

            JSONObject authData = new JSONObject();
            authData.put("public_key", pubKeyB64);
            MatrixKeyManager.getInstance(currentAccount).signJson(authData);

            body.put("auth_data", authData);

            // 3. Create key backup version on server
            MatrixClient.getInstance().createKeyBackupVersion(homeserver, accessToken, body, new MatrixClient.MatrixCallback<String>() {
                @Override
                public void onSuccess(String version) {
                    prefs.edit()
                            .putBoolean(KEY_ACTIVE, true)
                            .putString(KEY_VERSION, version)
                            .putString(KEY_ALGORITHM, "m.megolm_backup.v1.curve25519-aes-sha2")
                            .putString(KEY_PUBKEY, pubKeyB64)
                            .putString(KEY_STORED_RECOVERY_KEY, recoveryKeyStr)
                            .commit();

                    // Automatically backup any local keys
                    backupLocalKeys(null);

                    if (callback != null) {
                        callback.onSuccess(recoveryKeyStr);
                    }
                }

                @Override
                public void onError(int code, String message) {
                    if (callback != null) {
                        callback.onError(code, message);
                    }
                }
            });
        } catch (Exception e) {
            FileLog.e(e);
            if (callback != null) {
                callback.onError(-1, e.getMessage());
            }
        }
    }

    // ---------------- Restore Keys with Recovery Key ----------------

    public void restoreKeysWithRecoveryKey(String recoveryKeyInput, MatrixClient.MatrixCallback<Integer> callback) {
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);

        if (TextUtils.isEmpty(homeserver) || TextUtils.isEmpty(accessToken)) {
            if (callback != null) {
                callback.onError(-1, "Not logged in to Matrix");
            }
            return;
        }

        byte[] privKey = MatrixRecoveryKeyHelper.decodeRecoveryKey(recoveryKeyInput);
        if (privKey == null || privKey.length != 32) {
            if (callback != null) {
                callback.onError(400, "Invalid Recovery Key format. Please ensure you entered all blocks correctly.");
            }
            return;
        }

        // Fetch backup version to obtain the active version and public key
        MatrixClient.getInstance().getKeyBackupVersion(homeserver, accessToken, new MatrixClient.MatrixCallback<JSONObject>() {
            @Override
            public void onSuccess(JSONObject versionJson) {
                String version = versionJson.optString("version", "1");
                JSONObject authData = versionJson.optJSONObject("auth_data");
                String expectedPubB64 = authData != null ? authData.optString("public_key", "") : "";

                // Verify our derived public key matches the server's public key
                byte[] derivedPub = MatrixKeyManager.x25519Public(privKey);
                String derivedPubB64 = Base64.encodeToString(derivedPub, Base64.NO_WRAP | Base64.NO_PADDING);

                if (!TextUtils.isEmpty(expectedPubB64) && !expectedPubB64.equals(derivedPubB64)) {
                    if (callback != null) {
                        callback.onError(403, "Recovery Key does not match the active server backup. Please check your key.");
                    }
                    return;
                }

                // Download all room keys
                MatrixClient.getInstance().getKeyBackupKeys(homeserver, accessToken, version, new MatrixClient.MatrixCallback<JSONObject>() {
                    @Override
                    public void onSuccess(JSONObject keysJson) {
                        int restoredCount = decryptAndImportKeys(privKey, keysJson);

                        // Save key in prefs
                        prefs.edit()
                                .putBoolean(KEY_ACTIVE, true)
                                .putString(KEY_VERSION, version)
                                .putString(KEY_PUBKEY, derivedPubB64)
                                .putString(KEY_STORED_RECOVERY_KEY, recoveryKeyInput.trim())
                                .putInt(KEY_KEYS_COUNT, restoredCount)
                                .commit();

                        AndroidUtilities.runOnUIThread(() -> {
                            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.dialogsNeedReload);
                            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
                        });

                        if (callback != null) {
                            callback.onSuccess(restoredCount);
                        }
                    }

                    @Override
                    public void onError(int code, String message) {
                        if (callback != null) {
                            callback.onError(code, "Failed to download backup keys: " + message);
                        }
                    }
                });
            }

            @Override
            public void onError(int code, String message) {
                if (callback != null) {
                    callback.onError(code, "No active key backup found on server: " + message);
                }
            }
        });
    }

    // ---------------- Decryption Engine for Backup Keys ----------------

    private int decryptAndImportKeys(byte[] privKey, JSONObject keysJson) {
        int count = 0;
        try {
            JSONObject rooms = keysJson.optJSONObject("rooms");
            if (rooms == null) return 0;

            Iterator<String> roomIt = rooms.keys();
            while (roomIt.hasNext()) {
                String roomId = roomIt.next();
                JSONObject roomObj = rooms.optJSONObject(roomId);
                if (roomObj == null) continue;

                JSONObject sessions = roomObj.optJSONObject("sessions");
                if (sessions == null) continue;

                Iterator<String> sessionIt = sessions.keys();
                while (sessionIt.hasNext()) {
                    String sessionId = sessionIt.next();
                    JSONObject sessionObj = sessions.optJSONObject(sessionId);
                    if (sessionObj == null) continue;

                    JSONObject sessionData = sessionObj.optJSONObject("session_data");
                    if (sessionData == null) continue;

                    String ephemeralB64 = sessionData.optString("ephemeral");
                    String ciphertextB64 = sessionData.optString("ciphertext");
                    String macB64 = sessionData.optString("mac");

                    if (TextUtils.isEmpty(ephemeralB64) || TextUtils.isEmpty(ciphertextB64)) {
                        continue;
                    }

                    byte[] ephemeralPub = Base64.decode(ephemeralB64, Base64.DEFAULT);
                    byte[] ciphertext = Base64.decode(ciphertextB64, Base64.DEFAULT);

                    // 1. Compute ECDH shared secret
                    byte[] sharedSecret = MatrixKeyManager.x25519(privKey, ephemeralPub);

                    // 2. Derive AES and MAC keys using SHA-256 (or HKDF)
                    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                    byte[] keyMaterial = sha256.digest(sharedSecret);

                    // AES key = 32 bytes, IV = first 16 bytes of ephemeral
                    byte[] iv = new byte[16];
                    System.arraycopy(ephemeralPub, 0, iv, 0, 16);

                    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyMaterial, "AES"), new IvParameterSpec(iv));
                    byte[] decryptedBytes = cipher.doFinal(ciphertext);

                    String decryptedJsonStr = new String(decryptedBytes, StandardCharsets.UTF_8);
                    JSONObject decryptedJson = new JSONObject(decryptedJsonStr);

                    String sessionKey = decryptedJson.optString("session_key");
                    String algorithm = decryptedJson.optString("algorithm", "m.megolm.v1.aes-sha2");
                    String sender = decryptedJson.optString("sender_key", "");

                    if (!TextUtils.isEmpty(sessionKey)) {
                        MatrixKeyManager.getInstance(currentAccount).saveRoomKey(roomId, sessionId, sessionKey, sender, algorithm);
                        count++;
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e("decryptAndImportKeys error", e);
        }
        return count;
    }

    // ---------------- Backup Local Keys to Server ----------------

    public void backupLocalKeys(MatrixClient.MatrixCallback<Integer> callback) {
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);
        String version = getBackupVersion();
        String pubKeyB64 = prefs.getString(KEY_PUBKEY, null);

        if (TextUtils.isEmpty(homeserver) || TextUtils.isEmpty(accessToken) || TextUtils.isEmpty(version) || TextUtils.isEmpty(pubKeyB64)) {
            if (callback != null) {
                callback.onError(-1, "Key backup not configured");
            }
            return;
        }

        byte[] backupPub = Base64.decode(pubKeyB64, Base64.DEFAULT);
        if (backupPub == null || backupPub.length != 32) {
            if (callback != null) {
                callback.onError(-1, "Invalid backup public key");
            }
            return;
        }

        SharedPreferences keyPrefs = ApplicationLoader.applicationContext.getSharedPreferences("matrix_e2ee_keys_" + currentAccount, Context.MODE_PRIVATE);
        Map<String, ?> all = keyPrefs.getAll();
        int[] uploaded = new int[]{0};

        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (entry.getKey().startsWith("rk_room_")) {
                try {
                    String val = (String) entry.getValue();
                    JSONObject json = new JSONObject(val);
                    String roomId = json.optString("room_id");
                    String sessionId = json.optString("session_id");
                    String sessionKey = json.optString("session_key");
                    String sender = json.optString("sender");
                    String algo = json.optString("algorithm", "m.megolm.v1.aes-sha2");

                    if (!TextUtils.isEmpty(roomId) && !TextUtils.isEmpty(sessionId) && !TextUtils.isEmpty(sessionKey)) {
                        // Encrypt session key with server backup public key
                        JSONObject encryptedSession = encryptSessionForBackup(backupPub, roomId, sessionId, sessionKey, sender, algo);
                        if (encryptedSession != null) {
                            MatrixClient.getInstance().putRoomKeyBackup(homeserver, accessToken, version, roomId, sessionId, encryptedSession, null);
                            uploaded[0]++;
                        }
                    }
                } catch (Exception ignore) {}
            }
        }

        prefs.edit().putInt(KEY_KEYS_COUNT, uploaded[0]).commit();

        if (callback != null) {
            callback.onSuccess(uploaded[0]);
        }
    }

    private JSONObject encryptSessionForBackup(byte[] backupPub, String roomId, String sessionId, String sessionKey, String sender, String algo) {
        try {
            // Generate ephemeral Curve25519 keypair
            byte[] ephemeralPriv = MatrixRecoveryKeyHelper.generateRandomPrivateKey();
            byte[] ephemeralPub = MatrixKeyManager.x25519Public(ephemeralPriv);

            // Compute ECDH shared secret
            byte[] sharedSecret = MatrixKeyManager.x25519(ephemeralPriv, backupPub);

            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] keyMaterial = sha256.digest(sharedSecret);

            byte[] iv = new byte[16];
            System.arraycopy(ephemeralPub, 0, iv, 0, 16);

            JSONObject payload = new JSONObject();
            payload.put("algorithm", algo);
            payload.put("session_key", sessionKey);
            payload.put("sender_key", sender);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyMaterial, "AES"), new IvParameterSpec(iv));
            byte[] ciphertext = cipher.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));

            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(keyMaterial, "HmacSHA256"));
            byte[] mac = hmac.doFinal(ciphertext);

            JSONObject sessionData = new JSONObject();
            sessionData.put("ephemeral", Base64.encodeToString(ephemeralPub, Base64.NO_WRAP | Base64.NO_PADDING));
            sessionData.put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP | Base64.NO_PADDING));
            sessionData.put("mac", Base64.encodeToString(mac, Base64.NO_WRAP | Base64.NO_PADDING));

            JSONObject result = new JSONObject();
            result.put("first_message_index", 0);
            result.put("forwarded_count", 0);
            result.put("is_verified", false);
            result.put("session_data", sessionData);
            return result;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    // ---------------- Delete Backup ----------------

    public void deleteKeyBackup(MatrixClient.MatrixCallback<Boolean> callback) {
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);
        String version = getBackupVersion();

        if (TextUtils.isEmpty(homeserver) || TextUtils.isEmpty(accessToken) || TextUtils.isEmpty(version)) {
            prefs.edit().clear().commit();
            if (callback != null) {
                callback.onSuccess(true);
            }
            return;
        }

        MatrixClient.getInstance().deleteKeyBackupVersion(homeserver, accessToken, version, new MatrixClient.MatrixCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                prefs.edit().clear().commit();
                if (callback != null) {
                    callback.onSuccess(result);
                }
            }

            @Override
            public void onError(int code, String message) {
                prefs.edit().clear().commit();
                if (callback != null) {
                    callback.onSuccess(true);
                }
            }
        });
    }
}
