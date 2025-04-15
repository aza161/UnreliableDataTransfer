package client;

import java.io.IOException;
import java.net.*;
import java.util.Scanner;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import utils.PacketGenerator;
import utils.Utils;


/**
 * UserClient is a bidirectional UPD client application that implements
 * {@link SenderClient} and {@link ReceiverClient} interfaces.
 * It sends and receives UDP packets to other clients.
 *
 * @author ahmad
 */
public class UserClient implements ReceiverClient, SenderClient {
    /**
     * The username associated with this client.
     */
    final String uName;

    /**
     * The port number on which this client listens for and sends messages.
     */
    final int portNumber;

    /**
     * {@link DatagramSocket} used for sending and receiving UDP packets.
     */
    final DatagramSocket socket;

    /**
     * The termination signal to end the communication.
     */
    final static String END_SIGNAL = "END";

    /**
     * The logger for recording client activities and debugging information.
     */
    private static final Logger logger = Logger.getLogger(UserClient.class.getName());

    private final static int MESSAGES_TO_BE_SENT = 100;

    private static final Utils utils = new Utils();

    public static void main(String[] args) throws IOException {

        // Check if all arguments are passed
        if (args.length != 8) {
            System.out.print("Usage: UserClient <userName> <portNumber> <channelIP> <channelPort> <destinationName> <destinationIP> <destinationPort> <packetDistribution>\n");
            System.exit(1);
        }

        // Validate the port number as an int
        if (!utils.validateInt(args[1])) {
            throw new IllegalArgumentException("Invalid port number: " + args[1]);
        }

        // Parse the client port number into an int
        int portNumber = Integer.parseInt(args[1]);

        // Initialize the client app
        UserClient uc = new UserClient(portNumber, args[0]);

        // Validate the channel IP
        if (!utils.validateIp(args[2])) {
            throw new IllegalArgumentException("Invalid Channel IP address");
        }

        // Create the Channel IP
        InetAddress channelAdder = InetAddress.getByName(args[2]);

        // Validate the Channel port number
        if (!utils.validatePort(Integer.parseInt(args[3]))) {
            throw new IllegalArgumentException("Invalid Channel Port number");
        }

        // Parse the channel port number into an int
        int channelPort = Integer.parseInt(args[3]);

        // The uName of the destination/receiver
        String destName = args[4];

        // Validate the destination/receiver IP
        if (!utils.validateIp(args[5])) {
            throw new IllegalArgumentException("Invalid Destination IP address");
        }

        // Create the destination/receiver IP
        InetAddress destAdder = InetAddress.getByName(args[5]);

        // Validate the destination/receiver port number
        if (!utils.validatePort(Integer.parseInt(args[6]))) {
            throw new IllegalArgumentException("Invalid Destination Port number");
        }

        // Parse the channel port number into an int
        int destPort = Integer.parseInt(args[6]);

        String distribution = args[7].toUpperCase();

        if (!utils.validateDistribution(distribution)) {
            UserClient.logger.log(Level.WARNING, "A wrong input for distribution was provided\nDefaulted to uniform distribution!");
        }

        // The alternating sequence numbers of the message
        // String[] messages = {"0", "1"};

        // A thread for sending packets
        Thread sendingThread = new Thread(() -> {
            PacketGenerator pg = new PacketGenerator(512, distribution);
            int count = 0; // Count of packets sent
            while (count < MESSAGES_TO_BE_SENT) {
                String message = pg.generate(); // random message
                // Note we are temporarily sending the messages directly to the
                // destination/receiver since we didn't implement the channel yet
                // and for test purposes
                uc.send(message, destName, destAdder, destPort, channelAdder, channelPort); // Send the message
                count++;
                try {
                    Thread.sleep(500); // Delay between each message 0.5 sec
                } catch (InterruptedException e) {
                    // If an interruption happens log it
                    UserClient.logger.log(Level.WARNING, "Sleep interrupted", e);
                    // Stop the thread
                    Thread.currentThread().interrupt();
                    // Exit the loop
                    break;
                }
            }
            // After all the MESSAGES_TO_BE_SENT are sent
            // Send end-of-transmission signal
            uc.sendEndSignal(destName, destAdder, destPort, channelAdder, channelPort);
        });

        // A thread for listening for incoming messages
        Thread receivingThread = new Thread(() -> {
            int count = 0; // Keeps track of the messages received
            while (true) {
                DatagramPacket pkt = uc.receive(); // Receive a packet
                // If the packet is null continue to the next iteration
                if (pkt == null) {
                    continue;
                }
                // Get the message from the packet
                String msg = new String(pkt.getData(), pkt.getOffset(), pkt.getLength());
                try (Scanner scanner = new Scanner(msg)) {
                    for (int i = 0; i < 4; i++) {
                        scanner.next();
                    }
                    String message = scanner.next();
                    // Check if it is end-of-transmission signal
                    if (message.equals("END")) {
                        UserClient.logger.log(Level.INFO, "Received END signal\nTotal messages received: {0}\n", String.format("%d", count));
                        break;
                    }
                    count++;
                    System.out.println(message);
                }
            }
        });

        // Start the threads
        receivingThread.start();
        UserClient.logger.log(Level.INFO, "Socket Listening on port: {0}", String.format("%d", portNumber));
        System.out.println("Press enter to start sending messages...");
        Scanner sc = new Scanner(System.in);
        sc.nextLine();
        sc.close();
        UserClient.logger.log(Level.INFO, "Started Sending Messages!");
        sendingThread.start();

        // Try to join the threads back to the main thread
        try {
            receivingThread.join();
            sendingThread.join();
        } catch (InterruptedException e) {
            // If interruption occurs log it
            UserClient.logger.log(Level.SEVERE, "Interrupted while waiting for sending receiving thread", e);
        }

        // Close the socket
        uc.close();

        // Exit with status code 0 (no errors occured)
        System.exit(0);
    }

