package rudp;

import utils.*;


import java.net.*;
import java.io.File;
import java.time.Instant;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.FileHandler;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RUDPSource is a bidirectional Reliable UPD client application that implements.
 * It sends data UDP packets to other clients, and receives ACKs from them.
 *
 * @author ahmad
 */
public class RUDPSource {
    /**
     * The logger for recording client activities and debugging information.
     */
    private static final java.util.logging.Logger logger = Logger.getLogger(RUDPSource.class.getName());

    /**
     * The utilities object that provides certain utilities to the functions in this class
     */
    private static final Utils utils = new Utils();

    /**
     * The port used by this application.
     */
    public static final int PORT = 59069;

    /**
     * The maximum RTO after which the application will terminate if a re-transmission is needed.
     * in milliseconds
     */
    public static final long maximumTimeOut = 60000;

    /**
     * A byte used to identify a data-containing packet.
     */
    public static final byte TYPE_DATA = 0;

    /**
     * The size of the header of a packet.
     * 1 byte for packet type.
     * 3 bytes for the 18-bit sequence number.
     * 2 bytes for the payload size.
     */
    public static final int HEADER_SIZE = 6;

    /**
     * Maximum Segment Size of 1024 bytes.
     * This is the size of the payload of the DatagramPacket.
     * 6 bytes are used by the header.
     */
    public final static int MSS = 1024;

    /**
     * The advertised window size in bytes, the congestion window size cannot be larger than it.
     */
    public final static int windowSize = 131072;

    /**
     * The maximum sequence number, the sequence number is in the domain: [0:maximumSequenceNumber].
     * The domain has a cardinality of windowSize * 2.
     */
    public final static int maximumSequenceNumber = 262143;

    /**
     * The alpha used in Karn/Partridge algorithm.
     */
    private final static double alpha = 0.125;

    /**
     * The beta used in Jacobson/Karels algorithm.
     */
    private final static double beta = 0.25;

    /**
     * The current congestionWindow.
     * Key is the sequence number of a packet.
     * Value is {@link PacketInfo}.
     */
    private final ConcurrentHashMap<Integer, PacketInfo> window = new ConcurrentHashMap<Integer, PacketInfo>();

    /**
     * Maintains a mapping between the sequence number of a packet and the count of ACKs received for it.
     */
    private final ConcurrentHashMap<Integer, AtomicInteger> sequenceAckCount = new ConcurrentHashMap<>();


    /**
     * A set of timers for each packet.
     * Key is the sequence number of a packet.
     * Value is a {@link ScheduledFuture} to re-transmit the packet after RTO.
     */
    private final ConcurrentHashMap<Integer, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();

    /**
     * A {@link ScheduledExecutorService} used to execute the {@link ScheduledFuture} in the set of {@link RUDPSource#timers}.
     */
    /*private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "RUDP-RetransmitScheduler");
        t.setDaemon(true);
        return t;
    });*/
    private final int poolSize = 8;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(poolSize);

    /**
     * The total packets sent by the application.
     */
    private final AtomicLong totalPacketsSent = new AtomicLong(0);

    /**
     * Used to keep tack of the payload index.
     */
    private long index = 0;

    /**
     * The previous total number of packets sent.
     * Used for additive increase computations.
     */
    private AtomicLong prevTotalPacketsSent;

    /**
     * The socket the application uses.
     */
    private DatagramSocket socket;

    /**
     * The size of the congestion window.
     */
    private AtomicInteger congestionWindowSize;

    /**
     * The slow start threshold after which the additive increase starts.
     */
    private AtomicInteger slowStartThreshold;

    /**
     * The smoothed RTT in Karn/Partridge algorithm.
     */
    private AtomicLong smoothedRTT;

    /**
     * The RTT variation in Jacobson/Karels algorithm.
     */
    private AtomicLong RTTVar;

    /**
     * The estimated round trip timeout.
     */
    private AtomicLong RTO;

    /**
     * The receiver's IP address.
     */
    private InetAddress receiverHost;

    /**
     * The receiver's port number on which the {@link RUDPDestination} is listening on.
     */
    private int receiverPort;

