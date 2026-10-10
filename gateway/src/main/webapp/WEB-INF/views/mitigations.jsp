<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<!DOCTYPE html>
<html>
<head>
    <title>Redflag SOC - Mitigations Report</title>
    <style>
        body { font-family: 'Segoe UI', sans-serif; background-color: #0b0f14; color: #e6edf3; margin: 0; padding: 20px; }
        h1, h2 { color: #f0f6fc; border-bottom: 1px solid #30363d; padding-bottom: 10px; }
        table { width: 100%; border-collapse: collapse; margin-bottom: 30px; }
        th, td { padding: 12px; text-align: left; border-bottom: 1px solid #21262d; }
        th { background-color: #161b22; color: #8b949e; text-transform: uppercase; font-size: 12px; }
        tr:hover { background-color: #1c2128; }
        .badge { padding: 4px 8px; border-radius: 12px; font-size: 11px; font-weight: bold; }
        .badge-danger { background-color: #da3633; color: white; }
    </style>
</head>
<body>
    <h1>Mitigation Events Report</h1>
    
    <h2>Recent Attack Mitigations</h2>
    <table>
        <thead>
            <tr>
                <th>ID</th>
                <th>Node ID</th>
                <th>Started At</th>
                <th>Vector</th>
                <th>Peak Score</th>
                <th>Packets Blocked</th>
            </tr>
        </thead>
        <tbody>
            <c:forEach var="evt" items="${events}">
                <tr>
                    <td>${evt.id}</td>
                    <td><strong>${evt.node}</strong></td>
                    <td>${evt.start}</td>
                    <td><span class="badge badge-danger">${evt.vector}</span></td>
                    <td>${evt.peak}</td>
                    <td>${evt.blocked}</td>
                </tr>
            </c:forEach>
            <c:if test="${empty events}">
                <tr><td colspan="6">No mitigation events recorded.</td></tr>
            </c:if>
        </tbody>
    </table>

    <h2>Active Edge Nodes</h2>
    <table>
        <thead>
            <tr>
                <th>Node ID</th>
                <th>Last Seen</th>
                <th>Total Attack Events</th>
            </tr>
        </thead>
        <tbody>
            <c:forEach var="node" items="${nodes}">
                <tr>
                    <td>${node.node}</td>
                    <td>${node.lastSeen}</td>
                    <td>${node.attacks}</td>
                </tr>
            </c:forEach>
            <c:if test="${empty nodes}">
                <tr><td colspan="3">No active nodes registered.</td></tr>
            </c:if>
        </tbody>
    </table>
</body>
</html>
