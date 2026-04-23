package com.map.sstp.ppp;

/**
 * Minimal MD4 implementation for MS-CHAPv2 NT hash generation.
 */
public final class Md4 {
    private final int[] state = new int[4];
    private final int[] x = new int[16];
    private final byte[] buffer = new byte[64];
    private long count;

    public Md4() {
        reset();
    }

    public void update(byte[] input) {
        update(input, 0, input.length);
    }

    public void update(byte[] input, int offset, int length) {
        int index = (int) (count & 0x3F);
        count += length;
        int partLen = 64 - index;
        int i = 0;

        if (length >= partLen) {
            System.arraycopy(input, offset, buffer, index, partLen);
            transform(buffer, 0);
            for (i = partLen; i + 63 < length; i += 64) {
                transform(input, offset + i);
            }
            index = 0;
        }

        if (i < length) {
            System.arraycopy(input, offset + i, buffer, index, length - i);
        }
    }

    public byte[] digest() {
        byte[] padding = new byte[] { (byte) 0x80 };
        byte[] bits = encodeCount(count << 3);
        int index = (int) (count & 0x3F);
        int padLen = index < 56 ? 56 - index : 120 - index;
        update(padding, 0, 1);
        if (padLen > 1) {
            update(new byte[padLen - 1], 0, padLen - 1);
        }
        update(bits, 0, bits.length);

        byte[] digest = encodeState();
        reset();
        return digest;
    }

    public static byte[] hash(byte[] input) {
        Md4 md4 = new Md4();
        md4.update(input);
        return md4.digest();
    }

    private void reset() {
        count = 0;
        state[0] = 0x67452301;
        state[1] = 0xEFCDAB89;
        state[2] = 0x98BADCFE;
        state[3] = 0x10325476;
    }

    private void transform(byte[] block, int offset) {
        for (int i = 0; i < 16; i++) {
            int j = offset + i * 4;
            x[i] = (block[j] & 0xFF) |
                ((block[j + 1] & 0xFF) << 8) |
                ((block[j + 2] & 0xFF) << 16) |
                ((block[j + 3] & 0xFF) << 24);
        }

        int a = state[0];
        int b = state[1];
        int c = state[2];
        int d = state[3];

        a = ff(a, b, c, d, x[0], 3);
        d = ff(d, a, b, c, x[1], 7);
        c = ff(c, d, a, b, x[2], 11);
        b = ff(b, c, d, a, x[3], 19);
        a = ff(a, b, c, d, x[4], 3);
        d = ff(d, a, b, c, x[5], 7);
        c = ff(c, d, a, b, x[6], 11);
        b = ff(b, c, d, a, x[7], 19);
        a = ff(a, b, c, d, x[8], 3);
        d = ff(d, a, b, c, x[9], 7);
        c = ff(c, d, a, b, x[10], 11);
        b = ff(b, c, d, a, x[11], 19);
        a = ff(a, b, c, d, x[12], 3);
        d = ff(d, a, b, c, x[13], 7);
        c = ff(c, d, a, b, x[14], 11);
        b = ff(b, c, d, a, x[15], 19);

        a = gg(a, b, c, d, x[0], 3);
        d = gg(d, a, b, c, x[4], 5);
        c = gg(c, d, a, b, x[8], 9);
        b = gg(b, c, d, a, x[12], 13);
        a = gg(a, b, c, d, x[1], 3);
        d = gg(d, a, b, c, x[5], 5);
        c = gg(c, d, a, b, x[9], 9);
        b = gg(b, c, d, a, x[13], 13);
        a = gg(a, b, c, d, x[2], 3);
        d = gg(d, a, b, c, x[6], 5);
        c = gg(c, d, a, b, x[10], 9);
        b = gg(b, c, d, a, x[14], 13);
        a = gg(a, b, c, d, x[3], 3);
        d = gg(d, a, b, c, x[7], 5);
        c = gg(c, d, a, b, x[11], 9);
        b = gg(b, c, d, a, x[15], 13);

        a = hh(a, b, c, d, x[0], 3);
        d = hh(d, a, b, c, x[8], 9);
        c = hh(c, d, a, b, x[4], 11);
        b = hh(b, c, d, a, x[12], 15);
        a = hh(a, b, c, d, x[2], 3);
        d = hh(d, a, b, c, x[10], 9);
        c = hh(c, d, a, b, x[6], 11);
        b = hh(b, c, d, a, x[14], 15);
        a = hh(a, b, c, d, x[1], 3);
        d = hh(d, a, b, c, x[9], 9);
        c = hh(c, d, a, b, x[5], 11);
        b = hh(b, c, d, a, x[13], 15);
        a = hh(a, b, c, d, x[3], 3);
        d = hh(d, a, b, c, x[11], 9);
        c = hh(c, d, a, b, x[7], 11);
        b = hh(b, c, d, a, x[15], 15);

        state[0] += a;
        state[1] += b;
        state[2] += c;
        state[3] += d;
    }

    private int ff(int a, int b, int c, int d, int xk, int s) {
        return Integer.rotateLeft(a + f(b, c, d) + xk, s);
    }

    private int gg(int a, int b, int c, int d, int xk, int s) {
        return Integer.rotateLeft(a + g(b, c, d) + xk + 0x5A827999, s);
    }

    private int hh(int a, int b, int c, int d, int xk, int s) {
        return Integer.rotateLeft(a + h(b, c, d) + xk + 0x6ED9EBA1, s);
    }

    private int f(int x, int y, int z) {
        return (x & y) | (~x & z);
    }

    private int g(int x, int y, int z) {
        return (x & y) | (x & z) | (y & z);
    }

    private int h(int x, int y, int z) {
        return x ^ y ^ z;
    }

    private byte[] encodeState() {
        byte[] output = new byte[16];
        for (int i = 0; i < 4; i++) {
            int n = state[i];
            output[i * 4] = (byte) (n & 0xFF);
            output[i * 4 + 1] = (byte) ((n >>> 8) & 0xFF);
            output[i * 4 + 2] = (byte) ((n >>> 16) & 0xFF);
            output[i * 4 + 3] = (byte) ((n >>> 24) & 0xFF);
        }
        return output;
    }

    private byte[] encodeCount(long bitCount) {
        byte[] output = new byte[8];
        for (int i = 0; i < 8; i++) {
            output[i] = (byte) ((bitCount >>> (i * 8)) & 0xFF);
        }
        return output;
    }
}
