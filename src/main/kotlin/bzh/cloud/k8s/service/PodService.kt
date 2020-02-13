package bzh.cloud.k8s.service



import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Pod
import org.springframework.stereotype.Service

@Service
class PodService(
        val  kubeApi : CoreV1Api
) {
    fun pods(nameSpaces:List<String>,labelSelector:String=""):List<Map<String,Any?>>{

        return allPods(nameSpaces,labelSelector).map {
            val map = HashMap<String,Any?>()
            map["name"] = it.metadata?.name
            map["ns"] = it.metadata?.namespace
            map["labels"] = it.metadata?.labels
            map["status"] = it.status?.phase
            it.status?.containerStatuses?.forEach { state->
                state.state?.running
            }
            map["uid"] = it.metadata?.uid
            map["containers"] = it.spec?.containers?.map { con->
                val c= HashMap<String,Any?>()
                c["name"] = con.name
                c["image"]= con.image
                c
            }
            map
        }
    }

    fun allPods( nameSpaces:List<String>,labelSelector:String=""):List<V1Pod> {
        var reuslt= ArrayList<V1Pod>()
        nameSpaces.parallelStream().forEach {
            var a= kubeApi.listNamespacedPod(it,"true",null,null,null,
                    null,null,null,0,false) .items
            reuslt.addAll(a)
        }
        return reuslt
    }

}