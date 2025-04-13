package server;

import client.ReceiverClient;
import utils.Utils;

import java.io.IOException;
import java.net.*;
import java.util.HashMap;
import java.util.Random;
import java.util.Scanner;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Simulates an unreliable UDP network channel that receives packets from clients,
 * applies random packet dropping/delaying, and forwards them to destinations.
 */
public class UnreliableChannel implements ReceiverClient {

    /**
     * Internal data structure to track communication statistics
     * between sender-receiver pairs.
     */
    static class UserPair {
        String sender;
        String receiver;
        int delayedMessages;
        int LostMessages;
        int totalMessages;
        double averageDelay;

        public UserPair(String sender, String receiver) {
            this.sender = sender;
            this.receiver = receiver;
            this.delayedMessages = 0;
            this.LostMessages = 0;
            this.totalMessages = 0;
            this.averageDelay = 0;
        }

        @Override
        public String toString() {
            return String.format("Average delay from %s to %s: %.1f ms.\n\"Packets received from user %s: %d " + "| Lost: %d | Delayed: %d", sender, receiver, averageDelay, sender, totalMessages, LostMessages, delayedMessages);
        }
    }

    /**
     * Maps sender-receiver IP/port pairs to their communication statistics.
     */
    private final HashMap<String, UserPair> userByIPMap;

    /**
     * {@link DatagramSocket} used for sending and receiving UDP packets.
     */
    private final DatagramSocket socket;

    /**
     * The port number on which this channel listens for and sends messages.
     */
    private final int portNumber;

    /**
     * Probability of dropping packets (0.0 to 1.0).
     */
    private final double dropProbability;

    /**
     * Minimum artificial delay applied to packets (milliseconds).
     */
    private final long minDelay;

    /**
     * Maximum artificial delay applied to packets (milliseconds).
     */
    private final long maxDelay;

    /**
     * Utility instance for validation and helper methods.
     */
    private final static Utils utils = new Utils();

    /**
     * Logger for recording channel activities and debugging information.
     */
    private final java.util.logging.Logger logger = Logger.getLogger(UnreliableChannel.class.getName());

    /**
     * Buffer size for receiving UDP packets (1KB).
     */
    private static final int BUFFER_SIZE = 1024; // Buffer size for receiving packets

    /**
     * Constructs an unreliable network channel with specified configuration.
     * Initializes socket, validates parameters, and sets up logging.
     * <p>
     * The channel will:
     * <ul>
     *   <li>Listen on the specified port</li>
     *   <li>Apply packet loss based on given probability</li>
     *   <li>Introduce artificial delays within specified range</li>
     *   <li>Track communication statistics</li>
     * </ul>
     *
     * @param portNumber      Port to listen on (must be valid)
     * @param dropProbability Probability of dropping packets [0.0, 1.0]
     * @param minDelay        Minimum artificial delay (ms, >=0)
     * @param maxDelay        Maximum artificial delay (ms, >=minDelay)
     * @throws IllegalArgumentException if any parameter is invalid
     * @throws RuntimeException         if socket initialization fails
     */
    public UnreliableChannel(int portNumber, double dropProbability, long minDelay, long maxDelay) {
        this.userByIPMap = new HashMap<>();

        if (!utils.validatePort(portNumber)) {
            throw new IllegalArgumentException("Invalid port number: " + portNumber);
        }

        try {
            this.logger.addHandler(new FileHandler("UnreliableChannelLogs.txt", true));
        } catch (IOException e) {
            e.printStackTrace();
        }

        this.portNumber = portNumber;

        DatagramSocket tempSocket = null;

        try {
            tempSocket = new DatagramSocket(this.portNumber);
        } catch (SocketException e) {
            this.logger.log(Level.SEVERE, "Failed to initialize a socket", e);
        } finally {
            this.socket = tempSocket;
            if (tempSocket != null) {
                this.logger.log(Level.INFO, "UnreliableChannel socket initiated with port number: " + portNumber);
                String info = String.format("[Channel] Drop probability: %f, Delay range: %d - %d ms\n", dropProbability, minDelay, maxDelay);
                this.logger.log(Level.INFO, info);
            }
        }

        if (!utils.validateProbability(dropProbability)) {
            throw new IllegalArgumentException("Drop probability is out of range [0;1]: " + dropProbability);
        }

        this.dropProbability = dropProbability;

        if (!utils.validateDelay(minDelay)) {
            throw new IllegalArgumentException("Min delay is out of range [0;+inf): " + minDelay);
        }
        this.minDelay = minDelay;
        if (!utils.validateDelay(maxDelay)) {
            throw new IllegalArgumentException("Max delay is out of range [0;+inf): " + maxDelay);
        }

        if (minDelay > maxDelay) {
            throw new IllegalArgumentException("Min delay cannot be larger than max delay");
        }

        this.maxDelay = maxDelay;

        if (this.socket == null) {
            throw new RuntimeException("Failed to initialize a socket");
        }

        try {
            this.socket.setReceiveBufferSize(2 * 1024 * 1024);
        } catch (IOException e) {
            this.logger.log(Level.SEVERE, "Failed to set the size of the socket buffer to 2GB", e);
        }
    }

