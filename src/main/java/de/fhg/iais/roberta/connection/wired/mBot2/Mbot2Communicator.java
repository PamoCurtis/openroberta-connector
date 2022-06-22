package de.fhg.iais.roberta.connection.wired.mBot2;

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


    private final byte[] uploadMode = hexStringToByteArray("F3F603000D00000DF4");
    private byte[] bufSend = new byte[1024];
    private String[] payload;
    private SerialPort serialPort;

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

    public Pair<Integer, String> uploadFile(String portName, String filePath) {
        portName = (SystemUtils.IS_OS_WINDOWS ? "" : "/dev/") + portName; // to hide the parameter, which should not be used
        /**
         * step 1: switch to upload mode
         * step 2: send file header data frame
         * step 3: send body header data frame
         * step 4: repeat step 3 till last frame packet is send
         * step 5: receive ok
         */
        return new Pair<>(0, "");
    }

    private void initSerialPort(String portName) {
        if ( serialPort != null && serialPort.isOpen() ) {
            serialPort.closePort();
        }

        serialPort = SerialPort.getCommPort(portName);
        serialPort.setBaudRate(115200);
    }

    private void sendPayload(byte[] payload) {
        //send payload
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
}
