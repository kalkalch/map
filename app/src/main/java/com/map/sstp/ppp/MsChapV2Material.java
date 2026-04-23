package com.map.sstp.ppp;

/**
 * Derived MS-CHAPv2/MPPE material that SSTP later uses for crypto binding.
 */
public final class MsChapV2Material {
    private final byte[] authenticatorChallenge;
    private final byte[] peerChallenge;
    private final byte[] challenge;
    private final byte[] ntResponse;
    private final String authenticatorResponse;
    private final byte[] passwordHash;
    private final byte[] passwordHashHash;
    private final byte[] masterKey;
    private final byte[] masterSendKey;
    private final byte[] masterReceiveKey;
    private final byte[] higherLayerAuthKey;

    public MsChapV2Material(
        byte[] authenticatorChallenge,
        byte[] peerChallenge,
        byte[] challenge,
        byte[] ntResponse,
        String authenticatorResponse,
        byte[] passwordHash,
        byte[] passwordHashHash,
        byte[] masterKey,
        byte[] masterSendKey,
        byte[] masterReceiveKey,
        byte[] higherLayerAuthKey
    ) {
        this.authenticatorChallenge = authenticatorChallenge.clone();
        this.peerChallenge = peerChallenge.clone();
        this.challenge = challenge.clone();
        this.ntResponse = ntResponse.clone();
        this.authenticatorResponse = authenticatorResponse;
        this.passwordHash = passwordHash.clone();
        this.passwordHashHash = passwordHashHash.clone();
        this.masterKey = masterKey.clone();
        this.masterSendKey = masterSendKey.clone();
        this.masterReceiveKey = masterReceiveKey.clone();
        this.higherLayerAuthKey = higherLayerAuthKey.clone();
    }

    public byte[] getAuthenticatorChallenge() {
        return authenticatorChallenge.clone();
    }

    public byte[] getPeerChallenge() {
        return peerChallenge.clone();
    }

    public byte[] getChallenge() {
        return challenge.clone();
    }

    public byte[] getNtResponse() {
        return ntResponse.clone();
    }

    public String getAuthenticatorResponse() {
        return authenticatorResponse;
    }

    public byte[] getPasswordHash() {
        return passwordHash.clone();
    }

    public byte[] getPasswordHashHash() {
        return passwordHashHash.clone();
    }

    public byte[] getMasterKey() {
        return masterKey.clone();
    }

    public byte[] getMasterSendKey() {
        return masterSendKey.clone();
    }

    public byte[] getMasterReceiveKey() {
        return masterReceiveKey.clone();
    }

    public byte[] getHigherLayerAuthKey() {
        return higherLayerAuthKey.clone();
    }
}
