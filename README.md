API Gateway for Brown Dog Services
==================================

*Work In Progress*

1. Make sure you have Java 1.8 and sbt installed
2. Create an *application.conf* file under *src/main/resources*. You can use *reference.conf* as an example.
3. Run `sbt "run-main Proxy -log.level=DEBUG"`

Sample endpoints
----------------
- http://localhost:8080/key/8d4a0237-c754-4361-b361-23d6bee5fdb4/token (with username/password credentials)
- http://localhost:8080/token/8d4a0237-c754-4361-b361-23d6bee5fdb4 (with username/password credentials)
- http://localhost:8080/ok
- http://localhost:8080/dap/alive
- http://localhost:8080/dap/* (with authorization token in header)
- http://localhost:8080/dts/api/* (with authorization token in header)
- For example http://localhost:8080/dts/api/status
- http://localhost:8080/user/123
- http://localhost:8080/echo/echothismessage
- http://localhost:8080/google (with authorization token in header)

Admin console endpoints
-----------------------
- http://localhost:9990/admin
- http://localhost:9990/admin/metrics.json?pretty=true


