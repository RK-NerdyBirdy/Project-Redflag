# Project Redflag (Redflag) 

## Overview
Project Redflag (Codename: Redflag) is a high-performance, real-time IoT Volumetric DDoS Edge Gateway. It is designed to sit at the network edge, ingest high-frequency telemetry from IoT devices via Serial, run Machine Learning inference (XGBoost) to detect Mirai-class botnet floods, and execute immediate hardware block commands while providing a full SOC dashboard for administrators.

## Features
- **Real-Time ML Inference**: XGBoost model running directly in Java with circuit-breaker fallbacks.
- **Hardware Integration**: Connects dynamically to Arduino/IoT devices via COM ports to read telemetry and dispatch `BLOCK`/`SAFE` commands.
- **High-Performance UI**: Custom JavaFX Dashboard built for 60FPS rendering without GC pauses using AnimationTimers and non-blocking overlays.
- **Embedded Web Console**: Tomcat-powered REST API, WebSocket live feed, and JSP-based mitigation reports.
- **Database Persistence**: Automatic Flyway migrations backing a robust SQLite/PostgreSQL schema via HikariCP.
- **User Management**: Admin-only CRUD for SOC operators.
- **Report Export**: CSV generation of all attack mitigation events.

## Getting Started
1. **Compile & Run:**
   ```powershell
   mvn -f gateway/pom.xml clean compile exec:java
   ```
2. **Login:** Use `admin@example.com` / `trinetra2026`.
3. **Devices:** Navigate to the Devices tab, select your IoT device's COM port, and click Connect.
4. **Web Console:** 
   - Local Dashboard: `http://localhost:8080/`
   - Mitigation Reports: `http://localhost:8080/report/mitigations`

## Architecture
- `edu.redflag.core`: Core Pipeline (Threading, Back-pressure, Feature generation)
- `edu.redflag.event`: Observers and Mitigation Controller for hardware feedback
- `edu.redflag.fx`: JavaFX SOC Application & Controllers
- `edu.redflag.infer`: ML Strategies (XGBoost / Heuristics)
- `edu.redflag.persist`: JDBC Db Management & AuditWriter (batch insertions)
- `edu.redflag.web`: Embedded Tomcat Servlets and WebSockets
