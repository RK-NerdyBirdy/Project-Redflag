<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<html>
<head>
    <title>Project Redflag - Device ${nodeId}</title>
</head>
<body>
    <h2>Telemetry for Node: ${nodeId}</h2>
    <div id="telemetry-feed"></div>

    <script>
        const ws = new WebSocket("ws://" + location.host + "/ws/telemetry/${nodeId}");
        ws.onmessage = function(event) {
            const feed = document.getElementById("telemetry-feed");
            const div = document.createElement("div");
            div.textContent = event.data;
            feed.insertBefore(div, feed.firstChild);
            if(feed.children.length > 50) {
                feed.removeChild(feed.lastChild);
            }
        };
    </script>
</body>
</html>
