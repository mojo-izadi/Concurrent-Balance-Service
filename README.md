# Concurrent Balance Service

Java 21 application using Spring Boot 4.1.1, Spring Web MVC, and Gradle 9.8.2
(Groovy DSL). Generated with [Spring Initializr](https://start.spring.io/).

## Requirements

Install a Java 21 JDK and set `JAVA_HOME` to its installation directory. The
included Unix Gradle wrapper provides Gradle 9.8.2. The first build needs internet
access to download Gradle and dependencies.

## Build and test

```sh
./gradlew build
```

This compiles the application, runs the Spring context test, and creates the
executable JAR at `build/libs/concurrent-balance-service-0.0.1-SNAPSHOT.jar`.

Use `./gradlew clean build` to rebuild from scratch, or `./gradlew test` to run only
the tests.

## Run

```sh
./gradlew run
```

The embedded server listens on port 8080. Add controllers under
`com.example.concurrentbalanceservice` to define application endpoints.
Gradle's application plugin provides the standard `run` task. If Gradle is
installed on your `PATH`, `gradle test`, `gradle build`, and `gradle run` also work.

Run the packaged application with:

```sh
java -jar build/libs/concurrent-balance-service-0.0.1-SNAPSHOT.jar
```

## Docker dependencies

Install Docker with the Compose plugin, then start PostgreSQL, Redis, and Kafka:

```sh
docker compose up -d --wait --wait-timeout 180
```

This downloads the pinned images on the first run and waits for all three
health checks to pass. The services bind to localhost for local development.
PostgreSQL and Redis use alternate host ports to coexist with local installations.

| Service | Image | Host connection | Within the Compose network |
| --- | --- | --- | --- |
| PostgreSQL | `postgres:18.6-alpine` | `localhost:15432` | `postgres:5432` |
| Redis | `redis:8.2.10-alpine` | `localhost:16379` | `redis:6379` |
| Kafka | `apache/kafka:4.1.2` | `localhost:9092` | `kafka:29092` |

PostgreSQL defaults to database `concurrent_balance_service`, username
`concurrent_balance_service`, and password `concurrent_balance_service_dev`.
Its JDBC URL is `jdbc:postgresql://localhost:15432/concurrent_balance_service`.
Redis has no password, and Kafka runs as a single KRaft broker/controller with
plaintext listeners.
These settings are intended for local development.

To override ports or PostgreSQL credentials, copy `.env.example` to `.env` and
edit the values before starting the services. `.env` is excluded from Git.
PostgreSQL credentials initialize the database only when its volume is empty.

```sh
docker compose ps
docker compose logs -f
docker compose down
```

`down` stops and removes the containers while preserving downloaded images and
data in named volumes. Start them again with the same `up` command. The Java
application continues to run separately with `./gradlew run`.

Image documentation: [PostgreSQL](https://hub.docker.com/_/postgres),
[Redis](https://hub.docker.com/_/redis), and
[Apache Kafka](https://kafka.apache.org/41/getting-started/docker/).

## Project layout

- `build.gradle`: plugins, Java 21 toolchain, dependencies, and test configuration.
- `compose.yaml`: PostgreSQL, Redis, and Kafka services with persistent storage and health checks.
- `.env.example`: optional local service settings.
- `src/main/java/com/example/concurrentbalanceservice/ConcurrentBalanceServiceApplication.java`: entry point.
- `src/main/resources/application.properties`: application configuration.
- `src/test/java/com/example/concurrentbalanceservice/ConcurrentBalanceServiceApplicationTests.java`: context startup test.
