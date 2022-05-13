package de.fhg.iais.roberta.connection.wired.spikeHub;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fazecast.jSerialComm.SerialPort;

import de.fhg.iais.roberta.connection.wired.IWiredRobot;
import de.fhg.iais.roberta.util.Pair;


public class SpikeHubCommunicator {
    private static final Logger LOG = LoggerFactory.getLogger(SpikeHubCommunicator.class);

    private final IWiredRobot robot;
    private final int slotId = 0;
    private final byte end = 0x0D;

    private SerialPort comPort;

    SpikeHubCommunicator(IWiredRobot robot) {
        this.robot = robot;
    }

    public Pair<Integer, String> handleUpload(String portName, String absolutePath) {
        initSerialPort(portName);
        try {
            JSONObject payload;
            payload = createJsonPayload("stop_execution", absolutePath);
            uploadPayload(payload);

            payload = createJsonPayload("start_write_program", absolutePath);
            uploadPayload(payload);

            payload = createJsonPayload("write_package", absolutePath);
            uploadPayload(payload);

        } catch ( Exception e ) {
            return new Pair<>(1, "Something went wrong while uploading the file.");
        }

        //--> size      = get size of file in bytes
        //--> name      = get file name
        //--> nowTime   = get time for file creation and modified timestamp
        //--> slotID    = get slotID where program will be stored or use always same slotID 0
        //--> id        = random id of length 4

        //create JSON payload
        //  {'m':'start_write_program', 'p': {'slotid':slotID, 'size': size, 'meta': {'created': nowTime, 'modified': nowTime, 'name': name, 'type': 'python', 'project_id': '50uN1ZaRpHj2'}}, 'i': id}

        //serialize JSON payload and utf-8 encode it
        //write it + byte value "0x0d" on the serial port
        //  default serial port = ttymACM0
        //  baudrate            = 115200

        //wait for response from hub

        return new Pair<>(1, "Test");
    }

    JSONObject getDeviceInfo() {
        JSONObject deviceInfo = new JSONObject();

        deviceInfo.put("firmwarename", this.robot.getType().toString());
        deviceInfo.put("robot", this.robot.getType().toString());
        return deviceInfo;
    }

    private JSONObject createJsonPayload(String mode, String absolutePath) throws Exception {
        return createJsonPayload(mode, absolutePath, new JSONObject());
    }

    private JSONObject createJsonPayload(String mode, String absolutePath, JSONObject optParams) {
        JSONObject payload = new JSONObject();
        JSONObject params = new JSONObject();
        String id = RandomStringUtils.randomAlphanumeric(4);

        try {
            File file = new File(absolutePath);
            Path path = Paths.get(absolutePath);
            switch ( mode ) {
                case "start_write_program":
                    JSONObject meta = new JSONObject();
                    long size = Files.size(path);
                    long nowTime = System.currentTimeMillis() / 1000;

                    meta.put("created", nowTime);
                    meta.put("modified", nowTime);
                    meta.put("name", file.getName());
                    meta.put("type", "python");
                    meta.put("project_id", "50uN1ZaRpHj2");

                    params.put("slotid", slotId);
                    params.put("size", size);
                    params.put("meta", meta);
                case "write_package":
                    byte[] encodedFile = encodeFileToBase64Binary(file);
                    params.put("data", encodedFile);
                    params.put("transferid", optParams.getString("transferid"));
                case "program_terminate":
                case "program_execute":
                default:
                    payload.put("m", mode);
                    payload.put("p", params);
                    payload.put("i", id);
            }
            LOG.info("JSON Payload: {}", payload);

        } catch ( Exception e ) {
            LOG.error("Error while creating Payload for Spike Hub: {}", e.getMessage());
        }
        return payload;
    }

    private JSONObject uploadPayload(JSONObject payload) throws IOException {
        OutputStream outputStream = this.comPort.getOutputStream();
        byte[] bytePayload = payload.toString().getBytes(StandardCharsets.UTF_8);
        outputStream.write(bytePayload);
        outputStream.write(end);
        outputStream.close();
        return receiveResponse(payload.getString("i"));
    }

    private JSONObject receiveResponse(String id) throws IOException {

        while ( true ) {
            JSONObject response = receiveMessage(1);

            if ( response.has("i") && response.getString("i").equals(id) ) {
                if ( response.has("e") ) {
                    return null;
                }
                LOG.info(response.getString("r"));
                return response.getJSONObject("r");

            }
            LOG.info("Response while waiting: {}", response);
        }
    }

    private JSONObject receiveMessage(int timeout) throws IOException {
        InputStream inputStream = this.comPort.getInputStream();
        long startTime = System.currentTimeMillis() / 1000;
        int elapsed = 0;
        byte[] receiveBuffer;
        String result;

        while ( true ) {
            receiveBuffer = new byte[inputStream.available()];
            int pos = findEndValue(receiveBuffer);

            if ( pos >= 0 ) {
                result = receiveBuffer.toString().substring(0, pos);

                try {
                    JSONObject response = new JSONObject(result);
                    return response;
                } catch ( JSONException e ) {
                    LOG.error("Cannot parse JSON: {}", result);
                }

            }

            if ( pos >= inputStream.available() ) {
                inputStream.read(receiveBuffer);
            }
        }
        return null;
    }

    private int findEndValue(byte[] receiveBuffer) {
        for ( int pos = 0; pos < receiveBuffer.length; pos++ ) {
            if ( Byte.compare(receiveBuffer[pos], end) == 0 ) {
                return pos;
            }
        }
        return -1;
    }

    private byte[] encodeFileToBase64Binary(File file) {
        byte[] encodedFile = new byte[] {};
        try {
            encodedFile = Base64.getEncoder().encode(FileUtils.readFileToByteArray(file));
        } catch ( IOException e ) {
            LOG.error("Error while encoding file");
        }
        return encodedFile;
    }

    private void initSerialPort(String portName) {
        portName = (SystemUtils.IS_OS_WINDOWS ? "" : "/dev/") + portName; // to hide the parameter, which should not be used
        this.comPort = SerialPort.getCommPort(portName);
        this.comPort.setBaudRate(115200);
        this.comPort.openPort(0);
        LOG.info("Serial Communication is initialized: {} {} {}",
            this.comPort.getSystemPortName(),
            this.comPort.getDescriptivePortName(),
            this.comPort.getPortDescription());
    }

    private byte[] receiveResponse() {

        return null;
    }

}