    /**
     * The file which this application is going to send to the receiver.
     */
    private File file;

    /**
     * Computes the deviation.
     *
     * @param sampleRTT the Round Trip Time of a certain packet.
     * @return the deviation.
     */
    private long computeSigma(double sampleRTT) {
        return (long) Math.abs(sampleRTT - smoothedRTT.get());
    }

    /**
     * Computes and updates the RTT variation after a round trip.
     *
     * @param sigma the deviation.
     */
    private void updateRTTVar(double sigma) {
        RTTVar.set((long) Math.ceil((1 - beta) * RTTVar.get() + beta * sigma));
    }

    /**
     * Computes and updates the smoothed RTT.
     *
     * @param sampleRTT the Round Trip Time of a certain packet.
     */
    private void updateSmoothedRTT(double sampleRTT) {
        smoothedRTT.set((long) Math.ceil((1 - alpha) * smoothedRTT.get() + alpha * sampleRTT));
    }

    /**
     * Updates the round trip timeout.
     */
    private void updateRTO() {
        RTO.set(smoothedRTT.get() + 4 * RTTVar.get());
    }


    public RUDPSource(InetAddress receiverHost,
                      int receiverPort,
                      File file,
                      int port) {
        if (!utils.validateFile(file)) {
            String errorMsg = "File provided cannot be read or is a directory: " + file.toString();
            RUDPSource.logger.log(Level.SEVERE, errorMsg);
            throw new IllegalArgumentException(errorMsg);
        } else {
            this.file = file;
        }

        if (!utils.validateAddress(receiverHost)) {
            throw new IllegalArgumentException("Invalid Destination IP address");
        }

        if (!utils.validatePort(receiverPort)) {
            throw new IllegalArgumentException("Invalid receiver port number: " + receiverPort);
        }

        if (!utils.validatePort(port)) {
            throw new IllegalArgumentException("Invalid port number: " + port);
        }

        congestionWindowSize = new AtomicInteger(64);
        slowStartThreshold = new AtomicInteger(windowSize);

        smoothedRTT = new AtomicLong(500);
        RTTVar = new AtomicLong(250);
        this.updateRTO();

        try {
            logger.addHandler(new FileHandler("RUDPSourceLogs.xml"));
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            this.socket = new DatagramSocket(port);
            RUDPSource.logger.log(Level.INFO, "UserClient socket initiated with port number: " + port);
        } catch (SocketException e) {
            RUDPSource.logger.log(Level.SEVERE, "Failed to initialize a socket on port " + port, e);
            throw new RuntimeException("Failed to initialize a socket", e);
        }

        this.receiverHost = receiverHost;

        this.prevTotalPacketsSent = new AtomicLong(0);

        this.receiverPort = receiverPort;
    }

    /**
     * Receives a packets.
     *
     * @return the packet received.
     */
    public DatagramPacket receive() {

        // Check if the socket is functional
        if (!utils.validateSocket(this.socket)) {
            return null;
        }

        // Create the packet
        DatagramPacket packet = new DatagramPacket(new byte[1024], 1024);

        // Try receiving the packet
        try {
            this.socket.receive(packet);
            return packet;
        } catch (SocketException e) {
            return null;
        } catch (IOException e) {
            // If an error is caught log it
            RUDPSource.logger.log(Level.SEVERE, "Failed to receive message", e);
        }
        return null;
    }

