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
     * @param message   the message to be transmitted.
     * @param destName  the name of the destination.
     * @param recvAdder the {@link InetAddress} of the receiver.
     * @param port      the port number on which the receiver is listening.
     */
    void send(String message, String destName, InetAddress recvAdder, int port);

    /**
     * Sends an end-of-transmission message to the
     * specified destination address and port
     * to stop waiting for further messages.
     *
     * @param recvAdder the {@link InetAddress} of the receiver.
     * @param port      the port number on which the receiver is listening.
     */
    void sendEndSignal(InetAddress recvAdder, int port);
}