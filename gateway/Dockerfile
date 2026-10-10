FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY target/redflag-gateway-1.0-SNAPSHOT.jar redflag-gateway.jar
EXPOSE 8080
CMD ["java", "-jar", "redflag-gateway.jar", "-Drf.port=COM8"]
