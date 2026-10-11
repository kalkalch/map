package com.map.sstp.protocol;

import com.map.sstp.ppp.PppSession;
import com.map.sstp.tls.SstpTlsSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Computes the cryptographic fields needed by SSTP Call Connected.
 */
public final class SstpCryptoBinding {
    private static final byte[] CMK_SEED =
        "SSTP inner method derived CMK".getBytes(StandardCharsets.US_ASCII);

    private SstpCryptoBinding() {}

    public static SstpPacketUtil.CryptoBindingMaterial createMaterial(
        SstpPacketUtil.CryptoBindingRequest request,
        SstpTlsSession session,
        PppSession pppSession
    ) throws IOException {
        if (request == null) {
            throw new IOException("Missing crypto binding request");
        }
        if (session == null) {
            throw new IOException("Missing TLS session");
        }

        int chosenProtocol = chooseHashProtocol(request.getHashProtocolBitmask());
        byte[] certificateHash = computeCertificateHash(session, chosenProtocol);
        byte[] hlak = pppSession != null ? pppSession.getHigherLayerAuthKey() : PppSession.zeroedHlak();
        byte[] compoundMac = computeCompoundMac(
            chosenProtocol,
            request.getNonce(),
            certificateHash,
            hlak
        );

        return new SstpPacketUtil.CryptoBindingMaterial(
            chosenProtocol,
            request.getNonce(),
            certificateHash,
            compoundMac
        );
    }

    public static int chooseHashProtocol(int bitmask) throws IOException {
        if ((bitmask & SstpConstants.CERT_HASH_PROTOCOL_SHA256) != 0) {
            return SstpConstants.CERT_HASH_PROTOCOL_SHA256;
        }
        if ((bitmask & SstpConstants.CERT_HASH_PROTOCOL_SHA1) != 0) {
            throw new IOException("SSTP SHA1 crypto binding is not implemented yet");
        }
        throw new IOException("No supported SSTP hash protocol in bitmask " + bitmask);
    }

    private static byte[] computeCertificateHash(SstpTlsSession session, int hashProtocol) throws IOException {
        try {
            Certificate[] peerCertificates = session.getSocket().getSession().getPeerCertificates();
            if (peerCertificates == null || peerCertificates.length == 0) {
                throw new IOException("Peer certificate is not available");
            }
            byte[] encoded = peerCertificates[0].getEncoded();
            MessageDigest digest = MessageDigest.getInstance(
                hashProtocol == SstpConstants.CERT_HASH_PROTOCOL_SHA256 ? "SHA-256" : "SHA-1"
            );
            return digest.digest(encoded);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to hash peer certificate", e);
        }
    }

    private static byte[] computeCompoundMac(
        int hashProtocol,
        byte[] nonce,
        byte[] certificateHash,
        byte[] hlak
    ) throws IOException {
        if (hashProtocol != SstpConstants.CERT_HASH_PROTOCOL_SHA256) {
            throw new IOException("Only SHA256 crypto binding is implemented");
        }

        byte[] cmk = deriveSha256Cmk(hlak);
        byte[] input = SstpPacketUtil.buildCallConnectedForMac(hashProtocol, nonce, certificateHash);

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cmk, "HmacSHA256"));
            return mac.doFinal(input);
        } catch (Exception e) {
            throw new IOException("Failed to compute SSTP compound MAC", e);
        }
    }

    /** Package-private for unit tests (MS-SSTP CMK KDF). */
    static byte[] deriveSha256Cmk(byte[] hlak) throws IOException {
        byte[] normalizedHlak = normalizeHlak(hlak);
        // [MS-SSTP] CMK KDF: 16-bit output length is little-endian (see sorz/sstp-server, MS-SSTP 3.2.5).
        byte[] outputLen = new byte[] { 0x20, 0x00 };

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(normalizedHlak, "HmacSHA256"));

            ByteArrayOutputStream t1Input = new ByteArrayOutputStream();
            t1Input.write(CMK_SEED);
            t1Input.write(outputLen);
            t1Input.write(0x01);
            return mac.doFinal(t1Input.toByteArray());
        } catch (Exception e) {
            throw new IOException("Failed to derive SSTP CMK", e);
        }
    }

    private static byte[] normalizeHlak(byte[] hlak) {
        byte[] normalized = new byte[32];
        if (hlak == null) {
            return normalized;
        }
        System.arraycopy(hlak, 0, normalized, 0, Math.min(hlak.length, normalized.length));
        return normalized;
    }
}
