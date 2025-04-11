package server;
// This program simulates an unreliable UDP network channel.
// It receives packets from two clients, randomly drops or delays them,
// and forwards them to the correct destination using dynamic IP and port data.

import java.io.IOException;
import java.net.*;
import java.util.*;

public class UnreliableChannel {

    private static final int BUFFER_SIZE = 1024; // Buffer size for receiving packets

    // Dynamic user tracking – we don’t hardcode names like "A" and "B"
    private static String userA = null;
    private static String userB = null;

    // Statistics per user direction (userA -> userB, userB -> userA)
    private static int receivedA = 0, droppedA = 0, delayedA = 0, delaySumA = 0;
    private static int receivedB = 0, droppedB = 0, delayedB = 0, delaySumB = 0;

    public static void main(String[] args) throws IOException {
        // Expect exactly 4 arguments: port, dropProbability, minDelay, maxDelay
        if (args.length != 4) {
            System.out.println("Usage: UnreliableChannel <port> <dropProbability> <minDelay> <maxDelay>");
            return;
        }

        // Parse arguments
        int port = Integer.parseInt(args[0]);
        double dropProbability = Double.parseDouble(args[1]);
        int minDelay = Integer.parseInt(args[2]);
        int maxDelay = Integer.parseInt(args[3]);

        DatagramSocket socket = new DatagramSocket(port);
        byte[] buffer = new byte[BUFFER_SIZE];
        Random rand = new Random();

        System.out.println("[Channel] Started on port " + port);
        System.out.println("[Channel] Drop probability: " + dropProbability + ", Delay range: " + minDelay + "-"
                + maxDelay + " ms");

        // Track which clients sent "END" to know when to stop
        Set<String> endedUsers = new HashSet<>();

        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet); // Wait for a UDP packet

            String msg = new String(packet.getData(), packet.getOffset(), packet.getLength());
            InetAddress senderIP = packet.getAddress();
            int senderPort = packet.getPort();

            // Handle "END" message to mark that a client has finished
            if (msg.equals("END")) {
                endedUsers.add(senderIP + ":" + senderPort);
                System.out.println("[Channel] Received END from " + senderIP + ":" + senderPort);

                if (endedUsers.size() == 2) {
                    System.out.println("[Channel] Both clients ended. Exiting.");
                    printStats(); // Show final stats before exiting
                    break;
                }
                continue;
            }

            // Expect message format: <source> <destination> <destinationIP>
            // <destinationPort> <message>
            String[] parts = msg.split(" ", 5);
            if (parts.length != 5) {
                System.out.println("[Channel] Invalid message format: " + msg);
                continue;
            }

            String source = parts[0]; // Sender name (e.g., A or B)
            String destination = parts[1]; // Destination name (e.g., B or A)
            String destIPStr = parts[2]; // Destination IP
            String destPortStr = parts[3]; // Destination port
            String message = parts[4]; // The actual payload (e.g., 0 or 1)

            boolean drop = rand.nextDouble() <= dropProbability; // Should this packet be dropped?

            // Assign sender name to userA or userB based on first occurrence
            if (userA == null || userA.equals(source)) {
                userA = source;
                if (drop)
                    droppedA++;
                else {
                    receivedA++;
                }
            } else {
                userB = source;
                if (drop)
                    droppedB++;
                else {
                    receivedB++;
                }
            }

            if (drop) {
                System.out.println("[Channel] DROPPED packet from " + source + " to " + destination + ": " + message);
                continue; // Drop the packet — don't forward
            }

            // Simulate delay (between minDelay and maxDelay)
            int delay = minDelay + rand.nextInt(maxDelay - minDelay + 1);
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                System.out.println("[Channel] Sleep interrupted");
            }

            // Add to delay statistics
            if (source.equals(userA)) {
                delaySumA += delay;
                if (delay > 0)
                    delayedA++;
            } else {
                delaySumB += delay;
                if (delay > 0)
                    delayedB++;
            }

            // Try to forward the message to the destination IP and port
            try {
                InetAddress destAddr = InetAddress.getByName(destIPStr);
                int destPort = Integer.parseInt(destPortStr);

                // Send only source/destination/message to receiving client
                String forwarded = String.format("%s %s %s", source, destination, message);
                byte[] data = forwarded.getBytes();
                DatagramPacket forwardPacket = new DatagramPacket(data, data.length, destAddr, destPort);
                socket.send(forwardPacket);

                System.out.println("[Channel] RELAYED from " + source + " to " + destination + ": '" + message
                        + "' with " + delay + " ms delay");

            } catch (Exception e) {
                System.out.println("[Channel] ERROR forwarding packet: " + e.getMessage());
            }
        }

        socket.close();
    }

    // Print final communication statistics before shutting down
    private static void printStats() {
        System.out.println("\n--- Channel Statistics ---");

        System.out.printf("Packets received from user %s: %d | Lost: %d | Delayed: %d\n",
                userA, receivedA + droppedA, droppedA, delayedA);

        System.out.printf("Packets received from user %s: %d | Lost: %d | Delayed: %d\n",
                userB, receivedB + droppedB, droppedB, delayedB);

        System.out.printf("Average delay from %s to %s: %.1f ms.\n",
                userA, userB, (receivedA == 0 ? 0 : (double) delaySumA / receivedA));

        System.out.printf("Average delay from %s to %s: %.1f ms.\n",
                userB, userA, (receivedB == 0 ? 0 : (double) delaySumB / receivedB));
    }
}

