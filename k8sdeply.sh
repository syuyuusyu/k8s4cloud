v="latest"
ip="syuyuusyu"
#ip="192.168.50.28:5000"
name="k8s-svc"
mvn package -Dmaven.test.skip=true &&
docker build -t $name:$v . #&&
docker tag $name:$v $ip/$name:$v &&
docker push $ip/$name:$v #&&
#kubectl --kubeconfig=/Users/syu/.kube/config-company patch deployment k8s4cloud-deploy -n cloud-ns --patch '{"spec": {"template": {"spec": {"containers": [{"name": "k8s4cloud","image": "192.168.50.28:5000/k8s4cloud:'$v'"}]}}}}'
