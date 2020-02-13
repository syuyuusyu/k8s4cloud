FROM openjdk:8-alpine
WORKDIR /
COPY ./target/k8s4cloud-0.0.1-SNAPSHOT.jar k8s4cloud.jar
EXPOSE 7002
CMD [ "java", "-jar","k8s4cloudcd tar   .jar" ]