# Datumprikker API

This application acts as a datumprikker in which users can:
* Create a datumprikker, including options for dates and times
* Get an overview of available timeslots for a datumprikker
* Book a specific timeslot for a datumprikker
* Get an overview of the booked timeslots for a datumprikker

## Installation

## Installation
To locally run the application, you need to have Docker installed. You can download Docker [here](https://docs.docker.com/get-docker/). After installing Docker, you can clone the repository and run the following command in the root directory of the project:
```bash
cp .env.example .env
```
This will create a `.env` file in the root directory of the project. You can change the values in this file to your liking. After setting up the `.env` file, you can run the following command in the root directory of the project:
```bash
docker compose up -d --build
```
This will build and start all development Docker containers. The following containers will be started:
| Container name   | Port | Description                                              |
|------------------|------|----------------------------------------------------------|
| datumprikker-api | 8080 | The API for the Datumprikker application                 |
| datumprikker-db  | 5432 | The PostgreSQL database for the Datumprikker application |
| redis-cache      | 6379 | The Redis cache for the Datumprikker application         |
| adminer-db       | 8081 | The Adminer interface for the PostgreSQL database        |

After running the command, the application will be available at `http://localhost:8080`. The Adminer interface for the PostgreSQL database will be available at `http://localhost:8081`. You can login to the Adminer interface with the database credentials found in the `.env` file.

## Usage
A detailed description of the API endpoints can be found in the OpenAPI specification. To make interacting with the API easier, you can use the Swagger UI. The Swagger UI is available at `http://localhost:8080/docs/`. From here, it is also easy to launch example requests to the API.

## Main technical features
The application has the following main technical features:
* The application is built using Vert.x with Kotlin.
* The application uses a PostgreSQL database to store the data.
* The application uses Redis in some places to cache data.
* The application has an openAPI specification that the API adheres to.
* The application has a Swagger UI to easily interact with the API.
* The application has a fully containerized environment using Docker Compose.

## Future improvements
There is a lot I did not manage to get done in the time I had left, but here are some things I would have liked to work on:
* Add tests to the application.
* Add more robust error handling to the application.
* Implement the caching for all endpoints.
* Implement validation to see if timeslots for a datumprikker don't overlap.
* Implement more endpoints, to view / alter the booked timeslot.
* Add authentication through JWT tokens.
* Add a frontend to the application.
* Add a small CI/CD pipeline to format / test / build the application.