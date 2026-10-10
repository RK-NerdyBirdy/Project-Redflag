package edu.redflag.web;

import edu.redflag.model.RawPacket;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;

/**
 * Embedded Tomcat web server bootstrapping the REST API, JSP report, and WebSocket endpoints on port 8080.
 */
public final class EmbeddedWebServer implements AutoCloseable {
    private final int port;
    private final BlockingQueue<RawPacket> ingressQueue;
    private final edu.redflag.core.DeviceRegistry deviceRegistry;
    private Tomcat tomcat;

    public EmbeddedWebServer(int port, BlockingQueue<RawPacket> ingressQueue, edu.redflag.core.DeviceRegistry deviceRegistry) {
        this.port = port;
        this.ingressQueue = ingressQueue;
        this.deviceRegistry = deviceRegistry;
    }

    public void start() throws Exception {
        tomcat = new Tomcat();
        tomcat.setPort(port);
        tomcat.getConnector(); // Initializes default HTTP connector

        Path tempBaseDir = Files.createTempDirectory("redflag-tomcat-");
        tomcat.setBaseDir(tempBaseDir.toString());

        // Context path "" (root)
        String webappDirLocation = "gateway/src/main/webapp/";
        File docBase = new File(webappDirLocation);
        if (!docBase.exists()) {
            docBase = new File("src/main/webapp/"); // fallback if run from gateway dir
        }

        Context ctx = tomcat.addWebapp("", docBase.getAbsolutePath());
        ctx.setParentClassLoader(EmbeddedWebServer.class.getClassLoader());
        
        org.apache.catalina.WebResourceRoot resources = new org.apache.catalina.webresources.StandardRoot(ctx);
        File additionWebInfClasses = new File("gateway/target/classes");
        if (!additionWebInfClasses.exists()) {
            additionWebInfClasses = new File("target/classes");
        }
        resources.addPreResources(new org.apache.catalina.webresources.DirResourceSet(
                resources, "/WEB-INF/classes", additionWebInfClasses.getAbsolutePath(), "/"));
        ctx.setResources(resources);

        ctx.getServletContext().setAttribute("ingressQueue", ingressQueue);

        // Add WebSocket SCI support
        ctx.addServletContainerInitializer(new WsSci(), Collections.singleton(TelemetryWebSocket.class));

        // Register Servlets
        Tomcat.addServlet(ctx, "AuthServlet", new AuthServlet());
        ctx.addServletMappingDecoded("/api/auth/login", "AuthServlet");

        ctx.addServletContainerInitializer(new jakarta.servlet.ServletContainerInitializer() {
            @Override
            public void onStartup(java.util.Set<Class<?>> c, jakarta.servlet.ServletContext servletContext) throws jakarta.servlet.ServletException {
                jakarta.servlet.FilterRegistration.Dynamic filter = servletContext.addFilter("JwtAuthFilter", new JwtAuthFilter());
                filter.addMappingForUrlPatterns(null, false, "/api/*");
            }
        }, null);

        Tomcat.addServlet(ctx, "TelemetryIngestServlet", new TelemetryIngestServlet(ingressQueue, deviceRegistry));
        ctx.addServletMappingDecoded("/api/telemetry", "TelemetryIngestServlet");

        Tomcat.addServlet(ctx, "MitigationReportServlet", new MitigationReportServlet());
        ctx.addServletMappingDecoded("/report/mitigations", "MitigationReportServlet");

        tomcat.start();
        System.out.println("[WEB] Embedded Web Console listening at http://localhost:" + port + "/");
        System.out.println("[WEB] Live WebSocket feed active at ws://localhost:" + port + "/ws/telemetry");
        System.out.println("[WEB] Audit Reports at http://localhost:" + port + "/report/mitigations");
    }

    @Override
    public void close() {
        if (tomcat != null) {
            try {
                tomcat.stop();
                tomcat.destroy();
            } catch (Exception ignored) {}
        }
    }
}
