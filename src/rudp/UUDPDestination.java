package rudp;

import utils.PacketProcessor;
import utils.FileProcessor;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * UUDPDestination is the receiver side of the RUDP protocol, which simulates
 * packet loss and delay while receiving data reliably over UDP.
 * It handles receiving packets, acknowledging them, and reconstructing files
 * from the data.
 */
public class UUDPDestination {

    /** Port number to listen on */
    private static final int PORT = 59068;

    /** Size of the buffer for each UDP packet */
    private static final int BUFFER_SIZE = 1024;

    /** Logger for logging events and warnings */
    private static final Logger logger = Logger.getLogger(UUDPDestination.class.getName());

    /** Probability of simulating a packet drop */
    private static final double DROP_PROBABILITY = 0.1;

    /** Maximum delay to simulate for a received packet in milliseconds */
    private static final int MAX_DELAY_MS = 200;

    /** Socket used for receiving UDP packets */
    private final DatagramSocket socket;

    /** Map to buffer out-of-order packets based on their sequence number */
    private final ConcurrentSkipListMap<Integer, byte[]> buffer = new ConcurrentSkipListMap<>();

    /** The next expected sequence number for in-order delivery */
    private int expectedSeq = 0;

    /** Handles file reconstruction from received data packets */
    private final FileProcessor fileProcessor = new FileProcessor();

    /** Indicates whether file reconstruction has started */
    private boolean fileStarted = false;

    /** Counts consecutive socket timeouts */
    private int timeouts = 0;

    /** Sequence number space size (based on max sequence number in RUDP) */
    private static final int SN_SPACE_SIZE = RUDPSource.maximumSequenceNumber + 1;

    /** Queue of packets that need to be acknowledged */
    public static final BlockingQueue<DatagramPacket> packsToBeAcked = new LinkedBlockingQueue<>();

    /** Queue of packets to be written to file */
    public static final BlockingQueue<DatagramPacket> packsToBeWritten = new LinkedBlockingQueue<>();

    /** Special packet used to signal shutdown of processing threads */
    private static final DatagramPacket POISON_PILL = new DatagramPacket(new byte[0], 0);

    /** Flag to indicate if receiver is still accepting packets */
    public static boolean receiving = true;

    /** Flag to indicate shutdown in progress */
    private volatile boolean shuttingDown = false;

    /** Latch used to signal completion of shutdown */
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    /**
     * Constructs a new UUDPDestination instance and initializes the socket and
     * logger.
     * 
     * @throws IOException if socket or logger setup fails
     */
    public UUDPDestination() throws IOException {
        try {
            socket = new DatagramSocket(PORT);
        } catch (SocketException e) {
            logger.log(Level.SEVERE, "[ERROR] Failed to create socket on port " + PORT, e);
            throw e;
        }
        try {
            logger.addHandler(new FileHandler("UUDPDestinationLog.xml"));
        } catch (IOException e) {
            logger.log(Level.WARNING, "[WARNING] Failed to create log file handler", e);
        }
        logger.info("[UUDPDestination] Listening on port " + PORT);
    }

