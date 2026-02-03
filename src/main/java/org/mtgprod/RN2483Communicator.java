package org.mtgprod;

import com.fazecast.jSerialComm.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.LocalDateTime;
import java.util.function.Consumer;

/**
 * Classe de communication pour le module LoRa RN2483A.
 * Utilise un thread dédié pour l'écoute des réponses.
 */
public class RN2483Communicator {

    // Configuration UART pour le RN2483A
    private static final int DEFAULT_BAUD_RATE = 9600; // Baud rate par défaut
    private static final int DATA_BITS = 8;
    private static final int STOP_BITS = SerialPort.ONE_STOP_BIT;
    private static final int PARITY = SerialPort.NO_PARITY;
    private static final int FLOW_CONTROL = SerialPort.FLOW_CONTROL_DISABLED;

    // Objets de communication
    private SerialPort serialPort;
    private final BlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean listening = new AtomicBoolean(false);
    private Thread listenerThread;
    private Consumer<String> onMessageCallback;
    private Consumer<String> onDebugCallback;

    // Timeout pour les réponses
    private long responseTimeoutMs = 5000;

    /**
     * Configuration de la connexion
     */
    public static class Config {
        private String portName;
        private int baudRate = DEFAULT_BAUD_RATE;

        public Config(String portName) {
            this.portName = portName;
        }

        public Config baudRate(int baudRate) {
            this.baudRate = baudRate;
            return this;
        }

        public String getPortName() { return portName; }
        public int getBaudRate() { return baudRate; }
    }

    /**
     * Initialise et ouvre la connexion série
     */
    public boolean connect(Config config) {
        close(); // Fermeture propre si déjà connecté

        debug("Recherche du port: " + config.getPortName());
        serialPort = SerialPort.getCommPort(config.getPortName());

        if (serialPort == null) {
            error("Port '" + config.getPortName() + "' introuvable");
            listAvailablePorts();
            return false;
        }

        // Configuration du port série
        serialPort.setComPortParameters(
                config.getBaudRate(),
                DATA_BITS,
                STOP_BITS,
                PARITY
        );
        serialPort.setFlowControl(FLOW_CONTROL);

        // Configuration des timeouts (lecture non-bloquante)
        serialPort.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                100,  // Timeout lecture (ms)
                0     // Timeout écriture (ms)
        );

        // Ouverture du port
        if (!serialPort.openPort()) {
            error("Impossible d'ouvrir le port. Vérifiez les permissions.");
            debug("Essayez: sudo usermod -a -G dialout $USER");
            return false;
        }

        debug("Port ouvert: " + serialPort.getSystemPortName() +
                " à " + config.getBaudRate() + " bauds");

        // Vidage du buffer d'entrée
        try {
            Thread.sleep(100);
            byte[] buffer = new byte[serialPort.bytesAvailable()];
            serialPort.readBytes(buffer, buffer.length);
        } catch (Exception e) {}

        // Démarrage du thread d'écoute
        startListenerThread();

