FROM docker.io/library/eclipse-temurin:21-jre
COPY target/orders-form.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
