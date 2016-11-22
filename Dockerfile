FROM fabric8/java-jboss-openjdk8-jdk:1.2.1

ENV JAVA_APP_JAR aloha-fat.jar
ENV AB_ENABLED jolokia
ENV AB_JOLOKIA_AUTH_OPENSHIFT true
ENV JAVA_OPTIONS -Xmx256m 
ENV ZIPKIN_SERVER_URL http://hawkular-apm
ENV SELF_ROUTE http://aloha-helloworld-msa.rhel-cdk.10.1.2.2.xip.io

EXPOSE 8080

RUN chmod -R 777 /deployments/
ADD target/aloha-fat.jar /deployments/
