package edu.redflag.decode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.redflag.error.FramingErrorException;
import edu.redflag.error.PacketCorruptedException;
import edu.redflag.model.Packet;
import edu.redflag.model.RawPacket;

import java.io.IOException;

/**
 * Jackson-based decoder for JSON schema v1 telemetry lines emitted by the Arduino firmware:
 * e.g.: {"v":1,"node":"UNO-01","seq":25,"st":4210500,"proto":"udp","state":"INT","flgs":"e","bytes":60}
 */
public final class JsonPacketDecoder implements PacketDecoder {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Packet decode(RawPacket raw) throws PacketCorruptedException {
        String line = raw.line();
        if (line == null || line.isBlank()) {
            throw new FramingErrorException("Blank telemetry line received");
        }
        try {
            JsonNode root = MAPPER.readTree(line);
            if (root == null || !root.isObject()) {
                throw new FramingErrorException("Expected JSON object: " + line);
            }

            // Validate mandatory sequence and stime fields
            if (!root.hasNonNull("seq") || !root.hasNonNull("st")) {
                // Also support legacy field names node_id if present
                if (!root.hasNonNull("seq")) {
                    throw new FramingErrorException("Missing mandatory 'seq' field: " + line);
                }
            }

            String nodeId = root.has("node") ? root.path("node").asText("UNO-01")
                          : root.path("node_id").asText("UNO-01");
            long seq = root.path("seq").asLong();
            long stime = root.path("st").asLong(0L);
            String proto = root.path("proto").asText("tcp");
            String state = root.path("state").asText("CON");
            String flgs = root.path("flgs").asText("e");
            int bytes = root.path("bytes").asInt(60);

            if (bytes < 0 || bytes > 65535) {
                throw new FramingErrorException("Payload size out of bounds (" + bytes + "): " + line);
            }

            return new Packet(nodeId, seq, stime, proto, state, flgs, bytes);
        } catch (IOException e) {
            throw new PacketCorruptedException("Failed to parse JSON telemetry: " + line, e);
        }
    }
}
