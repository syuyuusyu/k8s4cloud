rm -rf ~/tmp/registryApi;
openapi-generator generate -i ~/project/bzworkspace/k8s4cloud/src/main/resources/static/registry.openapi.yaml  -g java -o /tmp/registryApi;
rm -rf ~/project/bzworkspace/k8s4cloud/src/main/kotlin/org;
cp -r /tmp/registryApi/src/main/java/org ~/project/bzworkspace/k8s4cloud/src/main/kotlin;