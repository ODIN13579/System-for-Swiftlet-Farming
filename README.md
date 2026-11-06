# Project_Java(Back)
- buld java backend springboot
docker compose run --rm backend mvn clean package -DskipTests
(dung = ctrl +C)
java -jar backend\target\swiftletcare-backend-0.0.1-SNAPSHOT.jar
- ktra connct (cmd khac)
curl http://localhost:8080/actuator/health
- buil mvn
docker build -t swiftletcare-backend:v3 ./backend