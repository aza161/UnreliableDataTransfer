package client;

import java.net.InetAddress;

/**
 * SenderClient defines the required methods
 * for a clients that is responsible for
 * sending messages to a receiver.
 *
 * @author ahmad
 */
public interface SenderClient {

    /**
     * Sends a message to the specified destination address and port.
     *
     * @param message      the message to be transmitted.
     * @param destName     the name of the destination.
     * @param destAdder    the {@link InetAddress} of the destination.
     * @param destPort     the port number on which the receiver is listening.
     * @param channelAdder the {@link InetAddress} of the channel.
     * @param channelPort  the port number on which the channel is listening.
     */
    void send(String message, String destName, InetAddress destAdder, int destPort, InetAddress channelAdder, int channelPort);

    /**
     * Sends a message to the specified destination address and port.
     *
     * @param destName     the name of the destination.
     * @param destAdder    the {@link InetAddress} of the destination.
     * @param destPort     the port number on which the receiver is listening.
     * @param channelAdder the {@link InetAddress} of the channel.
     * @param channelPort  the port number on which the channel is listening.
     */
    void sendEndSignal(String destName, InetAddress destAdder, int destPort, InetAddress channelAdder, int channelPort);
}