    public static void main(String[] args) throws IOException {
        // Expect exactly 4 arguments: port, dropProbability, minDelay, maxDelay
        if (args.length != 4) {
            System.out.print("Usage: UnreliableChannel <port> <dropProbability> <minDelay> <maxDelay>\n");
            System.exit(1);
        }

        // Parse arguments
        int portNumber = Integer.parseInt(args[0]);
        double dropProbability = Double.parseDouble(args[1]);
        long minDelay = Long.parseLong(args[2]);
        long maxDelay = Long.parseLong(args[3]);


        UnreliableChannel uc = new UnreliableChannel(portNumber, dropProbability, minDelay, maxDelay);

        int totalEND = 0; // Counts the total end messages received

        while (true) {
            DatagramPacket pkt = uc.receive(); // Receive a packet
            // If the packet is null continue to the next iteration
            if (pkt == null) {
                continue;
            }
            // msg format: "uName destName destAdder destPort message"
            String msg = new String(pkt.getData(), pkt.getOffset(), pkt.getLength());
            Scanner scanner = new Scanner(msg);
            String senderName = scanner.next();
            String destName = scanner.next();
            String destAdder = scanner.next();
            int destPort = scanner.nextInt();

            if (!utils.validatePort(destPort) || !utils.validateIp(destAdder)) {
                continue;
            }

            String message = scanner.next();
            String senderKey = pkt.getAddress().toString() + pkt.getPort();
            uc.userByIPMap.putIfAbsent(senderKey, new UserPair(senderName, destName));

            UserPair sender = uc.userByIPMap.get(senderKey);
            // Check if it is end-of-transmission signal
            boolean isEND = false;
            if (message.equals("END")) {
                System.out.println(sender);
                totalEND++;
                isEND = true;
            }

            uc.send(destAdder, destPort, pkt, sender, senderKey, isEND);

            if (totalEND == uc.userByIPMap.size()) {
                break;
            }
        }

        uc.close();
    }

    /**
     * Calculates updated average delay using weighted average formula.
     *
     * @param CurrentAverageDelay current average delay value
     * @param delayedMessages     number of previously delayed messages
     * @param newDelay            newly applied delay duration
     * @return updated average delay value
     */
    public double computeNewAverageDelay(double CurrentAverageDelay, int delayedMessages, double newDelay) {
        return ((delayedMessages * CurrentAverageDelay) + newDelay) / (delayedMessages + 1);
    }

    /**
     * Processes and forwards a received packet with simulated network unreliability.
     * Applies random packet dropping/delaying based on configuration parameters.
     *
     * @param destAdder destination client's IP address string
     * @param destPort  destination client's listening port
     * @param pkt       received packet to forward
     * @param sender    user pair tracking communication statistics
     * @param senderKey unique identifier for sender-receiver pair
     * @param isEND     flag indicating if this is a termination signal
     * @throws UnknownHostException if destination address is invalid
     */
    private void send(String destAdder, int destPort, DatagramPacket pkt, UserPair sender, String senderKey, boolean isEND) throws UnknownHostException {
        sender.totalMessages += 1;

        Random rand = new Random();

        double prob = rand.nextDouble();


        if (prob <= this.dropProbability && !isEND) {
            sender.LostMessages += 1;
            return;
        }

        long delay = minDelay + rand.nextLong(maxDelay - minDelay + 1);

        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            // If an interruption happens log it
            this.logger.log(Level.WARNING, "Sleep interrupted", e);
            sender.LostMessages += 1;
            return;
        }

        sender.averageDelay = computeNewAverageDelay(sender.averageDelay, sender.delayedMessages, delay);
        sender.delayedMessages += 1;

        pkt.setAddress(InetAddress.getByName(destAdder));
        pkt.setPort(destPort);

        this.userByIPMap.replace(senderKey, sender);
        // Try to send packet
        try {
            this.socket.send(pkt);
        } catch (IOException e) {
            // If error is caught log it
            this.logger.log(Level.SEVERE, "Failed to send message", e);
        }
    }

    @Override
    public DatagramPacket receive() {
        // Check if the socket is functional
        if (!utils.validateSocket(this.socket)) {
            return null;
        }

        // Create the packet
        DatagramPacket packet = new DatagramPacket(new byte[BUFFER_SIZE], BUFFER_SIZE);

        // Try receiving the packet
        try {
            this.socket.receive(packet);
        } catch (IOException e) {
            // If an error is caught log it
            this.logger.log(Level.SEVERE, "Failed to receive message", e);
        }
        return packet;
    }

    /**
     * Closes the socket and add info in the log.
     */
    public void close() {
        if (utils.validateSocket(this.socket)) {
            this.socket.close();
            this.logger.log(Level.INFO, "Socket closed.");
        }
    }
}