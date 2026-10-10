package edu.redflag.decode;

import edu.redflag.error.FramingErrorException;
import edu.redflag.error.PacketCorruptedException;
import edu.redflag.model.Packet;
import edu.redflag.model.RawPacket;

/**
 * Legacy CSV packet decoder (schema v0):
 * "seq,st,proto,state,flgs,bytes"
 */
public final class CsvPacketDecoder implements PacketDecoder {
    @Override
    public Packet decode(RawPacket raw) throws PacketCorruptedException {
        String line = raw.line().trim();
        String[] fields = line.split(",");
        if (fields.length != 6) {
            throw new FramingErrorException("Expected 6 comma-separated fields, got " + fields.length + ": " + line);
        }
        try {
            long seq = Long.parseLong(fields[0].trim());
            long st = Long.parseLong(fields[1].trim());
            String proto = fields[2].trim();
            String state = fields[3].trim();
            String flgs = fields[4].trim();
            int bytes = Integer.parseInt(fields[5].trim());

            return new Packet("LEGACY-01", seq, st, proto, state, flgs, bytes);
        } catch (NumberFormatException e) {
            throw new PacketCorruptedException("Non-numeric field in CSV line: " + line, e);
        }
    }
}
