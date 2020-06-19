package bzh.cloud.k8s.service

import bzh.cloud.k8s.expansion.CurlEvent
import bzh.cloud.k8s.expansion.curl
import io.kubernetes.client.openapi.apis.ExtensionsV1beta1Api
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodBuilder
import kotlinx.coroutines.*
import org.joda.time.DateTime
import org.json.JSONObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import reactor.core.publisher.FluxSink
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.HashSet


@Service
@EnableScheduling
class WatchAllService(
        val atomicThread: ExecutorCoroutineDispatcher
) : CoroutineScope by CoroutineScope(Dispatchers.Default) {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(WatchAllService::class.java)
    }

    val heartbeat = """{"type":"HEARTBEAT"}"""
    private val dispatcherSink = Collections.synchronizedSet(HashSet<FluxSink<String>>())
    private val curlEventMap = HashMap<String, CurlEvent>()
    private val cache = HashMap<String, String>(1000)

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
    )

    val watchPool = Executors.newFixedThreadPool(urlMap.size) { r ->
        val t = Thread(r)
        t.isDaemon = true
        t
    }

    val kindList = arrayOf("Node", "Pod", "ConfigMap", "Event", "ResourceQuota", "LimitRange", "PersistentVolumeClaim", "PersistentVolume",
            "ReplicationController", "Secret", "ServiceAccount", "Service", "ControllerRevision", "DaemonSet", "Deployment", "ReplicaSet", "StatefulSet",
            "CronJob", "Job", "HorizontalPodAutoscaler", "NetworkPolicy", "ClusterRoleBinding", "ClusterRole", "RoleBinding", "Role")

    @Scheduled(fixedRate = 1000 * 30)
    fun heartbeat() {
        log.info("heartbeat")
        dispatcherSink.forEach { it.next(heartbeat) }
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

    fun addSink(sink: FluxSink<String>) {
        launch {
            withContext(atomicThread) {
                sink.next(heartbeat)
                cache.values.forEach { sink.next(it) }
                dispatcherSink.add(sink)
            }
        }
    }

    fun addCache(uid: String, json: String, deleteFlag: Boolean, name: String = "", kind: String = "") {
        launch {
            withContext(atomicThread) {
                if (deleteFlag) {
                    cache.remove(uid)
                } else {
                    cache.put(uid, json)
                }
                //log.info("addCache deleteFlag:{},kind:{},name:{} cache-size:{}", deleteFlag,kind,name,cache.size)
            }
        }

    }


    fun watch(url: String): CurlEvent {
        val (client, api) = bzh.cloud.k8s.config.watchClient()
        return curl {
            client { client.httpClient }
            request {
                url("${client.basePath}${url}")
                params {
                    "watch" to "true"
                }
            }
            event {
                threadPool(watchPool)
                onWacth { line ->
                    if (url == "/api/v1/pods") {
                        log.info("{}", line)
                    }
                    val json = JSONObject(line)
                    val type = json.getString("type")
                    val uid = json.getJSONObject("object").getJSONObject("metadata").getString("uid")
                    val name = json.getJSONObject("object").getJSONObject("metadata").getString("name")
                    val kind = json.getJSONObject("object").getString("kind")
                    //log.info("watch {},name:{}",kind,name)
                    if (type == "DELETED") {
                        addCache(uid, line, true, name, kind)
                        dispatcherSink.forEach { it.next(json.toString()) }
                        return@onWacth
                    }
                    if (json.getJSONObject("object").getJSONObject("metadata").has("deletionTimestamp")) {
                        addCache(uid, line, true, name, kind)
                        val timeStr = json.getJSONObject("object").getJSONObject("metadata").getString("deletionTimestamp")
                        val datetime = DateTime(timeStr)
                        json.put("deletionTimestamp", datetime)
                        json.put("beforeNow", datetime.isBeforeNow)
                        json.put("afterNow", datetime.isAfterNow)
                        //log.info("json.toString----- {}",json.toString())
                        dispatcherSink.forEach { it.next(json.toString()) }
                        return@onWacth
                    }
                    addCache(uid, line, false, name, kind)
                    dispatcherSink.forEach { it.next(line) }
                }
            }
        } as CurlEvent
    }

}

