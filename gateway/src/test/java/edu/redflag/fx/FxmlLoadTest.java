package edu.redflag.fx;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class FxmlLoadTest {

    @Test
    public void testLoadAllFxmls() throws Exception {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException ignored) {}

        String[] fxmls = {
            "/fxml/Overview.fxml",
            "/fxml/Devices.fxml",
            "/fxml/Reports.fxml",
            "/fxml/Users.fxml",
            "/fxml/Settings.fxml",
            "/fxml/login.fxml",
            "/fxml/device_tab.fxml"
        };

        for (String fxml : fxmls) {
            URL url = getClass().getResource(fxml);
            assertNotNull(url, "Resource not found: " + fxml);
            try {
                Object loaded = FXMLLoader.load(url);
                assertNotNull(loaded, "Failed to load " + fxml);
                System.out.println("LOADED OK: " + fxml);
            } catch (Throwable t) {
                System.err.println("FAILED TO LOAD: " + fxml);
                t.printStackTrace();
                throw new RuntimeException("Error loading " + fxml, t);
            }
        }
    }
}
