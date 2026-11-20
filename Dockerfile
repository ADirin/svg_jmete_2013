FROM eclipse-temurin:21-jre

WORKDIR /app

# Spring Boot fat JAR (main class = WebApp)
COPY target/svg_3012_jmeter.jar app.jar

EXPOSE 8083

# MariaDB running on the Windows host
ENV DB_HOST=host.docker.internal

CMD ["java", "-jar", "app.jar"]

# how to run
# mvn clean package
# docker build -f Dockerfile.web -t amirdirin/svg_3012_web .
# docker run --rm -p 8083:8083 amirdirin/svg_3012_web