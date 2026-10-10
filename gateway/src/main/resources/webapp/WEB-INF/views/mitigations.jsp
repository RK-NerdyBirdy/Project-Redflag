<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>Project Redflag (Redflag) — Threat Mitigation Audit</title>
  <style>
    :root {
      --bg: #0b0f14;
      --card-bg: #161b22;
      --border: #30363d;
      --text: #e6edf3;
      --text-muted: #8b949e;
      --green: #238636;
      --red: #da3633;
      --accent: #58a6ff;
    }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      margin: 0;
      padding: 2rem 3rem;
      background: var(--bg);
      color: var(--text);
    }
    header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      border-bottom: 1px solid var(--border);
      padding-bottom: 1.5rem;
      margin-bottom: 2rem;
    }
    h1 {
      margin: 0;
      font-size: 1.75rem;
      color: #fff;
      display: flex;
      align-items: center;
      gap: 0.75rem;
    }
    .badge {
      font-size: 0.75rem;
      padding: 0.25rem 0.6rem;
      border-radius: 12px;
      font-weight: 700;
      text-transform: uppercase;
    }
    .badge-ok { background: var(--green); color: #fff; }
    .badge-bad { background: var(--red); color: #fff; }
    .card {
      background: var(--card-bg);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 1.5rem;
      margin-bottom: 2rem;
    }
    h2 {
      margin-top: 0;
      font-size: 1.2rem;
      color: var(--accent);
      border-bottom: 1px solid var(--border);
      padding-bottom: 0.5rem;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      margin-top: 1rem;
      font-size: 0.9rem;
    }
    th, td {
      border: 1px solid var(--border);
      padding: 0.65rem 0.85rem;
      text-align: left;
    }
    th {
      background: #1c2128;
      color: var(--text-muted);
      font-weight: 600;
    }
    tr:nth-child(even) { background: #12161c; }
    .status-ok { color: #3fb950; font-weight: 600; }
    .status-alert { color: #f85149; font-weight: 700; }
    .stats-row {
      display: flex;
      gap: 1.5rem;
      margin-bottom: 1.5rem;
    }
    .stat-box {
      flex: 1;
      background: var(--card-bg);
      border: 1px solid var(--border);
      border-radius: 6px;
      padding: 1rem 1.25rem;
    }
    .stat-val { font-size: 1.5rem; font-weight: 700; color: #fff; }
    .stat-label { font-size: 0.8rem; color: var(--text-muted); }
  </style>
</head>
<body>

  <header>
    <div>
      <h1>🔴 Project Redflag (Redflag) — Security Console</h1>
      <div style="color: var(--text-muted); font-size: 0.85rem; margin-top: 0.25rem;">
        Edge Volumetric DDoS Mitigation & Audit Report (JDK 21 + Jakarta EE)
      </div>
    </div>
    <div style="font-size: 0.85rem; color: var(--text-muted);">
      Session Report Views: <b style="color: #fff;">${sessionScope.reportViews}</b>
    </div>
  </header>

  <div class="stats-row">
    <div class="stat-box">
      <div class="stat-val">${nodes.size()}</div>
      <div class="stat-label">REGISTERED EDGE NODES</div>
    </div>
    <div class="stat-box">
      <div class="stat-val">${events.size()}</div>
      <div class="stat-label">MITIGATION INCIDENTS LOGGED</div>
    </div>
    <div class="stat-box">
      <div class="stat-val" style="color: #3fb950;">ACTIVE</div>
      <div class="stat-label">EDGE GATEWAY STATUS</div>
    </div>
  </div>

  <div class="card">
    <h2>Connected Node Registry & Health</h2>
    <table>
      <thead>
        <tr>
          <th>Node Identifier</th>
          <th>Last Seen Heartbeat</th>
          <th>Incidents Detected</th>
          <th>Edge Status</th>
        </tr>
      </thead>
      <tbody>
        <c:forEach var="n" items="${nodes}">
          <tr>
            <td><b><c:out value="${n.node}"/></b></td>
            <td><c:out value="${n.lastSeen}"/></td>
            <td><c:out value="${n.attacks}"/></td>
            <td>
              <span class="${n.attacks > 0 ? 'badge badge-bad' : 'badge badge-ok'}">
                ${n.attacks > 0 ? 'COMPROMISED' : 'HEALTHY'}
              </span>
            </td>
          </tr>
        </c:forEach>
        <c:if test="${empty nodes}">
          <tr><td colspan="4" style="text-align:center; color: var(--text-muted);">No nodes registered yet.</td></tr>
        </c:if>
      </tbody>
    </table>
  </div>

  <div class="card">
    <h2>Recent Threat Mitigation Episodes</h2>
    <table>
      <thead>
        <tr>
          <th>Episode ID</th>
          <th>Source Node</th>
          <th>Onset Timestamp</th>
          <th>Resolution Time</th>
          <th>Threat Vector</th>
          <th>Peak Score</th>
          <th>Packets Mitigated (403)</th>
        </tr>
      </thead>
      <tbody>
        <c:forEach var="e" items="${events}">
          <tr>
            <td>#${e.id}</td>
            <td><b><c:out value="${e.node}"/></b></td>
            <td><c:out value="${e.start}"/></td>
            <td>${empty e.end ? '<span class="status-alert">ONGOING</span>' : e.end}</td>
            <td><c:out value="${e.vector}"/></td>
            <td><b style="color: #f85149;"><c:out value="${e.peak}"/></b></td>
            <td><b><c:out value="${e.blocked}"/></b> pkts</td>
          </tr>
        </c:forEach>
        <c:if test="${empty events}">
          <tr><td colspan="7" style="text-align:center; color: var(--text-muted);">No mitigation episodes recorded.</td></tr>
        </c:if>
      </tbody>
    </table>
  </div>

</body>
</html>
