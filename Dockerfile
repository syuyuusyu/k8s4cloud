FROM openjdk:8-alpine
WORKDIR /
COPY ./target/k8s4cloud-0.0.1-SNAPSHOT.jar k8s4cloud.jar
EXPOSE 8002
CMD [ "java", "-jar","k8s4cloud.jar" ]
#CMD ["sleep","9999"]

#CMD ["/bin/bash", "-c", "sedockert -e && java -Xmx100m -jar k8s4cloudcd.jar"]