package bzh.cloud.k8s.service


import bzh.cloud.k8s.config.ClientUtil
import bzh.cloud.k8s.utils.CurlEvent
import bzh.cloud.k8s.utils.curl
import io.kubernetes.client.openapi.ApiClient
import kotlinx.coroutines.*
import okhttp3.Response
import org.apache.commons.collections4.map.MultiKeyMap

import org.json.JSONObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import reactor.core.publisher.FluxSink
import java.util.concurrent.Executors


@Service
//@EnableScheduling
class WatchNsService(
        val atomicThread: ExecutorCoroutineDispatcher

) : CoroutineScope by CoroutineScope(Dispatchers.Default) {



    companion object {
        private val log: Logger = LoggerFactory.getLogger(WatchNsService::class.java)
    }

    val heartbeat = """{"type":"HEARTBEAT"}"""

    private val dispatcherSink = HashSet< Pair<FluxSink<String>,String>>()
    private val curlEventMap = HashMap<String, CurlEvent>()
    private val cache = MultiKeyMap<String,String>();

    val urlMap = mapOf<String, String>(
            "Node" to "/api/v1/nodes", //no
            "Pod" to "/api/v1/pods", //pod
            //"Endpoints" to "/api/v1/endpoints",
            //"ns" to "/api/v1/namespaces",
            "ConfigMap" to "/api/v1/configmaps", //cm
            "Event" to "/api/v1/events", //event
            "ResourceQuota" to "/api/v1/resourcequotas", // quota
            "LimitRange" to "/api/v1/limitranges", // limits
            "PersistentVolumeClaim" to "/api/v1/persistentvolumeclaims",  //pvc
            "PersistentVolume" to "/api/v1/persistentvolumes", //pv
            "ReplicationController" to "/api/v1/replicationcontrollers", //rc
            "Secret" to "/api/v1/secrets", //secrets
            "ServiceAccount" to "/api/v1/serviceaccounts", //sa
            "Service" to "/api/v1/services", //svc

            "ControllerRevision" to "/apis/apps/v1/controllerrevisions", //controllerrevisions
            "DaemonSet" to "/apis/apps/v1/daemonsets", //ds
            "Deployment" to "/apis/apps/v1/deployments", //deploy
            "ReplicaSet" to "/apis/apps/v1/replicasets", //rs

            "StatefulSet" to "/apis/apps/v1/statefulsets", //sts

            "CronJob" to "/apis/batch/v1beta1/cronjobs", //cj

            "Job" to "/apis/batch/v1/jobs", //job

            "Ingress" to "/apis/extensions/v1beta1/ingresses",

            "HorizontalPodAutoscaler" to "/apis/autoscaling/v1/horizontalpodautoscalers", //pha

            "NetworkPolicy" to "/apis/networking.k8s.io/v1/networkpolicies", //netpol

            "ClusterRoleBinding" to "/apis/rbac.authorization.k8s.io/v1/clusterrolebindings", //clusterrolebindings
            "ClusterRole" to "/apis/rbac.authorization.k8s.io/v1/clusterroles", //clusterroles
            "RoleBinding" to "/apis/rbac.authorization.k8s.io/v1/rolebindings", //rolebindings
            "Role" to "/apis/rbac.authorization.k8s.io/v1/roles" //roles
            //"Metrics" to "/apis/metrics.k8s.io/v1beta1/nodes"
    )

    val watchPool = Executors.newFixedThreadPool(urlMap.size + 2) { r ->
        val t = Thread(r)
        t.isDaemon = true
        t
    }

    //@Scheduled(fixedRate = 1000 * 30)
    fun heartbeat() {
        log.info("heartbeat")
        val watchClient = ClientUtil.watchClient()
        val count =  watchClient.httpClient.connectionPool().connectionCount()
        log.info("watchClient connectionPool {}",count)
        val apiClient = ClientUtil.apiClient()
        val count1 =  apiClient.httpClient.connectionPool().connectionCount()
        log.info("apiClient connectionPool {}",count1)
        val removeSink =  HashSet< Pair<FluxSink<String>,String>>()
        dispatcherSink.forEach {

            if (it.first.isCancelled) {
                removeSink.add(it)
            }
            it.first.next(heartbeat)
        }
        dispatcherSink.removeAll(removeSink)
        log.info("dispatcherSink.size:{}",dispatcherSink.size)
        urlMap.forEach { kind, url ->
            if (!curlEventMap.containsKey(kind)) {
                curlEventMap[kind] = watch(url)
            } else {
                val event = curlEventMap[kind]!!
                if (!event.watchProcessing) {
                    log.info("watch {} nolonger processing,restart", kind)
                    curlEventMap[kind] = watch(url)
                }
            }
        }
    }

    //@Scheduled(fixedRate = 1000 * 60)
    fun metrics(){
        log.info("metrics")
        metricsNodes()
        metricsPod()
    }

    fun addSink(sink: FluxSink<String>,ns:String) {
        log.info("addSink")
        launch {
            withContext(atomicThread) {
                log.info("sink heartbeat")
                sink.next(heartbeat)
                log.info("before sink cache")
                cache.forEach{key,value->
                    if(key.getKey(0) == ns){
                        sink.next(value);
                    }
                }
                log.info("after sink cache")
                dispatcherSink.add(Pair(sink,ns))
                log.info("dispatcherSink added")
                metricsNodes()
                metricsPod()
                log.info("end addSink")
            }
        }
    }

    fun addCache(uid: String,ns :String?, json: String, deleteFlag: Boolean, name: String = "", kind: String = "") {
        launch {
            withContext(atomicThread) {
                if (deleteFlag) {
                    cache.removeAll(ns,uid)

                } else {
                    cache.put(ns,uid,json)
                }
                log.info("addCache deleteFlag:{},kind:{},name:{} cache-size:{}", deleteFlag, kind, name, cache.size)
            }
        }

    }

    fun watch(url: String): CurlEvent {
        var watchClient: ApiClient = ClientUtil.watchClient()
        return curl {
            client { watchClient.httpClient }
            request {
                url("${watchClient.basePath}${url}")
                params {
                    "watch" to "true"
                }
            }
            event {
                threadPool(watchPool)
                onWacth { line ->
                    if (url == "/api/v1/nodes") {
                        //log.info("{}", line)
                    }
                    val json = JSONObject(line)
                    val type = json.getString("type")
                    val objJson = json.getJSONObject("object");
                    val metadataJson = objJson.getJSONObject("metadata")
                    val uid = metadataJson.getString("uid")
                    val name = metadataJson.getString("name")
                    var ns = ""
                    if (metadataJson.has("namespace")) {
                        ns = metadataJson.getString("namespace")
                    }
                    val kind = json.getJSONObject("object").getString("kind")
                    //log.info("watch {},name:{}",kind,name)
                    var deleteFlag = false
                    if (type == "DELETED") {
                        deleteFlag = true
                    }
                    addCache(uid, ns, line, deleteFlag, name, kind)
                    json.put("notCache", true)
                    dispatcherSink.forEach { it.first.next(json.toString()) }
                }
            }
        } as CurlEvent
    }

    fun metricsNodes() {
        launch(watchPool.asCoroutineDispatcher()) {
            val apiClient: ApiClient = ClientUtil.apiClient()
            log.info("metricsNodes {}",apiClient.isDebugging)
            val response = curl {
                client { apiClient.httpClient }
                request {
                    url("${apiClient.basePath}/apis/metrics.k8s.io/v1beta1/nodes")
                }

            } as Response
            val str = response.body()?.string()!!
            //log.info(str)
            dispatcherSink.forEach { it.first.next(str) }
        }

    }

    fun metricsPod() {
        launch(watchPool.asCoroutineDispatcher()) {
            val apiClient: ApiClient = ClientUtil.apiClient()
            val response = curl {
                client { apiClient.httpClient }
                request {
                    url("${apiClient.basePath}/apis/metrics.k8s.io/v1beta1/pods")

                }
            } as Response
            val str = response.body()?.string()!!
            //log.info(str)
            dispatcherSink.forEach { it.first.next(str) }
        }
    }
}

//  /apis/metrics.k8s.io/v1beta1/namespaces/{namespace}/pods
//  /apis/metrics.k8s.io/v1beta1/namespaces/{namespace}/pods/{name}
//  /apis/metrics.k8s.io/v1beta1/nodes
//  /apis/metrics.k8s.io/v1beta1/nodes/{name}
//  /apis/metrics.k8s.io/v1beta1/pods






