# Source: https://how-to.vertx.io/executable-jar-docker-howto/

# 1st Docker build stage: build the project with Maven
FROM maven:3.9.9-eclipse-temurin-11 AS builder
WORKDIR /project
COPY . /project/
RUN mvn package -DskipTests -B

# 2nd Docker build stage: copy builder output and configure entry point
FROM eclipse-temurin:21
ENV APP_DIR=/application
ENV APP_FILE=container-uber-jar.jar

EXPOSE 8080

WORKDIR $APP_DIR
COPY --from=builder /project/target/*-fat.jar $APP_DIR/$APP_FILE

ENTRYPOINT ["sh", "-c"]
CMD ["exec java -jar $APP_FILE"]