    /**
     * Initializes a UserClient.
     *
     * @param portNumber The port number on which this client listens for and sends messages.
     * @param uName      The username associated with this client.
     */
    public UserClient(int portNumber, String uName) {

        if (!utils.validatePort(portNumber)) {
            throw new IllegalArgumentException("Invalid port number: " + portNumber);
        }

        this.portNumber = portNumber;
        this.uName = uName;

        try {
            UserClient.logger.addHandler(new FileHandler("ClientLog.xml"));
        } catch (IOException e) {
            e.printStackTrace();
        }

        DatagramSocket tempSocket = null;

        try {
            tempSocket = new DatagramSocket(portNumber);
        } catch (SocketException e) {
            UserClient.logger.log(Level.SEVERE, "Failed to initialize a socket", e);
        } finally {
            this.socket = tempSocket;
            if (tempSocket != null) {
                UserClient.logger.log(Level.INFO, "UserClient socket initiated with port number: {0}", String.format("%d", portNumber));
            }
        }

        if (this.socket == null) {
            throw new RuntimeException("Failed to initialize a socket");
        }
    }

    /**
     * Constructs a byte array message containing client metadata and payload.
     * Message format: "uName destName destAdder destPort message"
     *
     * @param message   the payload content to transmit
     * @param destAdder the destination client's IP address
     * @param destName  the destination client's username
     * @param destPort  the destination client's listening port
     * @return byte array containing formatted message data
     */
    private byte[] buildMessage(String message, InetAddress destAdder, String destName, int destPort) {
        return String.format("%s %s %s %s %s", uName, destName, destAdder.toString().substring(1), destPort, message).getBytes();
    }

    @Override
    public void send(String message, String destName, InetAddress destAdder, int destPort, InetAddress channelAdder, int channelPort) {

        // Check if all parameters are valid
        if (!utils.validatePort(destPort) || !utils.validateAddress(destAdder) || !utils.validateSocket(this.socket) || !utils.validatePort(channelPort) || !utils.validateAddress(channelAdder)) {
            return;
        }

        // Message to be sent
        byte[] buffer = buildMessage(message, destAdder, destName, destPort);

        // Create the packet
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, channelAdder, channelPort);

        // Try to send packet
        try {
            this.socket.send(packet);
        } catch (IOException e) {
            // If error is caught log it
            UserClient.logger.log(Level.SEVERE, "Failed to send message", e);
        }
    }

    @Override
    public void sendEndSignal(String destName, InetAddress destAdder, int destPort, InetAddress channelAdder, int channelPort) {

        // Check if all parameters are valid
        if (!utils.validatePort(destPort) || !utils.validateAddress(destAdder) || !utils.validateSocket(this.socket) || !utils.validatePort(channelPort) || !utils.validateAddress(channelAdder)) {
            return;
        }

        byte[] buffer = buildMessage(END_SIGNAL, destAdder, destName, destPort);
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, channelAdder, channelPort);

        // Try to send packet
        try {
            this.socket.send(packet);
            Thread.sleep(200);
        } catch (IOException e) {
            // If error is caught log it
            UserClient.logger.log(Level.SEVERE, "Failed to send message", e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
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
        } catch (IOException e) {
            // If an error is caught log it
            UserClient.logger.log(Level.SEVERE, "Failed to receive message", e);
        }
        return packet;
    }

    /**
     * Closes the socket and add info in the log.
     */
    public void close() {
        if (utils.validateSocket(this.socket)) {
            this.socket.close();
            UserClient.logger.log(Level.INFO, "Socket closed.");
        }
    }

}