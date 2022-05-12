package de.fhg.iais.roberta.connection.wired.spikeHub;

import de.fhg.iais.roberta.connection.IConnector;
import de.fhg.iais.roberta.connection.IRobot;
import de.fhg.iais.roberta.connection.wired.AbstractWiredRobot;
import de.fhg.iais.roberta.connection.wired.WiredRobotType;

public class SpikeHub extends AbstractWiredRobot {
    /**
     * Constructor for wired robots.
     *
     * @param type the robot type
     * @param port the robot port
     */
    public SpikeHub(WiredRobotType type, String port) {
        super(type, port);
    }

    @Override
    public IConnector<? extends IRobot> createConnector() {
        return new SpikeHubConnector(this);
    }
}
