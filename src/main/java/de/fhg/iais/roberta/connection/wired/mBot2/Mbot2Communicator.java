package de.fhg.iais.roberta.connection.wired.mBot2;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.SystemUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortInvalidPortException;

import de.fhg.iais.roberta.connection.wired.IWiredRobot;
import de.fhg.iais.roberta.util.Pair;

public class Mbot2Communicator {

    private static final Logger LOG = LoggerFactory.getLogger(Mbot2Communicator.class);

    private final IWiredRobot robot;

    private SerialPort serialPort;
    private final List<byte[]> payloads = new ArrayList<>();
    private final List<Byte> fileContent = new ArrayList<>();

    public Mbot2Communicator(IWiredRobot robot) {
        this.robot = robot;
    }

    public JSONObject getDeviceInfo() {
        JSONObject deviceInfo = new JSONObject();

        deviceInfo.put("firmwarename", this.robot.getType().toString());
        deviceInfo.put("robot", this.robot.getType().toString());
        deviceInfo.put("brickname", this.robot.getName());

        return deviceInfo;
    }

    /**
     * step 1: switch to upload mode
     * step 2: send file header data frame
     * step 3: send body header data frame
     * step 4: repeat step 3 till last frame packet is send
     * step 5: receive ok
     */
    public Pair<Integer, String> uploadFile(String portName, String filePath) {
        portName = (SystemUtils.IS_OS_WINDOWS ? "" : "/dev/") + portName; // to hide the parameter, which should not be used
        try {
            initSerialPort(portName);
            extractFileInformation(filePath);
            generatePayloads();
            return sendPayload();
        } catch ( Exception e ) {
            return new Pair<>(1, "Error while uploading file");
        }
    }

    private void initSerialPort(String portName) throws SerialPortInvalidPortException {
        if ( serialPort != null && serialPort.isOpen() ) {
            serialPort.closePort();
        }
        serialPort = SerialPort.getCommPort(portName);
        serialPort.setBaudRate(115200);
    }

    private void extractFileInformation(String filePath) throws IOException {
        File file = new File(filePath);
        Path path = Paths.get(file.getAbsolutePath());
        byte[] tmp = Files.readAllBytes(path);
        fileContent.clear();
        for ( byte value : tmp ) {
            this.fileContent.add(value);
        }
    }

    private void generatePayloads() {
        List<Byte> dataFrame = new ArrayList<>();
        List<Byte> uploadFrame = new ArrayList<>();
        List<List<Byte>> fileDataFrame = new ArrayList<>();
        byte[] modeUpload = new byte[] {(byte) 0xF3, (byte) 0xF6, 0x03, 0x00, 0x0D, 0x00, 0x00, 0x0D, (byte) 0xF4};
        byte frameHeader = (byte) 0xF3;
        byte frameFooter = (byte) 0xF4;
        byte protocolId = 0x01;
        byte deviceId = 0x00;
        byte serviceId = 0x5E;
        byte len1;
        byte len2;
        byte headerChecksum;
        byte fileDataChecksum;
        int frameSize;

        payloads.clear();
        payloads.add(modeUpload);

        fileDataFrame.add(generateHeader());
        fileDataFrame.addAll(generateBody());

        for ( List<Byte> frame : fileDataFrame ) {
            uploadFrame.add(protocolId);
            uploadFrame.add(deviceId);
            uploadFrame.add(serviceId);
            uploadFrame.addAll(frame);

            frameSize = uploadFrame.size();
            len1 = (byte) (frameSize % 256);
            len2 = (byte) (frameSize / 256);
            headerChecksum = (byte) (frameHeader + len1 + len2);
            fileDataChecksum = calculateChecksum(uploadFrame);

            dataFrame.add(frameHeader);
            dataFrame.add(headerChecksum);
            dataFrame.add(len1);
            dataFrame.add(len2);
            dataFrame.addAll(uploadFrame);
            dataFrame.add(fileDataChecksum);
            dataFrame.add(frameFooter);

            payloads.add(convertArrayListToByteArray(dataFrame));

            dataFrame.clear();
            uploadFrame.clear();
        }
    }

    /**
     * File Type = 0x00
     * File Size = 4 Byte, Size of file data
     * XOR Checksum = 4 Byte, of file data. must be padded if file data length % 4 != 0
     * File Name = max 256 Bytes. Absolute Path of file
     */
    private List<Byte> generateHeader() {
        List<Byte> frame = new ArrayList<>();
        List<Byte> data = new ArrayList<>();

        String fileName = "/flash/main.py";
        int fileSize = this.fileContent.size();
        byte instructionId = 0x01;
        byte fileType = 0x00;
        byte[] sizeByte = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fileSize).array();

