package client;

import java.io.IOException;
import java.net.*;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;


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
    final static byte[] END_SIGNAL = "END".getBytes();

    /**
     * The logger for recording client activities and debugging information.
     */
    private final Logger logger = Logger.getLogger(UserClient.class.getName());

    public static void main(String[] args) throws IOException {

        // Check if all arguments are passed
        if (args.length < 7) {
            System.out.print("Usage: UserClient <userName> <portNumber> <channelIP> <channelPort> <destinationName> <destinationIP> <destinationPort>\n");
            System.exit(1);
        }

        // Validate the port number as an int
        if (!UserClient.validateInt(args[1])) {
            throw new IllegalArgumentException("Invalid port number: " + args[1]);
        }

        // Parse the client port number into an int
        int portNumber = Integer.parseInt(args[1]);

        // Initialize the client app
        UserClient uc = new UserClient(portNumber, args[0]);

        // Validate the channel IP
        if (!uc.validateIp(args[2])) {
            throw new IllegalArgumentException("Invalid Channel IP address");
        }

        // Create the Channel IP
        InetAddress channelIP = InetAddress.getByName(args[2]);

        // Validate the Channel port number
        if (!uc.validatePort(Integer.parseInt(args[3]))) {
            throw new IllegalArgumentException("Invalid Channel Port number");
        }

        // Parse the channel port number into an int
        int channelPort = Integer.parseInt(args[3]);

        // The uName of the destination/receiver
        String destinationName = args[4];

        // Validate the destination/receiver IP
        if (!uc.validateIp(args[5])) {
            throw new IllegalArgumentException("Invalid Destination IP address");
        }

        // Create the destination/receiver IP
        InetAddress recvAdder = InetAddress.getByName(args[5]);

        // Validate the destination/receiver port number
        if (!uc.validatePort(Integer.parseInt(args[6]))) {
            throw new IllegalArgumentException("Invalid Destination Port number");
        }

        // Parse the channel port number into an int
        int recvPort = Integer.parseInt(args[6]);

        // The alternating sequence numbers of the message
        String[] messages = {"0", "1"};

        // A thread for sending packets
        Thread sendingThread = new Thread(() -> {
            int count = 0; // Count of packets sent
            while (count < 1000) {
                String message = messages[count % 2]; // Alternate the sequence number
                // Note we are temporarily sending the messages directly to the
                // destination/receiver since we didn't implement the channel yet
                // and for test purposes
                uc.send(message, destinationName, recvAdder, recvPort); // Send the message
                count++;
                try {
                    Thread.sleep(500); // Delay between each message 0.5 sec
                } catch (InterruptedException e) {
                    // If an interruption happens log it
                    uc.logger.log(Level.WARNING, "Sleep interrupted", e);
                    // Stop the thread
                    Thread.currentThread().interrupt();
                    // Exit the loop
                    break;
                }
            }
            // After all the 1000 packets are sent
            // Send end-of-transmission signal
            uc.sendEndSignal(recvAdder, recvPort);
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
                // Check if it is end-of-transmission signal
                if (msg.equals("END")) {
                    System.out.printf("Received END signal\nTotal messages received: " + count);
                    break;
                }
                count++;
                System.out.println(msg);
            }
        });

        // Start the threads
        sendingThread.start();
        receivingThread.start();

        // Try to join the threads back to the main thread
        try {
            receivingThread.join();
            sendingThread.join();
        } catch (InterruptedException e) {
            // If interruption occurs log it
            uc.logger.log(Level.SEVERE, "Interrupted while waiting for sending\\receiving thread", e);
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

        if (!validatePort(portNumber)) {
            throw new IllegalArgumentException("Invalid port number: " + portNumber);
        }

        this.portNumber = portNumber;
        this.uName = uName;

        try {
            this.logger.addHandler(new FileHandler("ClientLog.txt", true));
        } catch (IOException e) {
            e.printStackTrace();
        }

        DatagramSocket tempSocket = null;

        try {
            tempSocket = new DatagramSocket(portNumber);
        } catch (SocketException e) {
            this.logger.log(Level.SEVERE, "Failed to initialize a socket", e);
        } finally {
            this.socket = tempSocket;
            if (tempSocket != null) {
                this.logger.log(Level.INFO, "UserClient socket initiated with port number: " + portNumber);
            }
        }

        if (this.socket == null) {
            throw new RuntimeException("Failed to initialize a socket");
        }
    }

    /**
     * Checks if the port number is valid
     * and within it's certain bounds and
     * is not from the set of reserved ports
     *
     * @param port The port number to be validated.
     * @return true if the port is valid otherwise false
     */
    private boolean validatePort(int port) {
        if (port < 1025 || port > 65535) {
            this.logger.log(Level.SEVERE, "Port out of range (0 - 65535): " + port);
            return false;
        }
        return true;
    }


    /**
     * Checks if the ip is a valid {@link InetAddress}
     *
     * @param ip The ip address to be validated.
     * @return true if the ip is valid otherwise false
     */
    private boolean validateIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            this.logger.log(Level.SEVERE, "Invalid IP address: " + ip);
        }
        try {
            InetAddress.getByName(ip);
            return true;
        } catch (Exception e) {
            this.logger.log(Level.SEVERE, "Invalid IP address: " + ip);
            return false;
        }
    }


    /**
     * Checks if the ip is a valid {@link InetAddress}
     *
     * @param address The ip address to be validated.
     * @return true if the ip is valid otherwise false
     */
    private boolean validateAddress(InetAddress address) {
        if (address == null) {
            this.logger.log(Level.SEVERE, "recvAdder is null");
            return false;
        }
        return true;
    }

    /**
     * Checks if the {@link DatagramSocket} is functional
     * and not closed or null.
     *
     * @param socket The {@link DatagramSocket} to be validated.
     * @return true if the socket is functional otherwise false
     */
    private boolean validateSocket(DatagramSocket socket) {
        if (socket == null || socket.isClosed()) {
            this.logger.log(Level.SEVERE, "UserClient socket is closed");
            return false;
        }
        return true;
    }

    /**
     * Checks if the {@link String} is a valid
     * integer.
     *
     * @param number The {@link String} to be validated.
     * @return true if the String is an integer otherwise false
     */
    private static boolean validateInt(String number) {
        try {
            int num = Integer.parseInt(number);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public void send(String message, String destName, InetAddress recvAdder, int port) {

        // Check if all parameters are valid
        if (!validatePort(port) || !validateAddress(recvAdder) || !validateSocket(this.socket)) {
            return;
        }

        // Construct the message in the form "uName destName msg"
        // TO-DO: Later the message should include the destination IP and Port number
        // So the channel knows where to send the message
        String messageToSend = String.format("%s %s %s", uName, destName, message);
        byte[] buffer = messageToSend.getBytes();

        // Create the packet
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, recvAdder, port);

        // Try to send packet
        try {
            this.socket.send(packet);
        } catch (IOException e) {
            // If error is caught log it
            this.logger.log(Level.SEVERE, "Failed to send message", e);
        }
    }

    public void sendEndSignal(InetAddress recvAdder, int port) {

        // Check if all parameters are valid
        if (!validatePort(port) || !validateAddress(recvAdder) || !validateSocket(this.socket)) {
            return;
        }

        // Create the packet
        // TO-DO: Later the message should include the destination IP and Port number
        // So the channel knows where to send the message
        DatagramPacket packet = new DatagramPacket(END_SIGNAL, END_SIGNAL.length, recvAdder, port);

        // Try to send packet
        try {
            this.socket.send(packet);
        } catch (IOException e) {
            // If error is caught log it
            this.logger.log(Level.SEVERE, "Failed to send message", e);
        }
    }

    public DatagramPacket receive() {

        // Check if the socket is functional
        if (!validateSocket(this.socket)) {
            return null;
        }

        // Create the packet
        DatagramPacket packet = new DatagramPacket(new byte[1024], 1024);

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
        if (validateSocket(this.socket)) {
            this.socket.close();
            this.logger.log(Level.INFO, "Socket closed.");
        }
    }

}