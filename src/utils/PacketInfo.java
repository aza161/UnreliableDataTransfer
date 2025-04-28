package utils;

import java.net.DatagramPacket;

/**
 * Encapsulates a UDP packet along with transmission metadata for reliable UDP protocols.
 * <p>
 * Each PacketInfo holds the {@link DatagramPacket} to be sent or retransmitted,
 * along with timing and retransmission counters, and file-offset information.
 * </p>
 */
public class PacketInfo {
    /**
     * The underlying UDP packet containing header and payload data.
     */
    public final DatagramPacket packet;

    /**
     * Timestamp (in milliseconds since the epoch) when this packet was last sent.
     * Used for calculating RTT and scheduling retransmissions.
     */
    public long lastSentTime;

    /**
     * Number of times this packet has been retransmitted due to timeout events.
     */
    public int retransmitted;

    /**
     * Byte offset in the file where this packet's payload begins.
     * Set externally when fragmenting the file.
     */
    public long start;

    /**
     * Length (in bytes) of this packet's payload portion.
     * Set externally when fragmenting the file.
     */
    public int length;

    /**
     * Constructs a PacketInfo wrapping the given {@link DatagramPacket}.
     * Initializes timing and retransmission counters to their default values.
     *
     * @param pkt the DatagramPacket containing both the custom header and payload
     */
    public PacketInfo(DatagramPacket pkt) {
        this.packet = pkt;
        this.lastSentTime = 0L;
        this.retransmitted = 0;
        this.start = 0L;
        this.length = 0;
    }
}