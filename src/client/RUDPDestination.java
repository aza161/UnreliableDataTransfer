package client;

import java.io.IOException;

public interface RUDPDestination {
    void listenAndReceive(String saveDirectory) throws IOException;
}
