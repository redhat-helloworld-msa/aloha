# aloha
Hello microservice using Vert.X

Build and Deploy aloha
-------------------------

1. Open a command prompt and navigate to the root directory of this microservice.
2. Type this command to build and execute the service:

        mvn clean compile exec:java


Access the application
----------------------

The application will be running at the following URL: <http://localhost:8080/api/aloha>

Create a Docker image with Maven
--------------------------------

1. Be sure that a Docker daemon is accessible via the `DOCKER_HOST` variable.
1. Type this command to create a Docker image `redhatmsa/aloha`:

        mvn package docker:build
