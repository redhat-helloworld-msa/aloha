FROM fabric8/java-jboss-openjdk8-jdk:1.0.10

ENV JAVA_APP_JAR aloha-fat.jar
ENV AB_JOLOKIA_HTTPS true

EXPOSE 8080

ADD target/aloha-fat.jar /app/

