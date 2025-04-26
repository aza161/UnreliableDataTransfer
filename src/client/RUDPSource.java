package client;

import java.io.IOException;
import java.net.InetAddress;

public interface RUDPSource {
    void sendFile(String filePath, InetAddress destAddress, int destPort, InetAddress channelAddress, int channelPort) throws IOException;
}
