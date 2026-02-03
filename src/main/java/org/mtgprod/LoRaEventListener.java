package org.mtgprod;

import com.fazecast.jSerialComm.*;

public class LoRaEventListener {
    private SerialPort serialPort;

    public LoRaEventListener(String portName, int baudRate) {
        serialPort = SerialPort.getCommPort(portName);
        serialPort.setBaudRate(baudRate);
        serialPort.setParity(SerialPort.NO_PARITY);
        serialPort.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        serialPort.setNumDataBits(8);
        serialPort.setNumStopBits(1);
        serialPort.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                100,
                0
        );

        // Ajouter un DataListener pour les messages entrants
        serialPort.addDataListener(new SerialPortDataListener() {
            @Override
            public int getListeningEvents() {
                return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
            }

            @Override
            public void serialEvent(SerialPortEvent event) {
                if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE)
                    return;

                byte[] buffer = new byte[serialPort.bytesAvailable()];
                int bytesRead = serialPort.readBytes(buffer, buffer.length);

                if (bytesRead > 0) {
                    String message = new String(buffer, 0, bytesRead);
                    onMessageReceived(message.trim());
                }
            }
        });
    }

    private void onMessageReceived(String message) {
        System.out.println("Message LoRa reçu : " + message);
        // Traitement spécifique selon ton protocole LoRa
    }

    public boolean connect() {
        return serialPort.openPort();
    }

    public void send(String data) {
        serialPort.writeBytes(data.getBytes(), data.length());
    }
}