package edu.redflag.decode;

import edu.redflag.error.FramingErrorException;
import edu.redflag.error.PacketCorruptedException;
import edu.redflag.model.Packet;
import edu.redflag.model.RawPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PacketDecoderTest {

    @Test
    public void testJsonDecoderValidFrame() throws PacketCorruptedException {
        String line = "{\"v\":1,\"node\":\"UNO-01\",\"seq\":42,\"st\":1234567,\"proto\":\"udp\",\"state\":\"INT\",\"flgs\":\"e\",\"bytes\":60}";
        RawPacket raw = new RawPacket(line, System.nanoTime());

        PacketDecoder decoder = PacketDecoderFactory.forLine(line);
        assertTrue(decoder instanceof JsonPacketDecoder);

        Packet p = decoder.decode(raw);
        assertEquals("UNO-01", p.nodeId());
        assertEquals(42L, p.seq());
        assertEquals(1234567L, p.stimeUs());
        assertEquals("udp", p.proto());
        assertEquals("INT", p.state());
        assertEquals("e", p.flgs());
        assertEquals(60, p.bytes());
    }

    @Test
    public void testCsvDecoderValidFrame() throws PacketCorruptedException {
        String line = "10,500000,tcp,CON,e,120";
        RawPacket raw = new RawPacket(line, System.nanoTime());

        PacketDecoder decoder = PacketDecoderFactory.forLine(line);
        assertTrue(decoder instanceof CsvPacketDecoder);

        Packet p = decoder.decode(raw);
        assertEquals("LEGACY-01", p.nodeId());
        assertEquals(10L, p.seq());
        assertEquals(500000L, p.stimeUs());
        assertEquals("tcp", p.proto());
        assertEquals("CON", p.state());
        assertEquals(120, p.bytes());
    }

    @Test
    public void testCorruptedLineThrowsException() {
        String line = "not_valid_frame";
        assertThrows(FramingErrorException.class, () -> PacketDecoderFactory.forLine(line));

        String brokenJson = "{\"seq\":";
        RawPacket raw = new RawPacket(brokenJson, System.nanoTime());
        assertThrows(PacketCorruptedException.class, () -> {
            PacketDecoder decoder = PacketDecoderFactory.forLine(brokenJson);
            decoder.decode(raw);
        });
    }
}