    /**
     * Simulates network conditions such as random delay or drop on incoming
     * packets.
     * 
     * @param packet The received packet to process
     * @return true if packet should be dropped, false otherwise
     */
    private boolean simulateNetworkConditions(DatagramPacket packet) {
        double random = Math.random();
        if (random < DROP_PROBABILITY) {
            logger.log(Level.WARNING, "[NET-SIM] INCOMING DROPPED | seq=" +
                    PacketProcessor.getSequenceNumber(packet));
            return true;
        }

        int delay = (int) (Math.random() * MAX_DELAY_MS);
        if (delay > 0) {
            logger.log(Level.INFO, "[NET-SIM] INCOMING DELAYED | seq=" +
                    PacketProcessor.getSequenceNumber(packet) + " by " + delay + "ms");
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }

    /**
     * Starts receiving packets and handling them until shutdown is triggered.
     * 
     * @param outputDir Directory to store received file
     * @throws SocketTimeoutException if the socket experiences multiple timeouts
     */
    public void startReceiving(String outputDir) throws SocketTimeoutException {
        logger.log(Level.INFO, "[RECEIVER STARTED] Waiting for packets...");

        try {
            try {
                socket.setSoTimeout(60000);
            } catch (SocketException e) {
                logger.log(Level.SEVERE, "[ERROR] Failed to configure socket timeout", e);
                throw new SocketTimeoutException("Socket configuration failed");
            }

            while (!shuttingDown) {
                DatagramPacket packet = new DatagramPacket(new byte[BUFFER_SIZE], BUFFER_SIZE);

                try {
                    socket.receive(packet);

                    if (simulateNetworkConditions(packet)) {
                        continue;
                    }

                    packsToBeAcked.put(packet);
                    packsToBeWritten.put(packet);

                } catch (SocketTimeoutException e) {
                    if (shuttingDown)
                        break;

                    timeouts++;
                    if (timeouts > 4) {
                        logger.log(Level.WARNING, "[WARNING] Multiple consecutive timeouts");
                        throw e;
                    }
                } catch (IOException e) {
                    if (!shuttingDown) {
                        logger.log(Level.SEVERE, "[ERROR] Packet receive failed", e);
                    }
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            shutdown();
        }
    }

    /**
     * Sends an ACK packet back to the sender for the specified sequence number.
     * 
     * @param seqNum Sequence number being acknowledged
     * @param addr   Address of sender
     * @param port   Port of sender
     */
    private void sendAck(int seqNum, InetAddress addr, int port) {
        if (shuttingDown)
            return;
        try {
            DatagramPacket ackPkt = PacketProcessor.buildAckPacket(seqNum, addr, port);
            if (ackPkt != null && !socket.isClosed()) {
                socket.send(ackPkt);
            }
        } catch (IOException e) {
            if (!shuttingDown) {
                logger.log(Level.WARNING, "[WARNING] Failed to send ACK for Seq " + seqNum, e);
            }
        }
    }

    /**
     * Performs a clean shutdown by signaling threads, closing resources, and
     * logging the process.
     */
    private void shutdown() {
        shuttingDown = true;
        try {
            packsToBeAcked.put(POISON_PILL);
            packsToBeWritten.put(POISON_PILL);

            shutdownLatch.await(2, TimeUnit.SECONDS);

            if (!socket.isClosed())
                socket.close();
            if (fileStarted)
                fileProcessor.close();
            logger.info("[RECEIVER] Clean shutdown complete");
        } catch (Exception e) {
            logger.log(Level.WARNING, "[WARNING] Shutdown interrupted", e);
        }
    }

    /**
     * Main method to launch the destination receiver.
     * 
     * @param args Command-line arguments (first arg is optional output directory)
     * @throws IOException if socket setup fails
     */
    public static void main(String[] args) throws IOException {
        final String outputDirectory = args.length > 0 ? args[0] : "";
        final UUDPDestination receiver = new UUDPDestination();

        Thread receiverThread = new Thread(() -> {
            try {
                receiver.startReceiving(outputDirectory);
            } catch (SocketTimeoutException e) {
                logger.log(Level.SEVERE, "Socket Timed Out");
            }
        });

        Thread acksThread = new Thread(() -> {
            while (true) {
                try {
                    DatagramPacket packet = packsToBeAcked.take();
                    if (packet == POISON_PILL)
                        break;
                    int seq = PacketProcessor.getSequenceNumber(packet);
                    receiver.sendAck(seq, packet.getAddress(), packet.getPort());
                } catch (InterruptedException e) {
                    logger.log(Level.SEVERE, "Thread Interrupted");
                }
            }
        });

        Thread fileWritingThread = new Thread(() -> {
            long position = 0;
            try {
                while (!receiver.shuttingDown) {
                    try {
                        DatagramPacket packet = receiver.packsToBeWritten.take();
                        if (packet == POISON_PILL)
                            break;

                        byte type = PacketProcessor.getMessageType(packet);
                        int seq = PacketProcessor.getSequenceNumber(packet);
                        byte[] payload = PacketProcessor.getPayload(packet);

                        if (type == RUDPSource.TYPE_DATA) {
                            if (!receiver.fileStarted) {
                                String filename = outputDirectory +
                                        (outputDirectory.isEmpty() ? "" : "/") +
                                        "received-output-" + System.currentTimeMillis() + ".dat";
                                receiver.fileProcessor.startReconstruction(filename);
                                receiver.fileStarted = true;
                            }

                            int distance = (seq - receiver.expectedSeq + SN_SPACE_SIZE) % SN_SPACE_SIZE;

                            if (distance == 0) {
                                position += payload.length;
                                receiver.fileProcessor.writeChunk(payload);
                                receiver.expectedSeq = (receiver.expectedSeq + 1) % SN_SPACE_SIZE;

                                while (receiver.buffer.containsKey(receiver.expectedSeq)) {
                                    byte[] nextPayload = receiver.buffer.remove(receiver.expectedSeq);
                                    receiver.fileProcessor.writeChunk(nextPayload);
                                    receiver.expectedSeq = (receiver.expectedSeq + 1) % SN_SPACE_SIZE;
                                }
                            } else if (distance < SN_SPACE_SIZE / 2) {
                                receiver.buffer.putIfAbsent(seq, payload);
                            }
                        } else if (type == (byte) 2) {
                            receiver.shuttingDown = true;
                        }
                    } catch (InterruptedException e) {
                        if (!receiver.shuttingDown) {
                            logger.log(Level.SEVERE, "Thread Interrupted");
                        }
                        Thread.currentThread().interrupt();
                    }
                }
            } finally {
                receiver.shutdownLatch.countDown();
                logger.info("[FILE-WRITER] Thread completed");
            }
        });

        fileWritingThread.start();
        acksThread.start();
        receiverThread.start();

        try {
            receiverThread.join();
            packsToBeAcked.put(POISON_PILL);
            packsToBeWritten.put(POISON_PILL);
            acksThread.join();
            fileWritingThread.join();
        } catch (InterruptedException e) {
            logger.log(Level.SEVERE, "Threads Interrupted");
            System.exit(1);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            receiver.shuttingDown = true;
            try {
                receiverThread.join(1000);
                acksThread.join(1000);
                fileWritingThread.join(1000);
                receiver.shutdownLatch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        System.exit(0);
    }
}
