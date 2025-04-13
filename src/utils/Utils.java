package utils;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class providing validation methods and helper functions
 * for network operations and parameter checking.
 */
public class Utils {

    /**
     * Logger for recording utility operations and validation errors.
     */
    private final java.util.logging.Logger logger = Logger.getLogger(Utils.class.getName());

    public Utils() {
        try {
            this.logger.addHandler(new FileHandler("UtilsLog.txt", true));
        } catch (IOException e) {
            e.printStackTrace();
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
    public boolean validatePort(int port) {
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
    public boolean validateIp(String ip) {
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
    public boolean validateAddress(InetAddress address) {
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
    public boolean validateSocket(DatagramSocket socket) {
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
    public boolean validateInt(String number) {
        try {
            int num = Integer.parseInt(number);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Validates probability value range.
     * Acceptable values: [0;1]
     *
     * @param probability Value to validate
     * @return true if within valid range, false otherwise
     */
    public boolean validateProbability(double probability) {
        if (probability < 0 || probability > 1) {
            this.logger.log(Level.SEVERE, "Probability out of range: " + probability);
            return false;
        }
        return true;
    }

    /**
     * Checks if delay value is non-negative.
     * Valid delays: delay >= 0 milliseconds.
     *
     * @param delay Value to validate
     * @return true if valid delay, false otherwise
     */
    public boolean validateDelay(long delay) {
        if (delay < 0) {
            this.logger.log(Level.SEVERE, "Delay out of range (0-inf): " + delay);
            return false;
        }
        return true;
    }
}