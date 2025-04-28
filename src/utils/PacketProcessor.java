package utils;

import rudp.RUDPSource;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class for constructing and parsing custom reliable-UDP packets (DATA and ACK).
 * <p>
 * Provides static methods to build data and acknowledgment packets conforming to the
 * RUDP protocol header format, as well as extract sequence numbers, message types,
 * and payloads from received packets.
 * </p>
 */
public class PacketProcessor {
    /**
     * Logger for PacketProcessor events and errors.
     */
    private static final Logger logger = Logger.getLogger(PacketProcessor.class.getName());

    /**
     * Utility instance for validating addresses, ports, and sequence numbers.
     */
    private static final Utils utils = new Utils();

    /**
     * Initializes the PacketProcessor, setting up logging to a file.
     */
    public PacketProcessor() {
        try {
            logger.addHandler(new FileHandler("PacketProcessorLog.xml"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Builds a DATA packet containing a custom RUDP header and payload.
     *
     * @param data           the payload bytes to include in the packet
     * @param sequenceNumber the 18-bit sequence number for this packet
     * @param receiverHost   the destination IP address for the packet
     * @param receiverPort   the destination UDP port for the packet
     * @return a {@link DatagramPacket} ready for transmission, or {@code null}
     * if any validation fails
     */
    public static DatagramPacket buildDataPacket(byte[] data,
                                                 int sequenceNumber,
                                                 InetAddress receiverHost,
                                                 int receiverPort) {
        if (!utils.validateSequenceNumber(sequenceNumber)) {
            logger.log(Level.WARNING, "Seq# must fit in 18 bits");
            return null;
        }
        if (!utils.validatePort(receiverPort)) {
            logger.log(Level.SEVERE, "Invalid receiver port number: " + receiverPort);
            return null;
        }
        if (!utils.validateAddress(receiverHost)) {
            logger.log(Level.SEVERE, "Invalid Destination IP address");
            return null;
        }

        int dataLen = data.length;
        ByteBuffer buf = ByteBuffer.allocate(RUDPSource.HEADER_SIZE + dataLen);

        // Type byte: 0 = DATA
        buf.put(RUDPSource.TYPE_DATA);
        // 18-bit sequence number (3 bytes)
        for (int i = 1; i < 4; i++) {
            buf.put((byte) ((sequenceNumber >> ((3 - i) * 8)) & 0xFF));
        }
        // Payload length (2 bytes)
        buf.putShort((short) dataLen);
        // Payload data
        buf.put(data);

        return new DatagramPacket(buf.array(), buf.capacity(), receiverHost, receiverPort);
    }

    /**
     * Builds an ACK packet with a custom RUDP header and no payload.
     *
     * @param sequenceNumber the 18-bit sequence number being acknowledged
     * @param receiverHost   the destination IP address for the ACK
     * @param receiverPort   the destination UDP port for the ACK
     * @return a {@link DatagramPacket} representing the ACK, or {@code null}
     * if any validation fails
     */
    public static DatagramPacket buildAckPacket(int sequenceNumber,
                                                InetAddress receiverHost,
                                                int receiverPort) {
        if (!utils.validateSequenceNumber(sequenceNumber)) {
            logger.log(Level.WARNING, "Seq# must fit in 18 bits");
            return null;
        }
        if (!utils.validatePort(receiverPort)) {
            logger.log(Level.SEVERE, "Invalid receiver port number: " + receiverPort);
            return null;
        }
        if (!utils.validateAddress(receiverHost)) {
            logger.log(Level.SEVERE, "Invalid Destination IP address");
            return null;
        }

        ByteBuffer buf = ByteBuffer.allocate(RUDPSource.HEADER_SIZE);
        // Type byte: 1 = ACK
        buf.put((byte) 1);
        // 18-bit sequence number (3 bytes)
        for (int i = 1; i < 4; i++) {
            buf.put((byte) ((sequenceNumber >> ((3 - i) * 8)) & 0xFF));
        }
        // Zero payload length
        buf.putShort((short) 0);

        return new DatagramPacket(buf.array(), buf.capacity(), receiverHost, receiverPort);
    }

    /**
     * Extracts the sequence number from a received RUDP packet.
     *
     * @param packet the received {@link DatagramPacket}
     * @return the 18-bit sequence number parsed from the packet header
     */
    public static int getSequenceNumber(DatagramPacket packet) {
        byte[] data = packet.getData();
        int sequence = 0;
        for (int i = 1; i < 4; ++i) {
            sequence |= (data[i] & 0xFF) << ((3 - i) * 8);
        }
        return sequence;
    }

    /**
     * Reads the message type byte from a received RUDP packet.
     *
     * @param packet the received {@link DatagramPacket}
     * @return the type code (0 = DATA, 1 = ACK)
     */
    public static byte getMessageType(DatagramPacket packet) {
        return ByteBuffer.wrap(packet.getData()).get();
    }

    /**
     * Extracts the payload bytes from a received DATA packet.
     *
     * @param packet the received {@link DatagramPacket}
     * @return a byte array containing the payload; length is governed by the
     * payload-length field in the packet header
     */
    public static byte[] getPayload(DatagramPacket packet) {
        ByteBuffer buf = ByteBuffer.wrap(packet.getData());
        // Skip type byte and sequence number
        buf.get();
        buf.position(buf.position() + 3);
        // Read payload length
        int dataLen = buf.getShort();
        byte[] payload = new byte[dataLen];
        buf.get(payload, 0, dataLen);
        return payload;
    }
}