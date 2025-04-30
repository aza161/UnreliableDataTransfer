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

    /**
     * The port the receiver listens on
     */
    private static final int PORT = 59068;

    /**
     * Max UDP packet size (includes header + payload)
     */
    private static final int BUFFER_SIZE = 1024; // Should be MSS from sender

    /**
     * Logger for logging events and issues
     */
    private static final Logger logger = Logger.getLogger(RUDPDestination.class.getName());

    /**
     * Datagram socket to receive and send packets
     */
    private final DatagramSocket socket;

    /**
     * Buffer to store out-of-order packets (key: seq num, value: payload)
     * Using ConcurrentSkipListMap keeps them sorted by sequence number, which is helpful.
     */
    private final ConcurrentSkipListMap<Integer, byte[]> buffer = new ConcurrentSkipListMap<>();

    /**
     * The sequence number of the next contiguous packet expected for writing to file.
     */
    private int expectedSeq = 0;

    /**
     * FileProcessor instance used for reconstructing the file
     */
    private final FileProcessor fileProcessor = new FileProcessor();

    /**
     * Flag to know whether the output file has been initialized
     */
    private boolean fileStarted = false;


    /**
     * Counts the timeouts
     */
    private int timeouts = 0;

    // Need access to the sequence number space size from RUDPSource
    // Assuming maximumSequenceNumber is made public static final in RUDPSource
    // public final static int maximumSequenceNumber = 262143; // This should be in RUDPSource
    private static final int SN_SPACE_SIZE = RUDPSource.maximumSequenceNumber + 1; // Access from RUDPSource

    public RUDPDestination() throws IOException {
        try {
            socket = new DatagramSocket(PORT);
        } catch (SocketException e) {
            logger.log(Level.SEVERE, "[ERROR] Failed to create socket on port " + PORT, e);
            throw e;
        }
        try {
            logger.addHandler(new FileHandler("RUDPDestinationLog.xml"));
        } catch (IOException e) {
            logger.log(Level.WARNING, "[WARNING] Failed to create log file handler", e);
            // Continue without file logging if failed
        }
        logger.info("[RUDPDestination] Listening on port " + PORT);
    }

    /**
     * Starts receiving data packets and reconstructing the file.
     *
     * @param outputDir the directory where the received file will be saved
     */
    public void startReceiving(String outputDir) throws SocketTimeoutException {
        logger.log(Level.INFO, "[RECEIVER STARTED] Waiting for packets...");

        boolean receiving = true;

        // Set a timeout for the socket so it doesn't block indefinitely after the sender finishes
        // This allows the loop to terminate eventually if the END signal is lost.
        try {
            socket.setSoTimeout(60000); // Timeout after 1 minute of inactivity
        } catch (SocketException e) {
            logger.log(Level.WARNING, "[WARNING] Failed to set socket timeout", e);
        }
        long position = 0;


        while (receiving) {
            DatagramPacket packet = new DatagramPacket(new byte[BUFFER_SIZE], BUFFER_SIZE);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                timeouts++;
                logger.log(Level.FINE, "[TIMEOUT] Socket timed out. Continuing wait.");
                if (timeouts > 4) {
                    throw e;
                }
                continue; // Go back to waiting for the next packet
            } catch (IOException e) {
                logger.log(Level.SEVERE, "[ERROR] Failed to receive packet", e);
                break; // Exit the receiving loop on other IO errors
            }

            // Extract information from the packet
            byte type = PacketProcessor.getMessageType(packet);
            int seq = PacketProcessor.getSequenceNumber(packet);
            byte[] payload = PacketProcessor.getPayload(packet);

            // Handle packet types
            if (type == RUDPSource.TYPE_DATA) {
                logger.info("[DATA RECEPTION]:" + seq); // Log less verbosely

                // Send ACK immediately (Selective Repeat requires individual ACKs)
                sendAck(seq, packet.getAddress(), packet.getPort());

                if (!fileStarted) {
                    String filename;
                    if (outputDir != null && !outputDir.isEmpty()) {
                        // Ensure outputDir ends with a separator
                        filename = outputDir + (outputDir.endsWith("/") || outputDir.endsWith("\\") ? "" : "/") + "received-output-" + System.currentTimeMillis() + ".dat";
                    } else {
                        // Save to current directory if no outputDir specified
                        filename = "received-output-" + System.currentTimeMillis() + ".dat";
                    }

                    fileProcessor.startReconstruction(filename);
                    fileStarted = true;
                    logger.info("[FILE INITIALIZED] Saving to: " + filename);
                }


                // Calculate the distance between received seq and expectedSeq in the circular space
                // This handles wrap-around correctly for distances up to half the sequence space size.
                int distance = (seq - expectedSeq + SN_SPACE_SIZE) % SN_SPACE_SIZE;

                // Check if the packet is the next expected one (distance 0)
                if (distance == 0) {
                    // In-order packet, write it and advance expectedSeq
                    position += payload.length;
                    logger.log(Level.INFO, "[IN-ORDER] Writing packet | Pos: " + position + " Seq: " + seq + " (Expected: " + expectedSeq + ")");
                    fileProcessor.writeChunk(payload);

                    // Advance expectedSeq, handling wrap-around
                    expectedSeq = (expectedSeq + 1) % SN_SPACE_SIZE;

                    // Check buffer for subsequently expected packets that have already arrived
                    // Process all contiguous packets from the buffer
                    while (buffer.containsKey(expectedSeq)) {
                        logger.log(Level.INFO, "[BUFFERED-IN-ORDER] Writing packet from buffer | Seq: " + expectedSeq);
                        byte[] nextPayload = buffer.remove(expectedSeq); // Remove from buffer
                        fileProcessor.writeChunk(nextPayload);

                        // Advance expectedSeq again, handling wrap-around
                        expectedSeq = (expectedSeq + 1) % SN_SPACE_SIZE;
                    }

                } else if (distance < SN_SPACE_SIZE / 2) {
                    // Packet is ahead of expectedSeq within half the sequence space
                    // This indicates it's an out-of-order packet that belongs in the buffer.
                    // For simplicity, if it's ahead and not already in the buffer, store it.

                    // Check if we already have this packet in the buffer to avoid adding duplicates
                    if (!buffer.containsKey(seq)) {
                        buffer.put(seq, payload); // Store the out-of-order packet
                        logger.log(Level.FINE, "[BUFFERED] Out-of-order packet stored | Seq: " + seq + " (Expected next: " + expectedSeq + ")");
                    } else {
                        logger.log(Level.FINE, "[DUPLICATE IN BUFFER] Discarded | Seq: " + seq);
                    }

                } else {
                    // Packet is behind expectedSeq by more than half the sequence space.
                    // This indicates it's a duplicate of a packet already processed
                    logger.log(Level.FINE, "[DUPLICATE/OLD] Discarded | Seq: " + seq + " (Expected next: " + expectedSeq + ")");
                }

            } else if (type == (byte) 2) { // type 2 is the END signal
                logger.info("[RECEIVED] END signal | Seq: " + seq + ". Initiating shutdown.");
                receiving = false; // Exit the receiving loop

                // After receiving END, wait a bit for any straggler ACKs to be sent back
                try {
                    Thread.sleep(1000); // Short delay
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }

            } else {
                logger.warning("[RECEIVED] Unknown packet type: " + type + " | Seq: " + seq);
            }
        } // End of while(receiving) loop

        // Shutdown procedures outside the loop
        if (!socket.isClosed()) {
            socket.close();
            logger.info("[RECEIVER] Socket closed.");
        }
        if (fileStarted) {
            fileProcessor.close();
            logger.info("[RECEIVER] File reconstruction complete and closed.");
        } else {
            logger.info("[RECEIVER] No file started, nothing to close.");
        }

        logger.info("[RECEIVER] Shutdown complete.");
    }

    /**
     * Sends an ACK for a given sequence number to the sender.
     *
     * @param seqNum the sequence number being acknowledged
     * @param addr   the sender's address
     * @param port   the sender's port
     */
    private void sendAck(int seqNum, InetAddress addr, int port) {
        try {
            // PacketProcessor.buildAckPacket likely includes the sequence number
            DatagramPacket ackPkt = PacketProcessor.buildAckPacket(seqNum, addr, port);
            if (ackPkt != null) {
                socket.send(ackPkt);
                // logger.log(Level.FINE, "[ACK SENT] Seq: " + seqNum); // Log less verbosely
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "[ERROR] Failed to send ACK for Seq " + seqNum, e);
        }
    }

    /**
     * Entry point to start the RUDP receiver.
     *
     * @param args command-line arguments (optionally output directory)
     * @throws IOException if socket or initial setup fails
     */
    public static void main(String[] args) throws IOException {
        String outputDirectory = ""; // Default to current directory
        if (args.length > 0) {
            // Allow specifying output directory as a command line argument
            outputDirectory = args[0];
        }

        RUDPDestination receiver = new RUDPDestination();
        receiver.startReceiving(outputDirectory); // Pass the output directory
    }
}