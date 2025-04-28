package utils;

import rudp.RUDPSource;

import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.FileHandler;

/**
 * Utility class for fragmenting files into packets and computing file-related metadata
 * for reliable UDP transmission.
 * <p>
 * Provides methods to validate file existence, compute the number of packets,
 * extract payloads by index, and fill a sliding window with PacketInfo objects.
 * </p>
 */
public class FileProcessor {
    /**
     * Logger for FileProcessor events and errors.
     */
    private static final Logger logger = Logger.getLogger(FileProcessor.class.getName());

    /**
     * Utilities for file and network validations.
     */
    private static final Utils utils = new Utils();

    /**
     * Constructs a FileProcessor, initializing logging to a file.
     */
    public FileProcessor() {
        try {
            logger.addHandler(new FileHandler("FileProcessorLog.xml"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Validates and returns a File object for the given path.
     *
     * @param fileAbsolutePath the absolute path to the file
     * @return the File object if it exists and is readable, or {@code null}
     * if validation fails
     */
    public static File getFile(String fileAbsolutePath) {
        File file = new File(fileAbsolutePath);
        if (!utils.validateFile(file)) {
            logger.log(Level.SEVERE,
                    "File does not exist or cannot be read, check the path or reading permissions");
            return null;
        }
        return file;
    }

    /**
     * Computes the total number of packets needed to transmit the entire file,
     * given the protocol's payload size (MSS minus header).
     *
     * @param file the file whose size is to be fragmented into packets
     * @return the total packet count (ceil of fileSize / payloadSize)
     */
    public static long getNumberOfPackets(File file) {
        double fileSize = (double) file.length();
        int payloadSize = RUDPSource.MSS - RUDPSource.HEADER_SIZE;
        return (long) Math.ceil(fileSize / payloadSize);
    }

    /**
     * Reads the payload for a specific packet index from the file.
     *
     * @param file  the file to read from
     * @param index the zero-based packet index (offset in packets)
     * @param size  the desired payload size in bytes
     * @return a byte array containing up to {@code size} bytes of data,
     * or {@code null} if an I/O error occurs or end-of-file is reached
     */
    public static byte[] getPayload(File file, long index, int size) {
        try (FileInputStream fin = new FileInputStream(file)) {
            byte[] payload = new byte[size];
            long skipBytes = size * index;
            if (skipBytes != fin.skip(skipBytes)) {
                logger.log(Level.SEVERE, "Could not skip to the desired bytes");
                return null;
            }
            int sizeRead = fin.read(payload, 0, size);
            if (sizeRead < 0) {
                // End-of-file reached without reading data
                return null;
            }
            if (sizeRead != size) {
                byte[] trimmed = new byte[sizeRead];
                System.arraycopy(payload, 0, trimmed, 0, sizeRead);
                return trimmed;
            }
            return payload;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "File cannot be read: " + file.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * Fills the given send window map with PacketInfo objects up to the
     * specified congestion window size, starting at the given packet index.
     * Each PacketInfo is initialized with its file-offset (start) and payload length.
     *
     * @param window                the map to populate (key = sequence number)
     * @param file                  the file to fragment into packets
     * @param index                 the starting packet index (offset in packets)
     * @param size                  the payload size per packet in bytes
     * @param congestionWindowSize  the maximum number of outstanding packets
     *                              to generate
     * @param currentSequenceNumber the initial sequence number for packet keys
     * @param receiverHost          the destination IP address
     * @param receiverPort          the destination UDP port
     */
    public void fillWindow(ConcurrentHashMap<Integer, PacketInfo> window,
                           File file,
                           long index,
                           int size,
                           int congestionWindowSize,
                           int currentSequenceNumber,
                           InetAddress receiverHost,
                           int receiverPort) {
        while (window.size() < congestionWindowSize) {
            byte[] payload = getPayload(file, index, size);
            DatagramPacket packet = PacketProcessor.buildDataPacket(
                    payload, currentSequenceNumber,
                    receiverHost, receiverPort);
            PacketInfo info = new PacketInfo(packet);
            info.start = index * size;
            info.length = (payload != null ? payload.length : 0);
            window.put(currentSequenceNumber, info);
            index++;
            currentSequenceNumber = (currentSequenceNumber + 1) % (RUDPSource.maximumSequenceNumber + 1);
        }
    }
}