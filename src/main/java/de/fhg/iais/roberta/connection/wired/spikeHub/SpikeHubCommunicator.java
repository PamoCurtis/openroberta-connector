package de.fhg.iais.roberta.connection.wired.spikeHub;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.SystemUtils;
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
        Pair<Integer, String> result;
        try {
            initSerialPort(portName);
            extractAndEncodeFileInformation(absolutePath);
            createMainJsonPayloads();

            result = sendPayloads();

        } catch ( Exception e ) {
            return new Pair<>(1, "Something went wrong while uploading the file.");
        }

        return result;
    }

    JSONObject getDeviceInfo() {
        JSONObject deviceInfo = new JSONObject();

        deviceInfo.put("firmwarename", this.robot.getType().toString());
        deviceInfo.put("robot", this.robot.getType().toString());
        return deviceInfo;
    }

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

    private void extractAndEncodeFileInformation(String filePath) throws IOException {
        File file = new File(filePath);
        Path path = Paths.get(file.getAbsolutePath());
        fileContentEncoded = Base64.getEncoder().encodeToString(FileUtils.readFileToByteArray(file));
    }

    private void createMainJsonPayloads() {
        payloads = new ArrayList<>();

        createProgramTerminatePayload();
        createStartWriteProgramPayload();
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

    private void createWritePackagePayload(String transferid) {
        JSONObject payload;
        JSONObject param;
        List<JSONObject> paramList = new ArrayList<>();
        int maxDataSize = 512;
        int end;
        int rest = 0;
        for ( int i = 0; i < fileContentEncoded.length(); i += end ) {
            param = new JSONObject();

            end = Math.min(maxDataSize, fileContentEncoded.length() - rest);
            rest += end;
            param.put("data", fileContentEncoded.substring(i, i + end));
            param.put("transferid", transferid);

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

    private void createExecuteProgramPayload() {
        JSONObject payload = new JSONObject();

        payload.put("m", "program_execute");
        payload.put("p", new JSONObject());
        payload.put("i", RandomStringUtils.randomAlphanumeric(4));
    }

    private Pair<Integer, String> sendPayloads() throws IOException, InterruptedException {
        Pair<Integer, String> result = new Pair<>(0, "Program successfully uploaded");
        int bytesWritten;
        if ( !this.serialPort.isOpen() ) {
            this.serialPort.openPort();
        }

        for ( JSONObject payload : new ArrayList<>(payloads) ) { //Umändern
            String id = payload.getString("i");
            String payloadAsString = payload + "\r";
            byte[] payloadAsBytes = payloadAsString.getBytes(StandardCharsets.UTF_8);

            find0x0D();
            bytesWritten = this.serialPort.writeBytes(payloadAsBytes, payloadAsBytes.length);

            if ( bytesWritten != payloadAsBytes.length || !receiveResponse(id) ) {
                result = new Pair<>(1, "Something went wrong while uploading the program. If this happens again, please reconnect the robot with the computer and try again");
                break;
            }
        }
        return result;
    }

    private boolean receiveResponse(String id) throws InterruptedException {
        Pattern findResponsePattern = Pattern.compile("\\{.*" + id + ".*}[^\r]");
        Matcher responseMatcher;
        short bufSize = 512;
        byte[] buffer = new byte[bufSize];
        String answer;
        long time = System.currentTimeMillis();

        while ( (System.currentTimeMillis()) - time < 10000 ) {
            this.serialPort.readBytes(buffer, bufSize);

            answer = new String(buffer, StandardCharsets.UTF_8);
            responseMatcher = findResponsePattern.matcher(answer);

            if ( responseMatcher.find() ) {

                JSONObject jsonAnswer = new JSONObject(responseMatcher.group());

                if ( jsonAnswer.getString("i").equals(id) ) {
                    try {
                        createWritePackagePayload(jsonAnswer.getJSONObject("r").getString("transferid"));
                    } catch ( Exception ignored ) {

                    }
                    return true;
                }
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        LOG.error("No response received");
        return false;
    }

    private void find0x0D() {
        short bufSize = 512;
        byte[] buffer = new byte[bufSize];
        String answer;
        long time = System.currentTimeMillis();
        while ( (System.currentTimeMillis()) - time < 10000 ) {
            this.serialPort.readBytes(buffer, bufSize);
            answer = new String(buffer, StandardCharsets.UTF_8);
            if ( answer.contains("\r") ) {
                LOG.info("Found 0x0D");
                break;
            }
        }
    }
}