        return true;
    }

    /**
     * Démarre le thread d'écoute des réponses
     */
    private void startListenerThread() {
        listening.set(true);
        listenerThread = new Thread(() -> {
            debug("Thread d'écoute démarré");

            byte[] buffer = new byte[1024];
            StringBuilder currentMessage = new StringBuilder();

            while (listening.get() && serialPort != null && serialPort.isOpen()) {
                try {
                    // Lecture des données disponibles
                    int bytesAvailable = serialPort.bytesAvailable();
                    if (bytesAvailable > 0) {
                        int bytesRead = serialPort.readBytes(buffer, Math.min(buffer.length, bytesAvailable));

                        if (bytesRead > 0) {
                            String data = new String(buffer, 0, bytesRead);
                            currentMessage.append(data);

                            // Traitement des messages complets (séparés par \r\n)
                            String[] lines = currentMessage.toString().split("\\r?\\n", -1);

                            // Garder la dernière ligne incomplète dans le buffer
                            for (int i = 0; i < lines.length - 1; i++) {
                                String line = lines[i].trim();
                                if (!line.isEmpty()) {
                                    processIncomingLine(line);
                                }
                            }

                            currentMessage = new StringBuilder(lines[lines.length - 1]);
                        }
                    }

                    // Pause courte pour éviter la surcharge CPU
                    Thread.sleep(10);

                } catch (Exception e) {
                    if (listening.get()) {
                        error("Erreur dans le thread d'écoute: " + e.getMessage());
                    }
                    break;
                }
            }

            debug("Thread d'écoute arrêté");
        });

        listenerThread.setDaemon(true);
        listenerThread.setName("RN2483-Listener");
        listenerThread.start();
    }

    /**
     * Traite une ligne reçue du module
     */
    private void processIncomingLine(String line) {
        // Affiche toutes les réponses pour le débogage
        debug("<< " + line);

        // Ajoute à la file d'attente pour les réponses synchrones
        responseQueue.offer(line);

        // Appelle le callback pour les messages asynchrones
        if (onMessageCallback != null) {
            // Ne pas appeler le callback pour les réponses "ok" simples
            if (!line.equals("ok")) {
                onMessageCallback.accept(line);
            }
        }
    }

    /**
     * Envoie une commande au module et attend la réponse
     * @param command Commande à envoyer (sans \r\n)
     * @param expectResponse true pour attendre une réponse
     * @return La réponse ou null en cas de timeout/erreur
     */
    public String sendCommand(String command, boolean expectResponse) {
        if (!isConnected()) {
            error("Non connecté au module");
            return null;
        }

        // Vidage de la file d'attente avant d'envoyer
        responseQueue.clear();

        // Envoi de la commande
        String fullCommand = command + "\r\n";
        byte[] data = fullCommand.getBytes();
        debug(">> " + command);

        int written = serialPort.writeBytes(data, data.length);
        if (written != data.length) {
            error("Échec de l'envoi de la commande");
            return null;
        }

        if (!expectResponse) {
            return "commande envoyée";
        }

        // Attente de la réponse avec timeout
        try {
            long startTime = System.currentTimeMillis();
            StringBuilder response = new StringBuilder();

            while (System.currentTimeMillis() - startTime < responseTimeoutMs) {
                String line = responseQueue.poll(100, TimeUnit.MILLISECONDS);
                if (line != null) {
                    // Ignore l'écho de la commande (si activé)
                    if (line.equals(command)) {
                        continue;
                    }

                    response.append(line);

                    // Si on reçoit "ok", on considère que la réponse est complète
                    if (line.equals("ok")) {
                        break;
                    }

                    // Pour les commandes radio, certaines réponses sont terminales
                    if (line.startsWith("radio_tx_ok") || line.startsWith("radio_err") ||
                            line.startsWith("invalid") || line.startsWith("mac_err")) {
                        break;
                    }

                    // Pour les réceptions radio
                    if (line.startsWith("radio_rx")) {
                        break;
                    }
                }
            }

            if (response.length() == 0) {
                error("Timeout: pas de réponse à la commande '" + command + "'");
                return null;
            }

            return response.toString();

        } catch (InterruptedException e) {
            error("Interruption pendant l'attente de la réponse");
            return null;
        }
    }

    /**
     * Envoie une commande AT simple et retourne la réponse
     */
    public String sendATCommand(String command) {
        return sendCommand(command, true);
    }

    /**
     * Méthodes spécifiques au RN2483A
     */

    public String getVersion() {
        return sendATCommand("sys get ver");
    }

    public String reset() {
        return sendATCommand("sys reset");
    }

    public String pauseMAC() {
        return sendATCommand("mac pause");
    }

    public String setPower(int power) {
        if (power < -3 || power > 15) {
            error("Puissance invalide. Doit être entre -3 et 15 dBm");
            return null;
        }
        return sendATCommand("radio set pwr " + power);
    }

    public String transmit(String hexData) {
        return sendATCommand("radio tx " + hexData);
    }

    public String startReception(int timeoutMs) {
        return sendATCommand("radio rx " + timeoutMs);
    }

    /**
     * Définit un callback pour les messages asynchrones
     */
    public void setMessageCallback(Consumer<String> callback) {
        this.onMessageCallback = callback;
    }

    /**
     * Définit un callback pour les messages de débogage
     */
    public void setDebugCallback(Consumer<String> callback) {
        this.onDebugCallback = callback;
    }

    /**
     * Vérifie si la connexion est active
     */
    public boolean isConnected() {
        return serialPort != null && serialPort.isOpen() && listening.get();
    }

    /**
     * Ferme la connexion
     */
    public void close() {
        listening.set(false);

        if (listenerThread != null) {
            try {
                listenerThread.interrupt();
                listenerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (serialPort != null && serialPort.isOpen()) {
            serialPort.closePort();
            debug("Port série fermé");
        }

        responseQueue.clear();
    }

    /**
     * Liste les ports série disponibles
     */
    public static void listAvailablePorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        System.out.println("Ports série disponibles:");
        if (ports.length == 0) {
            System.out.println("  Aucun port détecté");
        } else {
            for (SerialPort port : ports) {
                System.out.printf("  %s - %s%n",
                        port.getSystemPortName(),
                        port.getDescriptivePortName());
            }
        }
    }

    /**
     * Méthodes de logging
     */
    private void debug(String message) {
        String logMessage = String.format("[%s] %s",
                LocalDateTime.now().toString(),
                message);

        if (onDebugCallback != null) {
            onDebugCallback.accept(logMessage);
        } else {
            System.out.println(logMessage);
        }
    }

    private void error(String message) {
        String logMessage = String.format("[%s] ERREUR: %s",
                LocalDateTime.now().toString(),
                message);

        if (onDebugCallback != null) {
            onDebugCallback.accept(logMessage);
        } else {
            System.err.println(logMessage);
        }
    }

    /**
     * Méthode main pour tester la classe
     */
    public static void main(String[] args) {
        System.out.println("=== Test du communicateur RN2483A ===\n");

        // 1. Lister les ports
        RN2483Communicator.listAvailablePorts();

        // 2. Créer la configuration
        Config config = new Config("/dev/ttyAMA0")
                .baudRate(57600);

        // 3. Créer et configurer le communicateur
        RN2483Communicator lora = new RN2483Communicator();

        lora.setDebugCallback(msg -> System.out.println("[DEBUG] " + msg));
        lora.setMessageCallback(msg -> {
            System.out.println("[MESSAGE] Réception LoRa: " + msg);
            if (msg.startsWith("radio_rx")) {
                System.out.println("[MESSAGE] Message LoRa reçu: " + msg);
            }
        });

        // 4. Connexion
        if (!lora.connect(config)) {
            System.err.println("Échec de la connexion");
            return;
        }

        // 5. Test de communication basique
        System.out.println("\n--- Test de communication ---");
        String version = lora.getVersion();

        if (version == null || version.contains("invalid")) {
            System.err.println("Le module ne répond pas. Vérifiez:");
            System.err.println("  1. Le câblage (TX/RX inversés?)");
            System.err.println("  2. L'alimentation 3.3V stable");
            System.err.println("  3. Le port série (" + config.getPortName() + ")");
            System.err.println("  4. Le baud rate (" + config.getBaudRate() + ")");
            lora.close();
            return;
        }

        System.out.println("Module détecté: " + version);

        // 6. Configuration pour mode point-à-point
        System.out.println("\n--- Configuration mode point-à-point ---");
        lora.pauseMAC();
        lora.setPower(14);

        // 7. Exemple d'envoi
        System.out.println("\n--- Test d'envoi ---");
        String txResult = lora.transmit("48656C6C6F"); // "Hello" en hex
        if (txResult != null && txResult.contains("radio_tx_ok")) {
            System.out.println("Message envoyé avec succès");
        }

        // 8. Exemple de réception
        System.out.println("\n--- Démarrage de la réception ---");
        lora.startReception(0); // 0 = réception infinie

        // 9. Attente pour la démo
        System.out.println("\nÉcoute pendant 30 secondes... (Ctrl+C pour arrêter)");
        try {
            Thread.sleep(30000);
        } catch (InterruptedException e) {
            System.out.println("Interrompu");
        }

        // 10. Fermeture
        System.out.println("\n--- Fermeture ---");
        lora.close();
        System.out.println("Test terminé");
    }
}