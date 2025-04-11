package client;
// This Java program represents a user in a UDP communication system.
// It sends and receives messages through an unreliable channel that may delay or drop packets.

import java.io.IOException;
import java.net.*;

public class UserClient implements ReceiverClient, SenderClient {
    final String uName; // Client name
    final int portNumber; // Port this client listens on
    final DatagramSocket socket; // Socket for sending/receiving packets
    final static byte[] END_SIGNAL = "END".getBytes(); // Predefined end-of-session signal

    public InetAddress destinationIP; // IP address of the destination user (used in message)
    public int destinationPort; // Port number of the destination user

    public static void main(String[] args) throws IOException {
        // Check if all required arguments are provided
        if (args.length < 7) {
            System.out.print(
                    "Usage: UserClient <userName> <portNumber> <channelIP> <channelPort> <destinationName> <destinationIP> <destinationPort>\n");
            System.exit(1);
        }

        // Parse port number (argument 1)
        if (!UserClient.validateInt(args[1])) {
            throw new IllegalArgumentException("Invalid port number: " + args[1]);
        }

        int portNumber = Integer.parseInt(args[1]);
        UserClient uc = new UserClient(portNumber, args[0]); // Initialize client

        // Parse and validate channel (server) IP and port
        if (!uc.validateIp(args[2]))
            throw new IllegalArgumentException("Invalid Channel IP address");
        InetAddress channelIP = InetAddress.getByName(args[2]);

        if (!uc.validatePort(Integer.parseInt(args[3])))
            throw new IllegalArgumentException("Invalid Channel Port number");
        int channelPort = Integer.parseInt(args[3]);

        // Destination client name (e.g., A or B)
        String destinationName = args[4];

        // Destination client IP and port (for message delivery)
        if (!uc.validateIp(args[5]))
            throw new IllegalArgumentException("Invalid Destination IP address");
        InetAddress recvAddr = InetAddress.getByName(args[5]);

        if (!uc.validatePort(Integer.parseInt(args[6])))
            throw new IllegalArgumentException("Invalid Destination Port number");
        int recvPort = Integer.parseInt(args[6]);

        // Store destination address and port to use during send
        uc.destinationIP = recvAddr;
        uc.destinationPort = recvPort;

        // Sequence message content (flip between "0" and "1")
        String[] messages = { "0", "1" };

        // Sender thread
        Thread sendingThread = new Thread(() -> {
            int count = 0;

            while (count < 10) {
                String message = messages[count % 2];
                uc.send(message, destinationName, channelIP, channelPort);
                count++;
                try {
                    Thread.sleep(500); // Delay between packets
                } catch (InterruptedException e) {
                    System.out.println("[Client " + uc.uName + "] Sleep interrupted: " + e.getMessage());
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Ensure that the last message is processed by the channel before END
            try {
                Thread.sleep(1000); // Delay END Signal in case packets still in flight
            } catch (InterruptedException e) {
                System.out.println("[Client " + uc.uName + "] Delay before END interrupted");
            }

            uc.sendEndSignal(channelIP, channelPort); // Send the END signal last
        });

        // Receiver thread
        Thread receivingThread = new Thread(() -> {
            int count = 0;
            while (true) {
                DatagramPacket pkt = uc.receive(); // Receive a packet
                if (pkt == null)
                    continue;

                String msg = new String(pkt.getData(), pkt.getOffset(), pkt.getLength());

                if (msg.equals("END")) {
                    // Stop when end signal received
                    System.out.printf("[Client %s] Received END signal\nTotal messages received: %d\n", uc.uName,
                            count);
                    break;
                }

                count++;
                System.out.println("[Client " + uc.uName + "] Received: " + msg);
            }
        });

        // Start both threads
        sendingThread.start();
        receivingThread.start();

        // Wait for both threads to finish
        try {
            receivingThread.join();
            sendingThread.join();
        } catch (InterruptedException e) {
            System.out.println("[Client " + uc.uName + "] ERROR: Thread join interrupted: " + e.getMessage());
        }

        // Close socket and exit
        uc.close();
        System.exit(0);
    }

    // Constructor sets up socket and port
    public UserClient(int portNumber, String uName) {
        if (!validatePort(portNumber)) {
            throw new IllegalArgumentException("Invalid port number: " + portNumber);
        }

        this.portNumber = portNumber;
        this.uName = uName;

        DatagramSocket tempSocket = null;
        try {
            tempSocket = new DatagramSocket(portNumber); // Bind socket to port
        } catch (SocketException e) {
            System.out.println("[Client " + uName + "] ERROR: Failed to initialize socket: " + e.getMessage());
        } finally {
            this.socket = tempSocket;
            if (tempSocket != null) {
                System.out.println("[Client " + uName + "] Socket initiated on port " + portNumber);
            }
        }

        if (this.socket == null) {
            throw new RuntimeException("Failed to initialize socket");
        }
    }

    // Validate port is within allowed range
    private boolean validatePort(int port) {
        if (port < 1025 || port > 65535) {
            System.out.println("[Client " + uName + "] Port out of range: " + port);
            return false;
        }
        return true;
    }

    // Check if a string is a valid IP
    private boolean validateIp(String ip) {
        try {
            InetAddress.getByName(ip);
            return true;
        } catch (Exception e) {
            System.out.println("[Client " + uName + "] Invalid IP: " + ip);
            return false;
        }
    }

    // Ensure IP address is not null
    private boolean validateAddress(InetAddress address) {
        return address != null;
    }

    // Check if socket is usable
    private boolean validateSocket(DatagramSocket socket) {
        return socket != null && !socket.isClosed();
    }

    // Validate integer string
    private static boolean validateInt(String number) {
        try {
            Integer.parseInt(number);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // Send a message to the channel
    @Override
    public void send(String message, String destName, InetAddress channelIP, int channelPort) {
        if (!validatePort(channelPort) || !validateAddress(channelIP) || !validateSocket(this.socket))
            return;

        // Format: <uName> <destName> <destIP> <destPort> <message>
        String messageToSend = String.format("%s %s %s %d %s",
                uName,
                destName,
                destinationIP.getHostAddress(),
                destinationPort,
                message);

        byte[] buffer = messageToSend.getBytes();
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, channelIP, channelPort);

        try {
            this.socket.send(packet);
            System.out.println("[Client " + uName + "] Sent to channel: " + messageToSend);
        } catch (IOException e) {
            System.out.println("[Client " + uName + "] ERROR: Failed to send message: " + e.getMessage());
        }
    }

    // Send END signal to server
    public void sendEndSignal(InetAddress channelIP, int channelPort) {
        if (!validatePort(channelPort) || !validateAddress(channelIP) || !validateSocket(this.socket))
            return;

        DatagramPacket packet = new DatagramPacket(END_SIGNAL, END_SIGNAL.length, channelIP, channelPort);
        try {
            this.socket.send(packet);
            System.out.println("[Client " + uName + "] Sent END signal to channel.");
        } catch (IOException e) {
            System.out.println("[Client " + uName + "] ERROR: Failed to send END signal: " + e.getMessage());
        }
    }

    // Receive a packet
    public DatagramPacket receive() {
        if (!validateSocket(this.socket))
            return null;

        DatagramPacket packet = new DatagramPacket(new byte[1024], 1024);
        try {
            this.socket.receive(packet);
        } catch (IOException e) {
            System.out.println("[Client " + uName + "] ERROR: Failed to receive message: " + e.getMessage());
        }
        return packet;
    }

    // Close the socket
    public void close() {
        if (validateSocket(this.socket)) {
            this.socket.close();
            System.out.println("[Client " + uName + "] Socket closed.");
        }
    }
}
