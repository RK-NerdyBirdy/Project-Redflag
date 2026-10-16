package edu.redflag.web;

import edu.redflag.core.DeviceRegistry;
import edu.redflag.model.RawPacket;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EmbeddedWebServerTest {

    @ClientEndpoint
    public static class TestClient {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean connected = false;

        @OnOpen
        public void onOpen(Session session) {
            connected = true;
            latch.countDown();
        }

        @OnMessage
        public void onMessage(String msg) {
            System.out.println("Client received: " + msg);
        }

        @OnClose
        public void onClose(Session session) {
            System.out.println("Client closed");
        }
    }

    @Test
    public void testWebSocketEndpoint() throws Exception {
        int port = 18080;
        ArrayBlockingQueue<RawPacket> q = new ArrayBlockingQueue<>(100);
        DeviceRegistry reg = new DeviceRegistry();
        try (EmbeddedWebServer server = new EmbeddedWebServer(port, q, reg)) {
            server.start();

            // Try connecting to ws://localhost:18080/ws/telemetry
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            assertNotNull(container, "WebSocketContainer should not be null");

            TestClient client = new TestClient();
            try {
                Session session = container.connectToServer(client, URI.create("ws://localhost:" + port + "/ws/telemetry"));
                boolean ok = client.latch.await(5, TimeUnit.SECONDS);
                assertTrue(ok && client.connected, "Should have connected to /ws/telemetry");
                session.close();
            } catch (Exception e) {
                e.printStackTrace();
                throw e;
            }

            // Test HTTP static serving of index.html
            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest httpReq = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/"))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> httpResp = httpClient.send(httpReq, java.net.http.HttpResponse.BodyHandlers.ofString());
            org.junit.jupiter.api.Assertions.assertEquals(200, httpResp.statusCode(), "GET / should return HTTP 200");
            assertTrue(httpResp.body().contains("Project Redflag"), "index.html should contain Project Redflag");
        }
    }
}
