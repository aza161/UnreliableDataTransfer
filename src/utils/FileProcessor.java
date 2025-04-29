package utils;

import rudp.RUDPSource;

import java.io.*;
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
     * File output stream for writing the received file.
     */
    private FileOutputStream fos = null;

    /**
     * Buffered stream to improve file writing efficiency.
     */
    private BufferedOutputStream bos = null;

    /**
     * File input stream for reading the file to be sent.
     */
    private FileInputStream fis = null;

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
    public File getFile(String fileAbsolutePath) {
        File file = new File(fileAbsolutePath);
        try {
            this.fis = new FileInputStream(file);
        } catch (FileNotFoundException e) {
            logger.log(Level.SEVERE, "File Not found: " + fileAbsolutePath);
            throw new IllegalArgumentException("File provided is not accessible");
        }
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
    public byte[] getPayload(File file,
                             long index,
                             int size) {
        try {
            byte[] payload = new byte[size];
            int sizeRead = fis.read(payload, 0, size);
            if (sizeRead < 0) {
                // End-of-file reached without reading data
                return null;
            }
            if (sizeRead < size) {
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
     * @param window               the map to populate (key = sequence number)
     * @param file                 the file to fragment into packets
     * @param index                the starting packet index (offset in packets)
     * @param size                 the payload size per packet in bytes
     * @param congestionWindowSize the maximum number of outstanding packets
     *                             to generate
     * @param receiverHost         the destination IP address
     * @param receiverPort         the destination UDP port
     * @return the total packets added.
     */
    public int fillWindow(ConcurrentHashMap<Integer, PacketInfo> window,
                          File file,
                          long index,
                          int size,
                          int congestionWindowSize,
                          InetAddress receiverHost,
                          int receiverPort) {
        int total = 0; // Total packets processed
        while (window.size() < congestionWindowSize) {
            int currentSequenceNumber = (int) (index % (RUDPSource.maximumSequenceNumber + 1));
            if (window.containsKey(currentSequenceNumber)) {
                return total;
            }
            byte[] payload = getPayload(file, index, size);
            DatagramPacket packet = PacketProcessor.buildDataPacket(
                    payload, currentSequenceNumber,
                    receiverHost, receiverPort);
            PacketInfo info = new PacketInfo(packet);
            info.start = index * size;
            info.length = (payload != null ? payload.length : 0);
            window.put(currentSequenceNumber, info);
            index++;
            total++;
        }
        return total;
    }

    /**
     * Initializes the output file for writing received chunks.
     *
     * @param filename Full path to the output file to be created
     */
    public void startReconstruction(String filename) {
        try {
            File outFile = new File(filename);
            fos = new FileOutputStream(outFile);
            bos = new BufferedOutputStream(fos);
            logger.info("[FILE CREATED] " + filename);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to create output file: " + filename, e);
        }
    }

    /**
     * Writes a chunk of received payload to the file.
     *
     * @param chunkData Payload data in order
     */
    public void writeChunk(byte[] chunkData) {
        try {
            if (bos != null) {
                bos.write(chunkData);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error writing chunk to file", e);
        }
    }

    /**
     * Finalizes and closes the file output stream.
     */
    public void close() {
        try {
            if (bos != null) {
                bos.flush();
                bos.close();
            }
            if (fos != null) {
                fos.flush();
                fos.close();
                logger.info("[FILE SAVED] Output file closed.");
            }
            if (fis != null) {
                fis.close();
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error closing file output stream", e);
        }
    }
}