package com.muse.monetnotes;

import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 单篇笔记加密：每篇笔记一个密码，PBKDF2-HmacSHA256（12 万轮）派生 AES-256 密钥，
 * AES-GCM 加密载荷（标题/正文/清单/标签）。盐与密文随笔记存进 notes.json，
 * 因此云备份/换机恢复后凭密码仍可解开（不依赖设备密钥库）。
 * 派生密钥只缓存在内存里（按笔记 id），进程结束即消失，笔记回到锁定状态。
 */
public class NoteCrypto {
    private static final int ITERATIONS = 120000;
    private static final Map<String, SecretKey> KEYS = new HashMap<>();
    private static final Map<String, String> LAST_HASH = new HashMap<>();

    public static boolean hasKey(String noteId) {
        return KEYS.containsKey(noteId);
    }

    public static void clearKey(String noteId) {
        KEYS.remove(noteId);
        LAST_HASH.remove(noteId);
    }

    /** 复制笔记时把会话密钥一并共享给副本（同盐同密码，派生密钥相同）。 */
    public static void shareKey(String fromId, String toId) {
        SecretKey k = KEYS.get(fromId);
        if (k != null) KEYS.put(toId, k);
    }

    private static SecretKey derive(char[] password, byte[] salt) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        return new SecretKeySpec(f.generateSecret(spec).getEncoded(), "AES");
    }

    /** 首次给笔记设置密码：生成盐、派生并缓存密钥。之后由 Note.toJson 完成加密落盘。 */
    public static boolean setupPassword(Note n, String password) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            n.encSalt = Base64.encodeToString(salt, Base64.NO_WRAP);
            KEYS.put(n.id, derive(password.toCharArray(), salt));
            LAST_HASH.remove(n.id);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 输入密码解锁：派生密钥试解，GCM 校验通过才算密码正确；成功后回填明文并缓存密钥。 */
    public static boolean unlock(Note n, String password) {
        try {
            byte[] salt = Base64.decode(n.encSalt, Base64.NO_WRAP);
            SecretKey key = derive(password.toCharArray(), salt);
            String plain = decryptRaw(key, n.encData);
            if (plain == null) return false;
            n.applyPayload(new JSONObject(plain));
            KEYS.put(n.id, key);
            LAST_HASH.put(n.id, fingerprintHash(n));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 用已缓存的会话密钥解开任意一份密文（历史版本预览用；盐不同/无密钥时返回 null）。 */
    public static org.json.JSONObject decryptEntry(String noteId, String data) {
        SecretKey key = KEYS.get(noteId);
        if (key == null) return null;
        String plain = decryptRaw(key, data);
        if (plain == null) return null;
        try {
            return new org.json.JSONObject(plain);
        } catch (Exception e) {
            return null;
        }
    }

    /** 已有会话密钥时直接解密回填（Note.fromJson 在恢复快照等场景调用）。 */
    public static boolean tryDecryptInPlace(Note n) {
        SecretKey key = KEYS.get(n.id);
        if (key == null || n.encData == null || n.encData.isEmpty()) return false;
        String plain = decryptRaw(key, n.encData);
        if (plain == null) return false;
        try {
            n.applyPayload(new JSONObject(plain));
        } catch (Exception e) {
            return false;
        }
        LAST_HASH.put(n.id, fingerprintHash(n));
        return true;
    }

    /**
     * Note.toJson 保存加密笔记时调用：明文与上次加密时相同则原样返回旧密文
     * （保证撤销快照的 JSON 稳定、不重复生成）；变了才用新 IV 重新加密。
     */
    public static String encrypt(Note n) {
        try {
            SecretKey key = KEYS.get(n.id);
            if (key == null) return null;
            String h = fingerprintHash(n);
            if (h.equals(LAST_HASH.get(n.id)) && n.encData != null && !n.encData.isEmpty()) {
                return n.encData;
            }
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(n.payloadJson().toString().getBytes(StandardCharsets.UTF_8));
            byte[] blob = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(ct, 0, blob, iv.length, ct.length);
            LAST_HASH.put(n.id, h);
            return Base64.encodeToString(blob, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private static String decryptRaw(SecretKey key, String data) {
        try {
            if (data == null || data.isEmpty()) return null;
            byte[] blob = Base64.decode(data, Base64.NO_WRAP);
            if (blob.length <= 12) return null;
            byte[] iv = new byte[12];
            System.arraycopy(blob, 0, iv, 0, 12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] pt = c.doFinal(blob, 12, blob.length - 12);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static String fingerprintHash(Note n) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(n.payloadFingerprint().getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(d, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }
}
