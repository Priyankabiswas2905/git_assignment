API Gateway for Brown Dog Services
==================================

*Work In Progress*

1. Make sure you have Java 1.8 and sbt installed
2. Create an *application.conf* file under *src/main/resources*. You can use *reference.conf* as an example.
3. Run `sbt "run-main Proxy -log.level=DEBUG"`

Sample endpoints
----------------
- http://localhost:8080/ok
- http://localhost:8080/dap/alive
- http://localhost:8080/dap/* (with authorization header *"open sesame"*)
- http://localhost:8080/dts/api/* (with authorization header *"open sesame"*)
- For example http://localhost:8080/dts/api/status
- http://localhost:8080/user/123
- http://localhost:8080/echo/echothismessage
- http://localhost:8080/google (with authorization header *"open sesame"*)

Admin console endpoints
-----------------------
- http://localhost:9990/admin
- http://localhost:9990/admin/metrics.json?pretty=true


