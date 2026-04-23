package com.map.sstp.ppp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * MS-CHAPv2 and MPPE derivation helpers based on RFC 2759 and RFC 3079.
 */
public final class MsChapV2Util {
    private static final byte[] AUTHENTICATOR_MAGIC_1 =
        "Magic server to client signing constant".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] AUTHENTICATOR_MAGIC_2 =
        "Pad to make it do more than one iteration".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MPPE_MAGIC_1 =
        "This is the MPPE Master Key".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MPPE_MAGIC_2 =
        "On the client side, this is the send key; on the server side, it is the receive key."
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MPPE_MAGIC_3 =
        "On the client side, this is the receive key; on the server side, it is the send key."
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SHS_PAD_1 = repeated((byte) 0x00, 40);
    private static final byte[] SHS_PAD_2 = repeated((byte) 0xF2, 40);

    private MsChapV2Util() {}

    public static MsChapV2Material deriveMaterial(
        String username,
        String password,
        byte[] authenticatorChallenge,
        byte[] peerChallenge
    ) throws IOException {
        validateChallenge(authenticatorChallenge, "authenticator");
        validateChallenge(peerChallenge, "peer");
        if (username == null || username.isEmpty()) {
            throw new IOException("MS-CHAPv2 username is required");
        }
        if (password == null || password.isEmpty()) {
            throw new IOException("MS-CHAPv2 password is required");
        }

        byte[] passwordHash = ntPasswordHash(password);
        byte[] passwordHashHash = hashNtPasswordHash(passwordHash);
        byte[] challenge = challengeHash(peerChallenge, authenticatorChallenge, username);
        byte[] ntResponse = challengeResponse(challenge, passwordHash);
        String authenticatorResponse = generateAuthenticatorResponse(
            password,
            ntResponse,
            peerChallenge,
            authenticatorChallenge,
            username
        );
        byte[] masterKey = getMasterKey(passwordHashHash, ntResponse);
        // Client keys: isServer must be false (RFC 3079 / MS-SSTP HLAK uses client send|receive keys).
        byte[] masterSendKey = getAsymmetricStartKey(masterKey, 16, true, false);
        byte[] masterReceiveKey = getAsymmetricStartKey(masterKey, 16, false, false);
        // MS-SSTP crypto binding: HLAK is MSK || MRK for the PPP client (see [MS-SSTP] Crypto Binding).
        byte[] higherLayerAuthKey = concatenate(masterSendKey, masterReceiveKey);

        return new MsChapV2Material(
            authenticatorChallenge,
            peerChallenge,
            challenge,
            ntResponse,
            authenticatorResponse,
            passwordHash,
            passwordHashHash,
            masterKey,
            masterSendKey,
            masterReceiveKey,
            higherLayerAuthKey
        );
    }

    public static byte[] ntPasswordHash(String password) {
        return Md4.hash(password.getBytes(StandardCharsets.UTF_16LE));
    }

    public static byte[] hashNtPasswordHash(byte[] passwordHash) {
        return Md4.hash(passwordHash);
    }

    public static byte[] challengeHash(
        byte[] peerChallenge,
        byte[] authenticatorChallenge,
        String username
    ) throws IOException {
        validateChallenge(authenticatorChallenge, "authenticator");
        validateChallenge(peerChallenge, "peer");
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(peerChallenge);
            sha1.update(authenticatorChallenge);
            sha1.update(stripDomain(username).getBytes(StandardCharsets.US_ASCII));
            byte[] digest = sha1.digest();
            byte[] challenge = new byte[8];
            System.arraycopy(digest, 0, challenge, 0, challenge.length);
            return challenge;
        } catch (Exception e) {
            throw new IOException("Failed to compute MS-CHAPv2 challenge hash", e);
        }
    }

    public static byte[] challengeResponse(byte[] challenge, byte[] passwordHash) throws IOException {
        if (challenge == null || challenge.length != 8) {
            throw new IOException("MS-CHAPv2 challenge must be 8 bytes");
        }
        if (passwordHash == null || passwordHash.length != 16) {
            throw new IOException("MS-CHAPv2 password hash must be 16 bytes");
        }

        byte[] zPasswordHash = new byte[21];
        System.arraycopy(passwordHash, 0, zPasswordHash, 0, passwordHash.length);
        byte[] response = new byte[24];
        desEncrypt(challenge, zPasswordHash, 0, response, 0);
        desEncrypt(challenge, zPasswordHash, 7, response, 8);
        desEncrypt(challenge, zPasswordHash, 14, response, 16);
        return response;
    }

    public static String generateAuthenticatorResponse(
        String password,
        byte[] ntResponse,
        byte[] peerChallenge,
        byte[] authenticatorChallenge,
        String username
    ) throws IOException {
        try {
            byte[] passwordHashHash = hashNtPasswordHash(ntPasswordHash(password));
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(passwordHashHash);
            sha1.update(ntResponse);
            sha1.update(AUTHENTICATOR_MAGIC_1);
            byte[] digest = sha1.digest();

            sha1.reset();
            sha1.update(digest);
            sha1.update(challengeHash(peerChallenge, authenticatorChallenge, username));
            sha1.update(AUTHENTICATOR_MAGIC_2);
            return "S=" + toHex(sha1.digest());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to compute MS-CHAPv2 authenticator response", e);
        }
    }

    public static byte[] getMasterKey(byte[] passwordHashHash, byte[] ntResponse) throws IOException {
        if (passwordHashHash == null || passwordHashHash.length != 16) {
            throw new IOException("PasswordHashHash must be 16 bytes");
        }
        if (ntResponse == null || ntResponse.length != 24) {
            throw new IOException("NT-Response must be 24 bytes");
        }
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(passwordHashHash);
            sha1.update(ntResponse);
            sha1.update(MPPE_MAGIC_1);
            byte[] digest = sha1.digest();
            byte[] masterKey = new byte[16];
            System.arraycopy(digest, 0, masterKey, 0, masterKey.length);
            return masterKey;
        } catch (Exception e) {
            throw new IOException("Failed to derive MPPE master key", e);
        }
    }

    public static byte[] getAsymmetricStartKey(
        byte[] masterKey,
        int sessionKeyLength,
        boolean isSend,
        boolean isServer
    ) throws IOException {
        if (masterKey == null || masterKey.length != 16) {
            throw new IOException("MasterKey must be 16 bytes");
        }
        if (sessionKeyLength <= 0 || sessionKeyLength > 16) {
            throw new IOException("Unsupported MPPE session key length " + sessionKeyLength);
        }

        byte[] s = selectAsymmetricMagic(isSend, isServer);
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(masterKey);
            sha1.update(SHS_PAD_1);
            sha1.update(s);
            sha1.update(SHS_PAD_2);
            byte[] digest = sha1.digest();
            byte[] sessionKey = new byte[sessionKeyLength];
            System.arraycopy(digest, 0, sessionKey, 0, sessionKeyLength);
            return sessionKey;
        } catch (Exception e) {
            throw new IOException("Failed to derive MPPE asymmetric start key", e);
        }
    }

    public static String stripDomain(String username) {
        if (username == null) {
            return "";
        }
        int backslash = username.lastIndexOf('\\');
        if (backslash >= 0 && backslash + 1 < username.length()) {
            return username.substring(backslash + 1);
        }
        return username;
    }

    private static void validateChallenge(byte[] challenge, String label) throws IOException {
        if (challenge == null || challenge.length != 16) {
            throw new IOException("MS-CHAPv2 " + label + " challenge must be 16 bytes");
        }
    }

    private static void desEncrypt(
        byte[] clear,
        byte[] keyBytes,
        int keyOffset,
        byte[] out,
        int outOffset
    ) throws IOException {
        try {
            byte[] desKey = createDesKey(keyBytes, keyOffset);
            Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(desKey, "DES"));
            byte[] encrypted = cipher.doFinal(clear);
            System.arraycopy(encrypted, 0, out, outOffset, 8);
        } catch (Exception e) {
            throw new IOException("Failed to DES-encrypt MS-CHAPv2 block", e);
        }
    }

    private static byte[] createDesKey(byte[] input, int offset) {
        byte[] material = new byte[7];
        System.arraycopy(input, offset, material, 0, material.length);

        byte[] key = new byte[8];
        key[0] = (byte) (material[0] & 0xFE);
        key[1] = (byte) (((material[0] << 7) | ((material[1] & 0xFF) >>> 1)) & 0xFE);
        key[2] = (byte) (((material[1] << 6) | ((material[2] & 0xFF) >>> 2)) & 0xFE);
        key[3] = (byte) (((material[2] << 5) | ((material[3] & 0xFF) >>> 3)) & 0xFE);
        key[4] = (byte) (((material[3] << 4) | ((material[4] & 0xFF) >>> 4)) & 0xFE);
        key[5] = (byte) (((material[4] << 3) | ((material[5] & 0xFF) >>> 5)) & 0xFE);
        key[6] = (byte) (((material[5] << 2) | ((material[6] & 0xFF) >>> 6)) & 0xFE);
        key[7] = (byte) ((material[6] << 1) & 0xFE);

        for (int i = 0; i < key.length; i++) {
            key[i] = applyOddParity(key[i]);
        }
        return key;
    }

    private static byte applyOddParity(byte value) {
        int b = value & 0xFE;
        int ones = Integer.bitCount(b & 0xFF);
        return (byte) (b | ((ones & 1) == 0 ? 0x01 : 0x00));
    }

    private static byte[] selectAsymmetricMagic(boolean isSend, boolean isServer) {
        if (isSend) {
            return isServer ? MPPE_MAGIC_3 : MPPE_MAGIC_2;
        }
        return isServer ? MPPE_MAGIC_2 : MPPE_MAGIC_3;
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static String toHex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte b : value) {
            builder.append(String.format(Locale.US, "%02X", b));
        }
        return builder.toString();
    }

    private static byte[] repeated(byte value, int count) {
        byte[] output = new byte[count];
        for (int i = 0; i < count; i++) {
            output[i] = value;
        }
        return output;
    }
}