        data.add(fileType);
        for ( byte value : sizeByte ) {
            data.add(value);
        }
        data.addAll(xor32BitChecksum());
        for ( byte value : fileName.getBytes() ) {
            data.add(value);
        }
        frame.add(instructionId);
        frame.add((byte) data.size());
        frame.add((byte) 0x00);
        frame.addAll(data);
        return frame;
    }

    /**
     * Sent Size = 4 Byte, size of packets that have been sent
     * File Block Data = Content of file, max 2^16 Bytes (length of instruction is 2 Bytes)
     */
    private List<List<Byte>> generateBody() {
        List<List<Byte>> bodyArr = new ArrayList<>();
        List<Byte> frame;
        List<Byte> data;

        byte[] sentDataArray;
        int dataSizeToSend;
        int maxSize = 0x40;

        for ( int sentData = 0x00; sentData < fileContent.size(); sentData += dataSizeToSend ) {
            frame = new ArrayList<>();
            data = new ArrayList<>();

            dataSizeToSend = Math.min(maxSize, this.fileContent.size()-sentData);
            sentDataArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sentData).array();
            for ( byte value : sentDataArray ) {
                data.add(value);
            }
            data.addAll(this.fileContent.subList(sentData, dataSizeToSend + sentData));
            frame.add((byte) 0x02);
            frame.add((byte) data.size());
            frame.add((byte) 0x00);
            frame.addAll(data);
            bodyArr.add(frame);
        }
        return bodyArr;
    }

    private List<Byte> xor32BitChecksum() {
        int fileSize = fileContent.size();
        byte[] checksum = new byte[] {0x00, 0x00, 0x00, 0x00};
        byte padding = (byte) (fileSize % 4);
        for ( int i = 0; i < fileSize / 4; i++ ) {
            checksum[0] ^= fileContent.get(i * 4);
            checksum[1] ^= fileContent.get(i * 4 + 1);
            checksum[2] ^= fileContent.get(i * 4 + 2);
            checksum[3] ^= fileContent.get(i * 4 + 3);
        }
        if ( padding != 0 ) {
            for ( int i = 0; i < padding; i++ ) {
                checksum[i] ^= fileContent.get(4 * (fileSize / 4) + i);
            }
        }
        return Arrays.asList(ArrayUtils.toObject(checksum));
    }

    private byte calculateChecksum(List<Byte> values) {
        byte checksum = 0x00;
        for ( int value : values ) {
            checksum += value;
        }
        return checksum;
    }

    private Pair<Integer, String> sendPayload() {
        int payloadLength;
        int writtenBytes;
        if ( !serialPort.isOpen() ) {
            serialPort.openPort();
        }
        for ( byte[] payload : payloads) {
            payloadLength = payload.length;
            writtenBytes = serialPort.writeBytes(payload, payloadLength);
            if ( writtenBytes != payloadLength || !(writtenBytes == payloadLength && receiveAnswer()) ) {
                serialPort.closePort();
                return new Pair<>(1, "Something went wrong while uploading the program. If this happens again, please reconnect the robot with the computer and try again");
            }
        }
        LOG.info("Program successfully uploaded");
        return new Pair<>(0, "Program successfully uploaded");
    }

    private boolean receiveAnswer() {
        byte[] buf = new byte[128];
        byte[] response;
        byte responseId = (byte) 0xF0;
        byte uploadModeId = 0x0D;
        byte ok = 0x00;
        long time = System.currentTimeMillis();
        while ( true ) {
            serialPort.readBytes(buf, 128);
            for ( int i = 0; i < buf.length; i++ ) {
                if ( buf[i] != 0 ) {
                    response = Arrays.copyOfRange(buf, i, buf.length - 1);
                    if ( response[0] == (byte) 0xF3 ) {
                        if ( response[i + 7] == responseId ) {
                            if ( response[i + 10] == ok ) {
                                return true;
                            } else {
                                LOG.info("Error: Package could not be delivered");
                                return false;
                            }
                        } else if ( response[i + 7] == uploadModeId ) {
                            return true;
                        }
                    }
                }
            }
            if ( (System.currentTimeMillis()) - time > 2000 ) {
                LOG.info("No response received");
                return false;
            }
        }
    }

    private byte[] convertArrayListToByteArray(List<Byte> arrayList) {
        byte[] result = new byte[arrayList.size()];
        for ( int i = 0; i < arrayList.size(); i++ ) {
            result[i] = arrayList.get(i);
        }
        return result;
    }
}
