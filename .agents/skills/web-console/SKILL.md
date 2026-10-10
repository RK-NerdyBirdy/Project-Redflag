---
name: web-console
description: >-
  Use this skill when building or modifying the web console (Servlets, JSP, WebSocket)
  for Project Redflag. Covers the REST telemetry API, mitigation report servlet with
  JSP view, WebSocket live telemetry feed, and browser client integration.
---

# Web Console Skill — Project Redflag

## Technology
- Jakarta Servlet 6.0 (Tomcat 10.1 embedded or Jetty 12)
- Jakarta WebSocket 2.1
- JSTL tags for JSP (`jakarta.tags.core`, `jakarta.tags.fmt`)
- Dark theme matching the JavaFX dashboard

## Endpoints

### 1. REST — `TelemetryIngestServlet` (`/api/telemetry`)

**GET** `/api/telemetry?node=UNO-01&limit=100`
- Returns JSON array of recent telemetry log rows
- Fields: `seq`, `dt`, `std20`, `score`, `attack`, `http`, `ts`
- Limit capped at 1000

**POST** `/api/telemetry`
- Accepts one JSON frame (same schema as serial)
- Offers to ingress queue → 202 Accepted or 503 if saturated
- Enables remote edge nodes to feed the gateway over HTTP

### 2. Report — `MitigationReportServlet` (`/report/mitigations`)

**GET** `/report/mitigations`
- Session tracking (counts report views)
- Queries `mitigation_events` and `nodes` tables
- Forwards to `/WEB-INF/views/mitigations.jsp`
- Shows: Node Health table + Recent Mitigation Events table

### 3. WebSocket — `TelemetryWebSocket` (`/ws/telemetry`)

`@ServerEndpoint("/ws/telemetry")`
- `CopyOnWriteArraySet<Session>` for connected clients
- `Broadcaster` implements `TelemetryObserver`
- Rate-capped to ~50 fps during attacks (`20_000_000L` nanos between pushes)
- JSON payload: `{node, seq, dt, std20, score, attack, http}`
- Synchronized per-session sends to prevent `IllegalStateException`

## JSP Template (`mitigations.jsp`)

Dark theme matching the rest of the application:
```css
body { font-family: system-ui, sans-serif; margin: 2rem; background: #0f1419; color: #e6edf3; }
table { border-collapse: collapse; width: 100%; }
th, td { border: 1px solid #30363d; padding: .4rem .6rem; }
th { background: #161b22; }
.ok { color: #3fb950; }
.bad { color: #f85149; font-weight: 700; }
```

Two tables:
1. **Node Health**: Node ID, Last Seen, Attack Count, Status (CLEAN/HAS INCIDENTS)
2. **Recent Mitigation Events**: ID, Node, Start, End, Vector, Peak Score, Packets Blocked

## Browser WebSocket Client Example
```javascript
const ws = new WebSocket(`ws://${location.host}/ws/telemetry`);
ws.onmessage = e => {
    const f = JSON.parse(e.data);
    document.getElementById('status').textContent =
        f.attack ? 'UNDER ATTACK (HTTP ' + f.http + ')' : 'SAFE';
    document.getElementById('status').className =
        f.attack ? 'bad' : 'ok';
};
```

## Servlet Context Sharing
Pipeline shares objects via `ServletContext` attributes:
- `"ingressQueue"` → `BlockingQueue<RawPacket>` (for the POST endpoint)
