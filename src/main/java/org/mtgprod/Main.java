package org.mtgprod;

import com.fazecast.jSerialComm.SerialPort;
import org.mtgprod.clavier.In;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

public class Main {
    public static void main(String[] args) {

        System.out.println("Liste des ports disponibles :");
        SerialPort[] ports = SerialPort.getCommPorts();
        for (SerialPort port : ports) {
            System.out.println(port.getSystemPortName());
        }

        System.out.println("Entrez le port série:");
        var userPort = In.readString();

        SerialPort port = SerialPort.getCommPort(userPort);
        port.setBaudRate(57600);
        port.setParity(SerialPort.NO_PARITY);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        port.setNumDataBits(8);
        port.setNumStopBits(1);

        if (port.openPort()) {
            System.out.println("Port ouvert !");
            port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0);
            // Envoyer "radio tx <payload>" pour envoyer des données sur la bande radio
            // Mais avant choisir sa bande radio avec "radio set freq 868000000"
            var mac_get = "mac get appeui";
            var sys_get = "sys get hweui";
            port.writeBytes(mac_get.getBytes(), mac_get.length());
            port.writeBytes(sys_get.getBytes(), sys_get.length());

            try {
                while (true)
                {
                    while (port.bytesAvailable() == 0)
                        Thread.sleep(20);

                    byte[] readBuffer = new byte[port.bytesAvailable()];
                    int numRead = port.readBytes(readBuffer, readBuffer.length);
                    System.out.println("Read " + numRead + " bytes.");
                }
            } catch (Exception e) { e.printStackTrace(); }

            port.closePort();
        } else {
            System.out.println("Impossible d'ouvrir le port.");
        }

        final float[] SENSOR_DATA = {
                230.2f,    // u
                16.35f,    // i
                49.98f,    // f
                0.92f,     // k
                3492.6684f, // p
                1475.09f,   // q
                3763.77f,   // s
                44.8f,      // lat
                0.0f,       // long
                372.81f,    // w
                0.1766f     // prix
        };

        ByteBuffer lora_buffer = ByteBuffer.allocate(4 * 11);
        lora_buffer.order(ByteOrder.BIG_ENDIAN);

        for (float data : SENSOR_DATA) {
            lora_buffer.putFloat(data);
        }

        var lora_buffer_array = lora_buffer.array();

        var string_payload = toHexString(lora_buffer_array);
        var base64_payload = Base64.getEncoder().encodeToString(lora_buffer_array);

        System.out.println(base64_payload);
        System.out.println(string_payload);
    }

    public static String toHexString(byte[] bytes) {
        char[] hexArray = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for ( int j = 0; j < bytes.length; j++ ) {
            v = bytes[j] & 0xFF;
            hexChars[j*2] = hexArray[v/16];
            hexChars[j*2 + 1] = hexArray[v%16];
        }
        return new String(hexChars);
    }

}