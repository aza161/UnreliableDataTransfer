package utils;

import java.util.Random;

/**
 * PacketGenerator is responsible for generating random packets
 * of data with a specified maximum length.
 */
public class PacketGenerator {
    private final int maxLength;

    /**
     * Constructs a PacketGenerator with the specified maximum length.
     *
     * @param maxLength the maximum length of the generated packets.
     */
    public PacketGenerator(int maxLength) {
        this.maxLength = maxLength;
    }

    /**
     * Generates a random packet of data as a string.
     *
     * @return a randomly generated string representing the packet data.
     */
    public String generate() {
        Random r = new Random();
        // Generate a random length for the packet, up to the maximum length
        int length = r.nextInt(maxLength);
        StringBuilder sb = new StringBuilder();
        // Append random characters to the StringBuilder to form the packet
        for (int i = 0; i < length; i++) {
            sb.append((char) r.nextInt(255));
        }
        return sb.toString();
    }
}