    /**
     * Schedules a retransmission of a certain packet after the current {@link RUDPSource#RTO}.
     * It creates a {@link ScheduledFuture} and adds it to the {@link RUDPSource#scheduler}.
     *
     * @param sequenceNumber the sequence number of the packet to be scheduled for retransmission.
     */
    private void scheduleRetransmit(int sequenceNumber) {

        // checks if there is already a timer for the packet.
        ScheduledFuture<?> old = timers.remove(sequenceNumber);

        // if it exists it cancels it
        if (old != null) {
            old.cancel(false);
        }

        long delay = RTO.get(); // the current delay/RTO

        // The function to be executed when the timer timeouts
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            // if still outstanding, retransmit
            PacketInfo pi = window.get(sequenceNumber);
            if (pi != null) {
                // exponential back-off global RTO
                long nextRTO = Math.min(RTO.get() * 2, maximumTimeOut);
                RTO.set(nextRTO);
                pi.retransmitted++;
                sendPacket(sequenceNumber);
                pi.lastSentTime = Instant.now().toEpochMilli();
                window.put(sequenceNumber, pi);
                sequenceAckCount.put(sequenceNumber, new AtomicInteger(0));
            }
        }, delay, TimeUnit.MILLISECONDS);
        timers.put(sequenceNumber, future);
    }

    /**
     * To be called once an ACK is received.
     * It cancels any timers for the packet with the specified sequence number.
     * It also updates the RTO if the packet was not retransmitted.
     *
     * @param seqNum the sequence number of the packet that was ACKed.
     */
    public void onAck(int seqNum) {
        // This function still has no way to deal with duplicate ACKs
        int totalACKs = sequenceAckCount.computeIfAbsent(seqNum, k -> new AtomicInteger(0)).incrementAndGet();


        if (totalACKs == 1) {
            // increment the total packets sent
            this.totalPacketsSent.incrementAndGet();
        }
        // 1) cancel that packet’s timer
        ScheduledFuture<?> task = this.timers.remove(seqNum);

        if (task != null) {
            task.cancel(false);
        }

        PacketInfo pi = this.window.get(seqNum);

        // 2) RTT update (Karn’s rule: only if attempts == 1, i.e. retransmitted == 0)
        if (pi != null && pi.retransmitted == 0) {
            long sampleRTT = Instant.now().toEpochMilli() - pi.lastSentTime;
            double sigma = computeSigma(sampleRTT);
            this.updateSmoothedRTT(sampleRTT);
            this.updateRTTVar(sigma);
            this.updateRTO();
        }

        // remove the packet from the window.
        this.window.remove(seqNum);

        // If the packet timed-out we do multiplicative decrease
        // We also duplicate the RTO
        if ((pi != null && pi.retransmitted != 0) || totalACKs > 3) {
            int currentCongestionWindowSize = this.congestionWindowSize.get();
            // set ssthreshold to cwnd_size
            this.slowStartThreshold.set(currentCongestionWindowSize);
            // multiply the cwnd_size by 0.5
            this.congestionWindowSize.set(currentCongestionWindowSize / 2);

            if (totalACKs > 3) {
                long nextRTO = Math.min(RTO.get() * 2, maximumTimeOut);
                RTO.set(nextRTO);
            }
        }

        // if the total packets sent are less than ssthreshold we increment the cwnd_size.
        if (this.totalPacketsSent.get() < this.slowStartThreshold.get()) {
            this.congestionWindowSize.incrementAndGet();
        }

        // else we start doing additive increase
        else {
            long totalPacksSentAfterLastCwndUpdate = this.totalPacketsSent.get() - this.prevTotalPacketsSent.get();

            // the total packets sent are the size of congestion window
            if (totalPacksSentAfterLastCwndUpdate == congestionWindowSize.get()) {
                this.congestionWindowSize.incrementAndGet();
                this.prevTotalPacketsSent.set(this.totalPacketsSent.get());
            }
        }
    }

    /**
     * Sends a packet from {@link RUDPSource#window} with the provided sequence number.
     *
     * @param sequenceNumber the sequence number of the packet to be sent.
     */
    private void sendPacket(int sequenceNumber) {
        try {
            PacketInfo packetInfo = this.window.get(sequenceNumber);
            if (packetInfo == null) {
                return;
            }
            socket.send(packetInfo.packet);
            packetInfo.lastSentTime = Instant.now().toEpochMilli();
            sequenceAckCount.put(sequenceNumber, new AtomicInteger(0));
            logger.log(Level.INFO, "[DATA TRANSMISSION]: " + packetInfo.start + " | " + packetInfo.length);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to send packet");
        }
    }

    private void shutdown() {
        this.socket.close();
        this.scheduler.shutdown();
    }

    public static void main(String[] args) throws UnknownHostException {

        // Checks if all arguments are passed to the app.
        if (args.length != 4) {
            System.out.print("Usage: RUDPSource -r <recvHost>:<recvPort> -f <fileName>");
            System.exit(1);
        }

        String recvHost = null;
        String recvPort = null;
        String fileName = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-r":
                    String[] hostPort = args[++i].split(":");
                    recvHost = hostPort[0];
                    recvPort = hostPort[1];
                    break;
                case "-f":
                    fileName = args[++i];
                    break;
            }
        }

        if (!utils.validateIp(recvHost)) {
            throw new IllegalArgumentException("Invalid Destination IP address");
        }

        if (!utils.validateInt(recvPort)) {
            throw new IllegalArgumentException("Invalid port number: " + recvPort);
        }

        InetAddress destHost = InetAddress.getByName(recvHost);

        assert recvPort != null;
        int destPort = Integer.parseInt(recvPort);

        FileProcessor fp = new FileProcessor();

        assert fileName != null;
        File file = fp.getFile(fileName);

        if (!utils.validateFile(file)) {
            throw new IllegalArgumentException("Invalid file, file either does not exist or cannot be read!");
        }

        RUDPSource rudpSource = new RUDPSource(destHost, destPort, file, RUDPSource.PORT);

        // In bytes
        int payloadLength = RUDPSource.MSS - RUDPSource.HEADER_SIZE;

        // Fill the current cwnd with packets.
        fp.fillWindow(rudpSource.window, file, 0, payloadLength, rudpSource.congestionWindowSize.get(), destHost, destPort);
        rudpSource.index = rudpSource.window.size();

        Thread sendingThread = new Thread(() -> {
            int currentSequenceNumber = 0;

            // Total packets in a file.
            long totalPackets = FileProcessor.getNumberOfPackets(file);

            // repeat until all packets are sent.
            while (rudpSource.totalPacketsSent.get() < totalPackets) {
                // check if the cwnd is empty and refill it.
                if (rudpSource.window.size() < rudpSource.congestionWindowSize.get()) {
                    rudpSource.index += fp.fillWindow(rudpSource.window, file, rudpSource.index, payloadLength, rudpSource.congestionWindowSize.get(), destHost, destPort);
                    continue;
                }

                PacketInfo packetInfo = rudpSource.window.get(currentSequenceNumber);
                if (packetInfo == null || packetInfo.lastSentTime != 0) {
                    // Packet doesn't exist in window or already sent/being handled
                    currentSequenceNumber = (currentSequenceNumber + 1) % (maximumSequenceNumber + 1); // Advance check to next potential seqNum
                    continue;
                }

                rudpSource.sendPacket(currentSequenceNumber);
                // set the time of sending the packet to the current time
                rudpSource.window.get(currentSequenceNumber).lastSentTime = Instant.now().toEpochMilli();
                rudpSource.scheduleRetransmit(currentSequenceNumber);
                // if the max sequence number is reached we wrap around.
                currentSequenceNumber = (currentSequenceNumber + 1) % (maximumSequenceNumber + 1);
            }
        });

        Thread listeningThread = new Thread(() -> {
            long totalPackets = FileProcessor.getNumberOfPackets(file);
            while (rudpSource.totalPacketsSent.get() < totalPackets) {
                DatagramPacket packet = rudpSource.receive();
                long now = Instant.now().toEpochMilli();
                if (packet == null) {
                    continue;
                }
                int seqNum = PacketProcessor.getSequenceNumber(packet);
                rudpSource.onAck(seqNum);
            }
        });

        listeningThread.start();
        sendingThread.start();

        try {
            listeningThread.join();
            sendingThread.join();
        } catch (InterruptedException e) {
            RUDPSource.logger.log(Level.SEVERE, "Interrupted while waiting for sending receiving thread", e);
        }

        fp.close();

        try {
            Thread.sleep(15000);
            int i = 3;
            // Send END Signal three times just in case it's lost
            // I'm too lazy to implement ACKs for END Signal.
            while (i-- > 0) {
                rudpSource.socket.send(PacketProcessor.buildEndPacket(destHost, destPort));
            }
        } catch (InterruptedException e) {
            logger.log(Level.SEVERE, "Failed to wait before sending END signal", e);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to send END signal!", e);
        }

        rudpSource.shutdown();
        System.exit(0);
    }
}
