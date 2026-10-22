package org.telegram.messenger.matrix;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class MatrixKeyManager {

    private static volatile MatrixKeyManager[] instances = new MatrixKeyManager[4];
    private final int currentAccount;
    private final SharedPreferences prefs;

    private static final String PREF_PREFIX = "matrix_e2ee_keys_";
    private static final String KEY_CURVE25519_PRIV = "curve25519_priv";
    private static final String KEY_CURVE25519_PUB = "curve25519_pub";
    private static final String KEY_ED25519_PRIV = "ed25519_priv";
    private static final String KEY_ED25519_PUB = "ed25519_pub";
    private static final String KEY_KEYS_UPLOADED = "keys_uploaded";

    // Curve25519 / Ed25519 Field Constants
    private static final BigInteger P = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger L = BigInteger.valueOf(2).pow(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger I = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P);
    private static final BigInteger BY = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
    private static final BigInteger BX = recoverX(BY);

    public static MatrixKeyManager getInstance(int account) {
        MatrixKeyManager local = instances[account];
        if (local == null) {
            synchronized (MatrixKeyManager.class) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new MatrixKeyManager(account);
                }
            }
        }
        return local;
    }

    private MatrixKeyManager(int account) {
        this.currentAccount = account;
        this.prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREF_PREFIX + account, Context.MODE_PRIVATE);
        ensureIdentityKeys();
    }

    private void ensureIdentityKeys() {
        if (!prefs.contains(KEY_CURVE25519_PRIV) || !prefs.contains(KEY_ED25519_PRIV)) {
            SecureRandom random = new SecureRandom();

            // 1. Generate Curve25519 keypair
            byte[] curvePriv = new byte[32];
            random.nextBytes(curvePriv);
            byte[] curvePub = x25519Public(curvePriv);

            // 2. Generate Ed25519 keypair
            byte[] edPriv = new byte[32];
            random.nextBytes(edPriv);
            byte[] edPub = ed25519Public(edPriv);

            prefs.edit()
                    .putString(KEY_CURVE25519_PRIV, Base64.encodeToString(curvePriv, Base64.NO_WRAP | Base64.NO_PADDING))
                    .putString(KEY_CURVE25519_PUB, Base64.encodeToString(curvePub, Base64.NO_WRAP | Base64.NO_PADDING))
                    .putString(KEY_ED25519_PRIV, Base64.encodeToString(edPriv, Base64.NO_WRAP | Base64.NO_PADDING))
                    .putString(KEY_ED25519_PUB, Base64.encodeToString(edPub, Base64.NO_WRAP | Base64.NO_PADDING))
                    .putBoolean(KEY_KEYS_UPLOADED, false)
                    .commit();
        }
    }

    public String getCurve25519PublicKey() {
        return prefs.getString(KEY_CURVE25519_PUB, "");
    }

    public String getEd25519PublicKey() {
        return prefs.getString(KEY_ED25519_PUB, "");
    }

    public byte[] getEd25519PrivateKey() {
        String priv = prefs.getString(KEY_ED25519_PRIV, null);
        return priv != null ? Base64.decode(priv, Base64.DEFAULT) : new byte[32];
    }

    public void initAndUploadKeys() {
        String homeserver = MatrixConfig.getHomeserver(currentAccount);
        String accessToken = MatrixConfig.getAccessToken(currentAccount);
        String userId = MatrixConfig.getUserId(currentAccount);
        String deviceId = MatrixConfig.getDeviceId(currentAccount);

        if (TextUtils.isEmpty(homeserver) || TextUtils.isEmpty(accessToken) || TextUtils.isEmpty(userId)) {
            return;
        }

        try {
            JSONObject body = new JSONObject();

            // 1. Device Keys
            JSONObject deviceKeys = new JSONObject();
            deviceKeys.put("user_id", userId);
            deviceKeys.put("device_id", deviceId);

            JSONArray algos = new JSONArray();
            algos.put("m.olm.v1.curve25519-aes-sha2");
            algos.put("m.megolm.v1.aes-sha2");
            deviceKeys.put("algorithms", algos);

            JSONObject keysMap = new JSONObject();
            keysMap.put("curve25519:" + deviceId, getCurve25519PublicKey());
            keysMap.put("ed25519:" + deviceId, getEd25519PublicKey());
            deviceKeys.put("keys", keysMap);

            String canonicalJson = canonicalizeJson(deviceKeys);
            String sig = Base64.encodeToString(ed25519Sign(getEd25519PrivateKey(), canonicalJson.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP | Base64.NO_PADDING);

            JSONObject deviceSig = new JSONObject();
            deviceSig.put("ed25519:" + deviceId, sig);
            JSONObject signatures = new JSONObject();
            signatures.put(userId, deviceSig);
            deviceKeys.put("signatures", signatures);

            body.put("device_keys", deviceKeys);

            // 2. One-Time Keys (Batch of 25 keys)
            JSONObject oneTimeKeys = new JSONObject();
            SecureRandom random = new SecureRandom();
            for (int i = 0; i < 25; i++) {
                String keyId = "k" + (System.currentTimeMillis() % 1000000) + "_" + i;
                byte[] otkPriv = new byte[32];
                random.nextBytes(otkPriv);
                byte[] otkPub = x25519Public(otkPriv);
                String pubB64 = Base64.encodeToString(otkPub, Base64.NO_WRAP | Base64.NO_PADDING);

                JSONObject keyObj = new JSONObject();
                keyObj.put("key", pubB64);

                String keyCanon = canonicalizeJson(keyObj);
                String otkSig = Base64.encodeToString(ed25519Sign(getEd25519PrivateKey(), keyCanon.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP | Base64.NO_PADDING);

                JSONObject otkUserSig = new JSONObject();
                otkUserSig.put("ed25519:" + deviceId, otkSig);
                JSONObject otkSigs = new JSONObject();
                otkSigs.put(userId, otkUserSig);
                keyObj.put("signatures", otkSigs);

                oneTimeKeys.put("signed_curve25519:" + keyId, keyObj);
            }
            body.put("one_time_keys", oneTimeKeys);

            MatrixClient.getInstance().uploadKeys(homeserver, accessToken, body, new MatrixClient.MatrixCallback<JSONObject>() {
                @Override
                public void onSuccess(JSONObject result) {
                    prefs.edit().putBoolean(KEY_KEYS_UPLOADED, true).commit();
                    FileLog.d("MatrixKeyManager device keys and one-time keys uploaded successfully for " + userId);
                }

                @Override
                public void onError(int code, String message) {
                    FileLog.e("MatrixKeyManager failed to upload keys: " + code + " " + message);
                }
            });

        } catch (Exception e) {
            FileLog.e("MatrixKeyManager upload keys error", e);
        }
    }

    // ---------------- Inbound Megolm Room Key Handling ----------------

    public void processToDeviceEvents(JSONArray events) {
        if (events == null || events.length() == 0) {
            return;
        }
        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.optJSONObject(i);
            if (ev == null) continue;

            String type = ev.optString("type");
            String sender = ev.optString("sender");
            JSONObject content = ev.optJSONObject("content");
            if (content == null) continue;

            if ("m.room_key".equals(type) || "m.forwarded_room_key".equals(type)) {
                String roomId = content.optString("room_id");
                String sessionId = content.optString("session_id");
                String sessionKey = content.optString("session_key");
                String algorithm = content.optString("algorithm", "m.megolm.v1.aes-sha2");

                if (!TextUtils.isEmpty(sessionId) && !TextUtils.isEmpty(sessionKey)) {
                    saveRoomKey(roomId, sessionId, sessionKey, sender, algorithm);
                    FileLog.d("MatrixKeyManager: stored room key for session " + sessionId + " in room " + roomId + " from " + sender);
                }
            }
        }
    }

    public void saveRoomKey(String roomId, String sessionId, String sessionKey, String sender, String algorithm) {
        try {
            JSONObject keyData = new JSONObject();
            keyData.put("room_id", roomId);
            keyData.put("session_id", sessionId);
            keyData.put("session_key", sessionKey);
            keyData.put("sender", sender);
            keyData.put("algorithm", algorithm);
            keyData.put("timestamp", System.currentTimeMillis());

            prefs.edit()
                    .putString("rk_" + sessionId, keyData.toString())
                    .putString("rk_room_" + roomId + "_" + sessionId, keyData.toString())
                    .commit();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public boolean hasRoomKey(String roomId, String sessionId) {
        if (TextUtils.isEmpty(sessionId)) return false;
        return prefs.contains("rk_" + sessionId) || prefs.contains("rk_room_" + roomId + "_" + sessionId);
    }

    public String getRoomKey(String roomId, String sessionId) {
        if (TextUtils.isEmpty(sessionId)) return null;
        String val = prefs.getString("rk_room_" + roomId + "_" + sessionId, null);
        if (val == null) {
            val = prefs.getString("rk_" + sessionId, null);
        }
        return val;
    }

    // ---------------- Cryptographic Math (RFC 7748 X25519 & RFC 8032 Ed25519) ----------------

    private static byte[] x25519Public(byte[] privateKey) {
        byte[] k = privateKey.clone();
        k[0] &= 248;
        k[31] &= 127;
        k[31] |= 64;

        BigInteger kNum = BigInteger.ZERO;
        for (int i = 0; i < 32; i++) {
            kNum = kNum.or(BigInteger.valueOf(k[i] & 0xFF).shiftLeft(i * 8));
        }

        BigInteger x_1 = BigInteger.valueOf(9);
        BigInteger x_2 = BigInteger.ONE;
        BigInteger z_2 = BigInteger.ZERO;
        BigInteger x_3 = BigInteger.valueOf(9);
        BigInteger z_3 = BigInteger.ONE;
        int swap = 0;

        for (int t = 254; t >= 0; t--) {
            int k_t = kNum.testBit(t) ? 1 : 0;
            swap ^= k_t;
            if (swap != 0) {
                BigInteger tx = x_2; x_2 = x_3; x_3 = tx;
                BigInteger tz = z_2; z_2 = z_3; z_3 = tz;
            }
            swap = k_t;

            BigInteger A = x_2.add(z_2).mod(P);
            BigInteger AA = A.multiply(A).mod(P);
            BigInteger B = x_2.subtract(z_2).mod(P);
            BigInteger BB = B.multiply(B).mod(P);
            BigInteger E = AA.subtract(BB).mod(P);
            BigInteger C = x_3.add(z_3).mod(P);
            BigInteger D = x_3.subtract(z_3).mod(P);
            BigInteger DA = D.multiply(A).mod(P);
            BigInteger CB = C.multiply(B).mod(P);

            x_3 = DA.add(CB).modPow(BigInteger.valueOf(2), P);
            z_3 = x_1.multiply(DA.subtract(CB).modPow(BigInteger.valueOf(2), P)).mod(P);
            x_2 = AA.multiply(BB).mod(P);
            z_2 = E.multiply(AA.add(BigInteger.valueOf(121665).multiply(E))).mod(P);
        }

        if (swap != 0) {
            BigInteger tx = x_2; x_2 = x_3; x_3 = tx;
            BigInteger tz = z_2; z_2 = z_3; z_3 = tz;
        }

        BigInteger pubX = x_2.multiply(z_2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P);
        return encodeFieldElement(pubX);
    }

    private static byte[] ed25519Public(byte[] secretKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            byte[] h = md.digest(secretKey);
            h[0] &= 248;
            h[31] &= 127;
            h[31] |= 64;

            BigInteger a = BigInteger.ZERO;
            for (int i = 0; i < 32; i++) {
                a = a.or(BigInteger.valueOf(h[i] & 0xFF).shiftLeft(i * 8));
            }

            BigInteger[] A = edwardsMul(BX, BY, a);
            return encodePoint(A[0], A[1]);
        } catch (Exception e) {
            return new byte[32];
        }
    }

    private static byte[] ed25519Sign(byte[] secretKey, byte[] message) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            byte[] h = md.digest(secretKey);
            byte[] aBytes = new byte[32];
            System.arraycopy(h, 0, aBytes, 0, 32);
            aBytes[0] &= 248;
            aBytes[31] &= 127;
            aBytes[31] |= 64;

            BigInteger a = BigInteger.ZERO;
            for (int i = 0; i < 32; i++) {
                a = a.or(BigInteger.valueOf(aBytes[i] & 0xFF).shiftLeft(i * 8));
            }
            BigInteger[] A = edwardsMul(BX, BY, a);
            byte[] A_bytes = encodePoint(A[0], A[1]);

            md.reset();
            md.update(h, 32, 32);
            md.update(message);
            byte[] rBytes = md.digest();

            BigInteger r = BigInteger.ZERO;
            for (int i = 0; i < rBytes.length; i++) {
                r = r.or(BigInteger.valueOf(rBytes[i] & 0xFF).shiftLeft(i * 8));
            }
            r = r.mod(L);

            BigInteger[] R = edwardsMul(BX, BY, r);
            byte[] R_bytes = encodePoint(R[0], R[1]);

            md.reset();
            md.update(R_bytes);
            md.update(A_bytes);
            md.update(message);
            byte[] kBytes = md.digest();

            BigInteger k = BigInteger.ZERO;
            for (int i = 0; i < kBytes.length; i++) {
                k = k.or(BigInteger.valueOf(kBytes[i] & 0xFF).shiftLeft(i * 8));
            }
            k = k.mod(L);

            BigInteger S = r.add(k.multiply(a)).mod(L);
            byte[] S_bytes = encodeFieldElement(S);

            byte[] signature = new byte[64];
            System.arraycopy(R_bytes, 0, signature, 0, 32);
            System.arraycopy(S_bytes, 0, signature, 32, 32);
            return signature;
        } catch (Exception e) {
            return new byte[64];
        }
    }

    private static BigInteger[] edwardsAdd(BigInteger x1, BigInteger y1, BigInteger x2, BigInteger y2) {
        BigInteger numX = x1.multiply(y2).add(x2.multiply(y1)).mod(P);
        BigInteger denX = BigInteger.ONE.add(D.multiply(x1).multiply(x2).multiply(y1).multiply(y2)).mod(P);
        BigInteger x3 = numX.multiply(denX.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P);

        BigInteger numY = y1.multiply(y2).add(x1.multiply(x2)).mod(P);
        BigInteger denY = BigInteger.ONE.subtract(D.multiply(x1).multiply(x2).multiply(y1).multiply(y2)).mod(P);
        BigInteger y3 = numY.multiply(denY.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P);

        return new BigInteger[]{x3, y3};
    }

    private static BigInteger[] edwardsMul(BigInteger px, BigInteger py, BigInteger e) {
        BigInteger rx = BigInteger.ZERO;
        BigInteger ry = BigInteger.ONE;
        BigInteger qx = px;
        BigInteger qy = py;

        int bits = e.bitLength();
        for (int i = 0; i < bits; i++) {
            if (e.testBit(i)) {
                BigInteger[] r = edwardsAdd(rx, ry, qx, qy);
                rx = r[0]; ry = r[1];
            }
            BigInteger[] q = edwardsAdd(qx, qy, qx, qy);
            qx = q[0]; qy = q[1];
        }
        return new BigInteger[]{rx, ry};
    }

    private static BigInteger recoverX(BigInteger y) {
        BigInteger xx = y.multiply(y).subtract(BigInteger.ONE)
                .multiply(D.multiply(y).multiply(y).add(BigInteger.ONE).modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P);
        BigInteger x = xx.modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P);
        if (!x.multiply(x).subtract(xx).mod(P).equals(BigInteger.ZERO)) {
            x = x.multiply(I).mod(P);
        }
        if (x.testBit(0)) {
            x = P.subtract(x);
        }
        return x;
    }

    private static byte[] encodePoint(BigInteger x, BigInteger y) {
        byte[] out = encodeFieldElement(y);
        if (x.testBit(0)) {
            out[31] |= (byte) 0x80;
        }
        return out;
    }

    private static byte[] encodeFieldElement(BigInteger val) {
        byte[] bytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            bytes[i] = (byte) (val.shiftRight(i * 8).byteValue() & 0xFF);
        }
        return bytes;
    }

    // ---------------- Canonical JSON Helper ----------------

    public static String canonicalizeJson(JSONObject obj) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        List<String> keys = new ArrayList<>();
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!"signatures".equals(k) && !"unsigned".equals(k)) {
                keys.add(k);
            }
        }
        Collections.sort(keys);
        boolean first = true;
        for (String key : keys) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escapeJson(key)).append("\":");
            Object val = obj.opt(key);
            if (val instanceof JSONObject) {
                sb.append(canonicalizeJson((JSONObject) val));
            } else if (val instanceof JSONArray) {
                sb.append(canonicalizeArray((JSONArray) val));
            } else if (val instanceof String) {
                sb.append('"').append(escapeJson((String) val)).append('"');
            } else {
                sb.append(val);
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static String canonicalizeArray(JSONArray arr) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < arr.length(); i++) {
            if (i > 0) sb.append(',');
            Object val = arr.opt(i);
            if (val instanceof JSONObject) {
                sb.append(canonicalizeJson((JSONObject) val));
            } else if (val instanceof JSONArray) {
                sb.append(canonicalizeArray((JSONArray) val));
            } else if (val instanceof String) {
                sb.append('"').append(escapeJson((String) val)).append('"');
            } else {
                sb.append(val);
            }
        }
        sb.append(']');
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
