package rudp;

import utils.PacketProcessor;
import utils.FileProcessor;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * RUDPDestination is the receiver in the Reliable UDP transfer system.
 * It receives data packets, sends ACKs, handles out-of-order packets,
 * and reconstructs the file in the correct order using FileProcessor.
 */
public class RUDPDestination {

    /** The port the receiver listens on */
    private static final int PORT = 59068;

    /** Max UDP packet size (includes header + payload) */
    private static final int BUFFER_SIZE = 1024;

    /** Logger for logging events and issues */
    private static final Logger logger = Logger.getLogger(RUDPDestination.class.getName());

    /** Datagram socket to receive and send packets */
    private final DatagramSocket socket;

    /** Buffer to store out-of-order packets (key: seq num, value: payload) */
    private final ConcurrentSkipListMap<Integer, byte[]> buffer = new ConcurrentSkipListMap<>();

    /** Expected next sequence number */
    private int expectedSeq = 0;

    /** FileProcessor instance used for reconstructing the file */
    private final FileProcessor fileProcessor = new FileProcessor();

    /** Flag to know whether the output file has been initialized */
    private boolean fileStarted = false;

    public RUDPDestination() throws IOException {
        socket = new DatagramSocket(PORT);
        logger.addHandler(new FileHandler("RUDPDestinationLog.xml"));
        logger.info("[RUDPDestination] Listening on port " + PORT);
    }

    /**
     * Starts receiving data packets and reconstructing the file.
     * @param outputDir the directory where the received file will be saved
     */
    public void startReceiving(String outputDir) throws IOException {
        System.out.println("[RECEIVER STARTED] Waiting for packets...");

        boolean receiving = true;

        while (receiving) {
            DatagramPacket packet = new DatagramPacket(new byte[BUFFER_SIZE], BUFFER_SIZE);

            try {
                socket.receive(packet);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "[ERROR] Failed to receive packet", e);
                continue;
            }

            // Extract information from the packet
            int seq = PacketProcessor.getSequenceNumber(packet);
            byte type = PacketProcessor.getMessageType(packet);
            byte[] payload = PacketProcessor.getPayload(packet);

            // Handle only DATA packets (ACKs are only sent, not processed)
            if (type == RUDPSource.TYPE_DATA) {
                logger.info("[RECEIVED] DATA packet | Seq: " + seq);

                // Send ACK immediately
                sendAck(seq, packet.getAddress(), packet.getPort());

                // Initialize the file if this is the first packet
                if (!fileStarted) {
                    String filename = outputDir + "/received-output-" + System.currentTimeMillis() + ".dat";
                    fileProcessor.startReconstruction(filename);
                    fileStarted = true;
                }

                if (seq == expectedSeq) {
                    // In-order packet, write it
                    fileProcessor.writeChunk(payload);
                    expectedSeq++;

                    // Check buffer for next expected packets
                    while (buffer.containsKey(expectedSeq)) {
                        byte[] nextPayload = buffer.remove(expectedSeq);
                        fileProcessor.writeChunk(nextPayload);
                        expectedSeq++;
                    }
                } else if (seq > expectedSeq) {
                    // Out-of-order, store it for now
                    buffer.putIfAbsent(seq, payload);
                    logger.info("[BUFFERED] Out-of-order packet stored | Seq: " + seq);
                } else {
                    // Duplicate or late packet, already handled
                    logger.info("[DUPLICATE] Discarded | Seq: " + seq);
                }
            }
        }

        socket.close();
        fileProcessor.close();
        logger.info("[RECEIVER] Shutdown complete.");
    }

    /**
     * Sends an ACK for a given sequence number to the sender.
     * @param seqNum the sequence number being acknowledged
     * @param addr the sender's address
     * @param port the sender's port
     */
    private void sendAck(int seqNum, InetAddress addr, int port) {
        try {
            DatagramPacket ackPkt = PacketProcessor.buildAckPacket(seqNum, addr, port);
            if (ackPkt != null) {
                socket.send(ackPkt);
                logger.info("[ACK SENT] Seq: " + seqNum);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "[ERROR] Failed to send ACK", e);
        }
    }

    /**
     * Entry point to start the RUDP receiver.
     * @param args command-line arguments (not used)
     * @throws IOException if socket or file IO fails
     */
    public static void main(String[] args) throws IOException {
        RUDPDestination receiver = new RUDPDestination();
        receiver.startReceiving("."); // Save to current working directory
    }
}