package edu.redflag;
import edu.redflag.web.EmbeddedWebServer;
import java.util.concurrent.ArrayBlockingQueue;

public class TestWeb {
    public static void main(String[] args) throws Exception {
        EmbeddedWebServer web = new EmbeddedWebServer(8080, new ArrayBlockingQueue<>(10), new edu.redflag.core.DeviceRegistry());
        web.start();
        System.out.println("Started ok!");
        web.close();
    }
}
