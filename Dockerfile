FROM openjdk:8-alpine
WORKDIR /
COPY ./target/k8s4cloud-0.0.1-SNAPSHOT.jar k8s4cloud.jar
EXPOSE 8002
#CMD [ "java", "-jar","k8s4cloudcd.jar" ]
CMD ["/bin/bash", "-c", "set -e && java -Xmx100m -jar k8s4cloudcd.jar"]