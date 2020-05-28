v="v1.03"
ip="10.10.25.1:5001"
mvn package -Dmaven.test.skip=true &&
docker build -t k8s4cloud:$v . #&&
docker tag k8s4cloud:$v $ip/k8s4cloud:$v &&
docker push $ip/k8s4cloud:$v #&&
#kubectl --kubeconfig=/Users/syu/.kube/config-company patch deployment k8s4cloud-deploy -n cloud-ns --patch '{"spec": {"template": {"spec": {"containers": [{"name": "k8s4cloud","image": "192.168.50.28:5000/k8s4cloud:'$v'"}]}}}}'
