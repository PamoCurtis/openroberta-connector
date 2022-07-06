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

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.SystemUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fazecast.jSerialComm.SerialPort;

import de.fhg.iais.roberta.connection.wired.IWiredRobot;
import de.fhg.iais.roberta.util.Pair;

public class Mbot2Communicator {

    private static final Logger LOG = LoggerFactory.getLogger(Mbot2Communicator.class);

    private final IWiredRobot robot;

    private SerialPort serialPort;
    private final List<byte[]> payloads = new ArrayList<>();
    private final List<Byte> fileContent = new ArrayList<>();
    private String absFilePath;
    private String fileName;

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

        Pair<Integer, String> result;
        portName = (SystemUtils.IS_OS_WINDOWS ? "" : "/dev/") + portName; // to hide the parameter, which should not be used
        try {
            initSerialPort(portName);

            extractFileInformation(filePath);
            generatePayloads();

            result = sendPayload();
        } catch ( Exception io ) {
            result = new Pair<>(1, "Error while uploading file");
            return result;
        }

        return result;
    }

    private void initSerialPort(String portName) {
        if ( serialPort != null && serialPort.isOpen() ) {
            serialPort.closePort();
        }

        serialPort = SerialPort.getCommPort(portName);
        serialPort.setBaudRate(115200);
    }

    private void extractFileInformation(String filePath) throws IOException {
        File file = new File(filePath);
        this.absFilePath = file.getAbsolutePath();
        this.fileName = file.getName();
        Path path = Paths.get(this.absFilePath);
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
        byte[] modeUpload = hexStringToByteArray("F3F603000D00000DF4");
        byte[] disableReply1 = hexStringToByteArray("f3202d002804000027007472793a0a20202020696d706f727420636f6e6669670a6578636570743a0a2020202070617373b5f4");
        byte[] disableReply2 = hexStringToByteArray("f33c49002804000043007472793a0a20202020636f6e6669672e77726974655f636f6e66696728227265706c5f656e61626c65222c2046616c7365290a6578636570743a0a202020207061737389f4");
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
        payloads.add(disableReply1);
        payloads.add(disableReply2);
        payloads.add(modeUpload);
        payloads.add(disableReply1);
        payloads.add(disableReply2);

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

        byte instructionId = 0x01;
        byte fileType = 0x00;
        int fileSize = this.fileContent.size();
        byte[] sizeByte = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fileSize).array();
        byte[] checksum = xor32BitChecksum();

        data.add(fileType);
        for ( byte value : sizeByte ) {
            data.add(value);
        }
        for ( byte value : checksum ) {
            data.add(value);
        }
        for ( byte value : this.fileName.getBytes() ) {
            data.add(value);
        }
        frame.add(instructionId);
        frame.add((byte) data.size());
        frame.add((byte) 0x00); //high-order bits should be always 0
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
        int maxSize = 0x80;

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

    private byte[] xor32BitChecksum() {
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

        return checksum;
    }

    private byte calculateChecksum(List<Byte> values) {
        byte checksum = 0x00;
        for ( int value : values ) {
            checksum += value;
        }

        return checksum;
    }

    private Pair<Integer, String> sendPayload(){
        Pair<Integer, String> result = new Pair<>(0, "");
        if ( !serialPort.isOpen() ) {
            serialPort.openPort();
        }
        for ( byte[] payload : payloads ) {
            int pLength = payload.length;
            if ( serialPort.writeBytes(payload, pLength) != pLength ) {
                serialPort.closePort();
                result = new Pair<>(1, "Something went wrong while transmitting the payloads");
                break;
            }
            LOG.info(Hex.encodeHexString(payload));
            result = receiveAnswer();
            if ( result.getFirst() != 0 ) {
                serialPort.closePort();
                break;
            }
        }
        return result;
    }

    private Pair<Integer, String> receiveAnswer() {
        byte[] buf = new byte[248];
        byte[] tmp;
        byte[] possibleResp = new byte[0];
        int end;
        short bytesRead = 0;
        while ( bytesRead < buf.length ) {
            serialPort.readBytes(buf, 64);
            tmp = Arrays.copyOfRange(buf, bytesRead, bytesRead + 64);
            LOG.info(Hex.encodeHexString(tmp));
            LOG.info("read Bytes");
            bytesRead += 64;
            for ( int i = 0; i < 64; i++ ) {
                if ( possibleResp.length > 0 ) {
                    if ( tmp[i + 12 - possibleResp.length - 1] == (byte) 0xF4 ) {
                        end = i + 12 - possibleResp.length - 1;
                        possibleResp = Arrays.copyOfRange(buf, bytesRead - possibleResp.length, end + bytesRead);
                    } else {
                        possibleResp = new byte[0];
                        i = 0;
                        continue;
                    }
                } else if ( tmp[i] == (byte) 0xF3 ) {
                    if ( (i + 12) >= 64 ) {
                        end = 63;
                        possibleResp = Arrays.copyOfRange(buf, i + bytesRead, end + bytesRead);
                        break;
                    } else if ( tmp[i + 12] == (byte) 0xF4 ) {
                        end = i + 12;
                        possibleResp = Arrays.copyOfRange(buf, i + bytesRead, end + bytesRead);
                    } else {
                        continue;
                    }
                } else {
                    continue;
                }
                if ( possibleResp[7] == (byte) 0xF0 ) {
                    return possibleResp[10] == 0x00 ? new Pair<>(0, "Successfully transmitted package") : new Pair<>(1, "Error while transmitting package");
                } else {
                    possibleResp = new byte[0];
                }
            }

        }
        LOG.info("next Payload");
        return new Pair<>(0, "No answer received from the robot");
    }

    private byte[] convertArrayListToByteArray(List<Byte> arrayList) {
        byte[] result = new byte[arrayList.size()];
        for ( int i = 0; i < arrayList.size(); i++ ) {
            result[i] = arrayList.get(i);
        }
        return result;
    }

    private byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for ( int i = 0; i < len; i += 2 ) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

//    private void addValuesToData(ArrayList<Byte> data, ArrayList<Byte> values, ArrayList<Byte>... optValues) {
//        data.addAll(values);
//        if ( optValues.length > 0 ) {
//            for ( ArrayList<Byte> optValue : optValues ) {

//                data.addAll(optValue);

//            }

//        }
//    }
}
