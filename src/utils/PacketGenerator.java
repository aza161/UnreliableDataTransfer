package utils;

import java.util.Random;

/**
 * PacketGenerator is responsible for generating packets with varying lengths
 * using specified statistical distributions.
 * <p>
 * Supported distributions include:
 * <ul>
 *     <li><b>UNIFORM:</b> Packet length is selected uniformly between 0 and maxLength.</li>
 *     <li><b>POISSON:</b> Packet length is sampled from a Poisson distribution with mean (lambda) = maxLength / 2.</li>
 *     <li><b>DEFAULT (fallback):</b> Uniform distribution is used if an unsupported distribution is specified.</li>
 * </ul>
 */
public class PacketGenerator {
    private final int maxLength;           // Maximum possible length of the generated packet
    private final String distribution;     // The distribution type used for packet generation
    private final Random rand = new Random(); // Random generator for sampling

    /**
     * Constructs a PacketGenerator with the specified maximum packet length and distribution type.
     *
     * @param maxLength    the maximum allowed length for generated packets
     * @param distribution the distribution type ("UNIFORM", "POISSON", or fallback to "UNIFORM")
     */
    public PacketGenerator(int maxLength, String distribution) {
        this.maxLength = maxLength;
        this.distribution = distribution.toUpperCase();
    }

    /**
     * Generates a random string (packet) based on the chosen distribution type.
     * <p>
     * The length of the generated packet is determined by:
     * <ul>
     *     <li><b>UNIFORM:</b> Random length between 0 and maxLength.</li>
     *     <li><b>POISSON:</b> Length sampled using the Poisson distribution with lambda = maxLength / 2.</li>
     * </ul>
     *
     * @return a randomly generated string containing printable ASCII characters.
     */
    public String generate() {
        int length;
        switch (distribution) {
            case "U":
                length = rand.nextInt(maxLength);
                break;

            case "P":
                double lambda = maxLength / 2.0;
                double l = Math.exp(-lambda);
                int k = 0;
                double p = 1.0;
                do {
                    k++;
                    p *= rand.nextDouble();
                } while (p >= l);
                length = Math.min(k, maxLength); // Cap to maxLength
                break;

            default:
                length = rand.nextInt(maxLength); // Default to uniform
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append((char) (rand.nextInt(95) + 32)); // Printable ASCII range: [32, 126]
        }
        return sb.toString();
    }
}