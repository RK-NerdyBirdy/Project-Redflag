package edu.redflag.decode;

import edu.redflag.error.PacketCorruptedException;
import edu.redflag.model.Packet;
import edu.redflag.model.RawPacket;

/**
 * Strategy interface for parsing incoming raw line payloads into domain Packet objects.
 */
public interface PacketDecoder {
    Packet decode(RawPacket raw) throws PacketCorruptedException;
}
