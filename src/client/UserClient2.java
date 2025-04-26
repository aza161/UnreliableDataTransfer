package client;

import utils.Utils;

import java.io.*;
import java.net.*;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

// Implements both sender and receiver functionality for reliable UDP file transfer
public class UserClient2 implements RUDPSource, RUDPDestination {

    private final String UserName;
    private final int port;
    private final DatagramSocket socket;
    private static final Logger logger = Logger.getLogger(UserClient2.class.getName());

    private static final int PACKET_SIZE = 1024;  // Total UDP packet size (header + data)
    private static final int DATA_SIZE = 900;     // Maximum data bytes per packet (to leave space for headers)
    private static final int TIMEOUT = 1000;      // Socket timeout in ms for ACK wait

    // Constructor
    public UserClient2(String UserName, int port) {
        this.UserName = UserName;
        this.port = port;

        DatagramSocket tempSocket = null;
        try {
            tempSocket = new DatagramSocket(port);        // Bind to given port
            tempSocket.setSoTimeout(TIMEOUT);             // Set timeout for receiving
            logger.addHandler(new FileHandler("USerLog.xml"));
            logger.log(Level.INFO, "User initialized on port: {0}", port);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize user socket", e);
        }

        this.socket = tempSocket;
    }

    // Send a file reliably using sequence numbers and ACKs
    @Override
    public void sendFile(String filePath, InetAddress destAddress, int destPort, InetAddress channelAddress, int channelPort) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            logger.log(Level.SEVERE, "File not found: {0}", filePath);
            return;
        }

        FileInputStream fis = new FileInputStream(file);
        byte[] buffer = new byte[DATA_SIZE];
        int seqNum = 0;

        // Send filename first with a special header
        String fileNameMsg = "FILENAME|" + file.getName();
        sendPacket(fileNameMsg.getBytes(), seqNum, destAddress, destPort, channelAddress, channelPort);
        waitForAck(seqNum); // Wait for ACK before continuing
        seqNum++;

        int bytesRead;
        while ((bytesRead = fis.read(buffer)) != -1) {
            // Copy read data into a trimmed byte array
            byte[] data = new byte[bytesRead];
            System.arraycopy(buffer, 0, data, 0, bytesRead);

            // Build packet header with sequence number
            String header = "DATA|" + seqNum + "|";
            byte[] fullPacket = combine(header.getBytes(), data); // Combine header and data

            boolean acked = false;
            // Retransmit until ACK is received
            while (!acked) {
                sendPacket(fullPacket, seqNum, destAddress, destPort, channelAddress, channelPort);
                acked = waitForAck(seqNum);
            }

            seqNum++;
        }

        fis.close();

        // Send end-of-file signal
        String eofMsg = "EOF|" + seqNum;
        boolean eofAcked = false;
        while (!eofAcked) {
            sendPacket(eofMsg.getBytes(), seqNum, destAddress, destPort, channelAddress, channelPort);
            eofAcked = waitForAck(seqNum);
        }

        System.out.println("[COMPLETE]");
    }

    // Listens and receives a file reliably, writing it to the given directory
    @Override
    public void listenAndReceive(String saveDirectory) throws IOException {
        Set<Integer> receivedSeq = new HashSet<>(); // To track which packets were received
        FileOutputStream fos = null;
        String fileName = "received-copy";
        boolean running = true;

        while (running) {
            try {
                // Wait for an incoming packet
                DatagramPacket packet = new DatagramPacket(new byte[PACKET_SIZE], PACKET_SIZE);
                socket.receive(packet);

                String msg = new String(packet.getData(), 0, packet.getLength());

                // First packet contains the filename
                if (msg.startsWith("FILENAME|")) {
                    fileName = msg.split("\\|")[1];

                    // Add "-copy" before the extension
                    int dotIndex = fileName.lastIndexOf('.');
                    if (dotIndex != -1) {
                        fileName = fileName.substring(0, dotIndex) + "-copy" + fileName.substring(dotIndex);
                    } else {
                        fileName = fileName + "-copy.dat";
                    }

                    File outFile = new File(saveDirectory, fileName);

                    fos = new FileOutputStream(outFile);
                    sendAck(0, packet.getAddress(), packet.getPort());
                    System.out.println("[DATA RECEPTION]: FILENAME | OK");
                    continue;
                }

                // Handle end of file
                if (msg.startsWith("EOF|")) {
                    int eofSeq = Integer.parseInt(msg.split("\\|")[1]);
                    sendAck(eofSeq, packet.getAddress(), packet.getPort());
                    System.out.println("[COMPLETE]");
                    break;
                }

                // Handle data packets
                if (msg.startsWith("DATA|")) {
                    String[] parts = msg.split("\\|", 3);
                    int seq = Integer.parseInt(parts[1]);

                    // If already received, discard and ACK again
                    if (receivedSeq.contains(seq)) {
                        System.out.println("[DATA RECEPTION]: " + seq + " | DISCARDED");
                        sendAck(seq, packet.getAddress(), packet.getPort());
                        continue;
                    }

                    // Extract and write data
                    String dataStr = parts[2];
                    byte[] data = dataStr.getBytes();
                    if (fos != null) {
                        fos.write(data);
                        fos.flush();
                    }

                    receivedSeq.add(seq);
                    sendAck(seq, packet.getAddress(), packet.getPort());
                    System.out.println("[DATA RECEPTION]: " + seq + " | OK");
                }

            } catch (SocketTimeoutException e) {
                // Timeout reached, keep listening
            }
        }

        if (fos != null) {
            fos.close();
        }
    }

    // Sends a data packet through the channel to the destination
    private void sendPacket(byte[] data, int seqNum, InetAddress destAddr, int destPort, InetAddress channelAddr, int channelPort) {
        DatagramPacket packet = new DatagramPacket(data, data.length, channelAddr, channelPort);
        try {
            socket.send(packet);
            System.out.println("[DATA TRANSMISSION]: " + seqNum + " | " + data.length);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to send packet", e);
        }
    }

    // Waits for an ACK with a matching sequence number
    private boolean waitForAck(int expectedSeq) {
        byte[] buf = new byte[PACKET_SIZE];
        DatagramPacket ackPkt = new DatagramPacket(buf, buf.length);
        try {
            socket.receive(ackPkt);
            String msg = new String(ackPkt.getData(), 0, ackPkt.getLength());
            if (msg.startsWith("ACK|")) {
                int ackNum = Integer.parseInt(msg.split("\\|")[1]);
                if (ackNum == expectedSeq) {
                    System.out.println("[ACK RECEIVED]: " + ackNum);
                    return true;
                }
            }
        } catch (SocketTimeoutException e) {
            System.out.println("[TIMEOUT] waiting for ACK " + expectedSeq + ", will retransmit.");
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error receiving ACK", e);
        }
        return false;
    }

    // Sends an ACK packet for the given sequence number
    private void sendAck(int seqNum, InetAddress addr, int port) {
        String ackMsg = "ACK|" + seqNum;
        byte[] ackBytes = ackMsg.getBytes();
        DatagramPacket ackPkt = new DatagramPacket(ackBytes, ackBytes.length, addr, port);
        try {
            socket.send(ackPkt);
            System.out.println("[ACK SENT]: " + seqNum);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to send ACK", e);
        }
    }

    // Combines header and data into one byte array
    private byte[] combine(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    public static void main(String[] args) throws IOException {

        // Check if all arguments are passed
        if (args.length != 7) {
            System.out.print("Usage: UserClient2 <userName> <portNumber> <channelIP> <channelPort> <destinationName> <destinationIP> <destinationPort>\n");
            System.exit(1);
        }

        Utils utils = new Utils();

        // Validate and parse the port number
        if (!utils.validateInt(args[1])) {
            throw new IllegalArgumentException("Invalid port number: " + args[1]);
        }
        int portNumber = Integer.parseInt(args[1]);

        // Initialize the user client
        UserClient2 uc = new UserClient2(args[0], portNumber);

        // Validate and parse channel IP
        if (!utils.validateIp(args[2])) {
            throw new IllegalArgumentException("Invalid Channel IP address");
        }
        InetAddress channelAddress = InetAddress.getByName(args[2]);

        // Validate and parse channel port
        if (!utils.validatePort(Integer.parseInt(args[3]))) {
            throw new IllegalArgumentException("Invalid Channel Port number");
        }
        int channelPort = Integer.parseInt(args[3]);

        // Destination info
        String destName = args[4];

        // Validate and parse destination IP
        if (!utils.validateIp(args[5])) {
            throw new IllegalArgumentException("Invalid Destination IP address");
        }
        InetAddress destAddress = InetAddress.getByName(args[5]);

        // Validate and parse destination port
        if (!utils.validatePort(Integer.parseInt(args[6]))) {
            throw new IllegalArgumentException("Invalid Destination Port number");
        }
        int destPort = Integer.parseInt(args[6]);

        // Thread for listening for incoming packets (receive)
        Thread receivingThread = new Thread(() -> {
            try {
                uc.listenAndReceive("."); // Save received files in current directory
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Receiving thread crashed", e);
            }
        });

        receivingThread.start();
        logger.log(Level.INFO, "Socket listening on port: {0}", portNumber);

        System.out.println("Press Enter to start sending the file...");
        Scanner sc = new Scanner(System.in);
        sc.nextLine();

        // Ask the user for the file path to send
        System.out.print("Enter the path to the file you want to send: ");
        String filePath = sc.nextLine();
        sc.close();

        // Thread for sending the file
        Thread sendingThread = new Thread(() -> {
            try {
                uc.sendFile(filePath, destAddress, destPort, channelAddress, channelPort);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Sending thread crashed", e);
            }
        });

        logger.log(Level.INFO, "Started sending the file...");
        sendingThread.start();

        // Try to join the threads back to main
        try {
            receivingThread.join();
            sendingThread.join();
        } catch (InterruptedException e) {
            logger.log(Level.SEVERE, "Main thread interrupted", e);
        }

        uc.socket.close();
        logger.log(Level.INFO, "Socket closed. Exiting...");
        System.exit(0);
    }

}
