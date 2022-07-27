package de.fhg.iais.roberta.connection.wired.spikeHub;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private SerialPort serialPort;

    private List<JSONObject> payloads;

    private String fileContentEncoded;

    SpikeHubCommunicator(IWiredRobot robot) {
        this.robot = robot;
    }

    public Pair<Integer, String> handleUpload(String portName, String absolutePath) throws IOException {
        try {
            initSerialPort(portName);
            extractAndEncodeFileInformation(absolutePath);
            createJsonPayloads();

            sendPayloads();

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

    private void extractAndEncodeFileInformation(String filePath) throws IOException {
        File file = new File(filePath);
        Path path = Paths.get(file.getAbsolutePath());
        fileContentEncoded = Base64.getEncoder().encodeToString(FileUtils.readFileToByteArray(file));
    }

    private void createJsonPayloads() {
        payloads = new ArrayList<>();

        createProgramTerminatePayload();
        createStartWriteProgramPayload();
        createWritePackagePayload();
//        while(true){
//            JSONObject payload = new JSONObject();
//            JSONObject params = new JSONObject();
//            JSONObject meta = new JSONObject();
//            String id = RandomStringUtils.randomAlphanumeric(4);
//            long nowTime = System.currentTimeMillis() / 1000;
//
//
//            try {
//                payload.put("m", "program_terminate");
//                payload.put("p", meta);
//                payload.put("i", id);
//
//                payload.put("m", "start_write_package");
//
//                meta.put("created", nowTime);
//                meta.put("modified", nowTime);
//                meta.put("name", "NepoProg.py");
//                meta.put("type", "python");
//                meta.put("project_id", "50uN1ZaRpHj2");
//
//                params.put("slotid", slotId);
//                params.put("size", fileContent.size());
//                params.put("meta", meta);
//
//                payload.put("p", params);
//                payload.put("i", id);
//
//                payloads.add(payload);
//
//
//                switch ( mode ) {
//                    case "start_write_program":
//                        JSONObject meta = new JSONObject();
//                        long size = Files.size(path);
//                        long nowTime = System.currentTimeMillis() / 1000;
//
//
//
//                    case "write_package":
//                        byte[] encodedFile = encodeFileToBase64Binary(file);
//                        params.put("data", encodedFile);
//                        params.put("transferid", optParams.getString("transferid"));
//                    case "program_terminate":
//                    case "program_execute":
//                    default:
//
//                }
//                LOG.info("JSON Payload: {}", payload);
//
//            } catch ( Exception e ) {
//                LOG.error("Error while creating Payload for Spike Hub: {}", e.getMessage());
//            }
//        }
    }

    private void createProgramTerminatePayload() {
        JSONObject payload = new JSONObject();
        payload.put("m", "program_terminate");
        payload.put("p", new JSONObject());
        payload.put("i", RandomStringUtils.randomAlphanumeric(4));

        payloads.add(payload);
    }

    private void createStartWriteProgramPayload() {
        JSONObject payload = new JSONObject();
        JSONObject params = new JSONObject();
        JSONObject meta = new JSONObject();
        String id = RandomStringUtils.randomAlphanumeric(4);
        long nowTime = System.currentTimeMillis() / 1000;
        int slotId = 0;

        meta.put("created", nowTime);
        meta.put("modified", nowTime);
        meta.put("name", "NepoProg.py");
        meta.put("type", "python");
        meta.put("project_id", "50uN1ZaRpHj2");

        params.put("slotid", slotId);
        params.put("size", fileContentEncoded.length());
        params.put("meta", meta);

        payload.put("m", "start_write_program");
        payload.put("p", params);
        payload.put("i", id);

        payloads.add(payload);
    }

    private void createWritePackagePayload() {
        JSONObject payload;
        JSONObject param;
        List<JSONObject> paramList = new ArrayList<>();
        int maxDataSize = 512;
        int end = 0;
        int rest = 0;
        for ( int i = 0; i < fileContentEncoded.length(); i += end ) {
            param = new JSONObject();

            end = Math.min(maxDataSize, fileContentEncoded.length() - rest);
            rest += end;
            param.put("data", fileContentEncoded.substring(i, i + end));
//            param.put("transferid", RandomStringUtils.randomNumeric(5));

            paramList.add(param);
        }
        for ( JSONObject params : paramList ) {
            payload = new JSONObject();
            payload.put("m", "write_package");
            payload.put("p", params);
            payload.put("i", RandomStringUtils.randomAlphanumeric(4));

            payloads.add(payload);
        }
    }

//    private JSONObject uploadPayloads() throws IOException {
//        OutputStream outputStream = this.serialPort.getOutputStream();
//        for(JSONObject payload: payloads) {
//            byte[] bytePayload = payload.toString().getBytes(StandardCharsets.UTF_8);
//            outputStream.write(bytePayload);
//            outputStream.write(0x0D);
//            receiveResponse(payload.getString("i"));
//        }
//        return new JSONObject();
//    }

    //    private JSONObject receiveResponse(String id) throws IOException {
//
//        while ( true ) {
//            JSONObject response = receiveMessage(1);
//
//            if ( response.has("i") && response.getString("i").equals(id) ) {
//                if ( response.has("e") ) {
//                    return null;
//                }
//                LOG.info(response.getString("r"));
//                return response.getJSONObject("r");
//
//            }
//            LOG.info("Response while waiting: {}", response);
//        }
//    }
//
//    private JSONObject responseToJson(int timeout) throws IOException {
//        InputStream inputStream = this.serialPort.getInputStream();
//        long startTime = System.currentTimeMillis() / 1000;
//        int elapsed = 0;
//        byte[] receiveBuffer;
//        String result;
//
//        while ( true ) {
//            receiveBuffer = new byte[inputStream.available()];
//            int pos = findEndValue(receiveBuffer);
//
//            if ( pos >= 0 ) {
//                result = receiveBuffer.toString().substring(0, pos);
//
//                try {
//                    JSONObject response = new JSONObject(result);
//                    return response;
//                } catch ( JSONException e ) {
//                    LOG.error("Cannot parse JSON: {}", result);
//                }
//
//            }
//
//            if ( pos >= inputStream.available() ) {
//                inputStream.read(receiveBuffer);
//            }
//        }
//    }
    private Pair<Integer, String> sendPayloads() throws IOException {
        Pair<Integer, String> result = new Pair<>(0, "Program successfully uploaded");
        OutputStream out = this.serialPort.getOutputStream();

        if ( !serialPort.isOpen() ) {
            serialPort.openPort();
        }
        for ( JSONObject payload : payloads ) {
            byte[] payloadAsByteArr = payload.toString().getBytes(StandardCharsets.UTF_8);
            out.write(payloadAsByteArr, 0, payloadAsByteArr.length);
            out.write((byte) 0x0D);

            if ( !receiveAnswer(payload.getString("i")) ) {
                result = new Pair<>(1, "Something went wrong while uploading the program. If this happens again, please reconnect the robot with the computer and try again");
                break;
            }
        }
        if ( result.getFirst() == 0 ) {
            LOG.info("Program successfully uploaded");
        }
        //clearAndCloseAll();
        return result;
    }

    private boolean receiveAnswer(String id) {
        Pattern responsePattern = Pattern.compile(String.format("\\{.*%s.*}$\\x0D", id));
        short bufSize = 128;
        byte[] buf = new byte[bufSize];
        String answer;
        Matcher responseMatcher;
        long time = System.currentTimeMillis();
        while ( (System.currentTimeMillis()) - time < 10000 ) {
            serialPort.readBytes(buf, bufSize);
            answer = new String(buf, StandardCharsets.UTF_8);
            LOG.info(answer);
            responseMatcher = responsePattern.matcher(answer);
            if ( responseMatcher.find() ) {
                LOG.info("response: " + responseMatcher.group());
                JSONObject jsonAnswer = new JSONObject(responseMatcher.group());
                if ( jsonAnswer.getString("i") != id ) {
                    continue;
                }
                try {
                    String transferid = jsonAnswer.getString("transferid");

                } catch ( JSONException e ) {
                    return true;
                }
            }
        }
        LOG.error("No response received");
        return false;
    }

//    private int findEndValue(byte[] receiveBuffer) {
//        for ( int pos = 0; pos < receiveBuffer.length; pos++ ) {
//            if ( Byte.compare(receiveBuffer[pos], end) == 0 ) {
//                return pos;
//            }
//        }
//        return -1;
//    }

//    private byte[] encodeFileToBase64Binary(File file) {
//        try {
//            byte[] encodedFile = Base64.getEncoder().encode(FileUtils.readFileToByteArray(file));
//            return encodedFile;
//        } catch ( IOException e ) {
//            LOG.error("Error while encoding file");
//        }
//    }

    private void initSerialPort(String portName) {
        portName = (SystemUtils.IS_OS_WINDOWS ? "" : "/dev/") + portName; // to hide the parameter, which should not be used
        this.serialPort = SerialPort.getCommPort(portName);
        this.serialPort.setBaudRate(115200);
        this.serialPort.openPort(0);
        LOG.info("Serial Communication is initialized: {} {} {}",
            this.serialPort.getSystemPortName(),
            this.serialPort.getDescriptivePortName(),
            this.serialPort.getPortDescription());
    }

    private byte[] receiveResponse() {

        return null;
    }

}
