package client;

import java.net.DatagramPacket;

/**
 * ReceiverClient defines required methods
 * for a clients that is responsible for
 * receiving messages from a sender.
 *
 * @author ahmad
 */
public interface ReceiverClient {

    /**
     * Receives a {@link DatagramPacket} from a sender.
     *
     * @return the received datagram packet containing message data.
     */
    DatagramPacket receive();
}