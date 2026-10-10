<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<html>
<head>
    <title>Project Redflag - Web Login</title>
</head>
<body>
    <h2>Gateway Login</h2>
    <form action="/api/auth/login" method="post">
        <label>Email: <input type="text" name="email"/></label><br/>
        <label>Password: <input type="password" name="password"/></label><br/>
        <input type="submit" value="Login"/>
    </form>
</body>
</html>
