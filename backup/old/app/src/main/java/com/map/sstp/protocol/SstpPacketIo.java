package com.map.sstp.protocol;

import java.io.IOException;
import java.io.InputStream;

/**
 * Shared SSTP packet stream helpers used by both negotiation and relay stages.
 */
public final class SstpPacketIo {
    private SstpPacketIo() {}

    public static byte[] readPacket(InputStream in) throws IOException {
        if (in == null) {
            throw new IOException("SSTP input stream is not available");
        }

        byte[] header = new byte[SstpConstants.HEADER_SIZE];
        readFully(in, header, 0, header.length);
        int length = ((header[2] & 0x0F) << 8) | (header[3] & 0xFF);
        if (length < SstpConstants.HEADER_SIZE) {
            throw new IOException("Invalid SSTP packet length " + length);
        }

        byte[] packet = new byte[length];
        System.arraycopy(header, 0, packet, 0, header.length);
        int remaining = length - header.length;
        if (remaining > 0) {
            readFully(in, packet, header.length, remaining);
        }
        return packet;
    }

    public static boolean isControlPacket(byte[] packet) throws IOException {
        if (packet == null || packet.length < SstpConstants.HEADER_SIZE) {
            throw new IOException("SSTP packet header is too short");
        }
        return ((packet[1] & 0xFF) & SstpConstants.CONTROL_PACKET) == SstpConstants.CONTROL_PACKET;
    }

    private static void readFully(InputStream in, byte[] buffer, int offset, int length) throws IOException {
        int current = offset;
        int end = offset + length;
        while (current < end) {
            int read = in.read(buffer, current, end - current);
            if (read == -1) {
                throw new IOException("Unexpected EOF while reading SSTP packet");
            }
            current += read;
        }
    }
}
