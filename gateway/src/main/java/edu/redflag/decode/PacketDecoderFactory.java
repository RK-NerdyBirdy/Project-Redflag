package edu.redflag.decode;

import edu.redflag.error.FramingErrorException;

/**
 * Factory for instantiating the appropriate PacketDecoder by inspecting line format.
 */
public final class PacketDecoderFactory {
    private static final PacketDecoder JSON = new JsonPacketDecoder();
    private static final PacketDecoder CSV  = new CsvPacketDecoder();

    private PacketDecoderFactory() {}

    /**
     * Inspects the first non-whitespace character to select the matching decoder.
     *
     * @param line Raw input string
     * @return JsonPacketDecoder if starts with '{', CsvPacketDecoder if starts with a digit
     * @throws FramingErrorException on null, empty, or unrecognized frame header
     */
    public static PacketDecoder forLine(String line) throws FramingErrorException {
        if (line == null || line.isBlank()) {
            throw new FramingErrorException("Empty line received");
        }
        String trimmed = line.trim();
        char firstChar = trimmed.charAt(0);
        if (firstChar == '{') {
            return JSON;
        }
        if (Character.isDigit(firstChar)) {
            return CSV;
        }
        throw new FramingErrorException("Unrecognized frame header byte 0x" + Integer.toHexString(firstChar));
    }
}
