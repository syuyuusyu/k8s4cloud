package bzh.cloud.k8s.controller


import bzh.cloud.k8s.config.ClientUtil
import bzh.cloud.k8s.config.CmContext


import bzh.cloud.k8s.expansion.curl
import bzh.cloud.k8s.expansion.metricsNode
import bzh.cloud.k8s.service.ConfigMapService
import bzh.cloud.k8s.service.WatchAllService
import bzh.cloud.k8s.service.WatchNsService
import bzh.cloud.k8s.service.WatchService
import com.fasterxml.jackson.core.type.TypeReference
import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.util.StringUtils
import org.springframework.web.bind.annotation.*
import java.io.FileReader
import com.fasterxml.jackson.databind.ObjectMapper
import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.*
import io.kubernetes.client.openapi.models.*
import kotlinx.coroutines.*
import okhttp3.Response
import org.json.JSONObject
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponse
import reactor.core.publisher.Flux
import java.util.concurrent.Executor

class NsWithQuota {
    var name = ""
    var quotaCpu = ""
    var quotaMemory = ""
    var quotaPods = 0
    var limitCpu = ""
    var limitMemory = ""
    var defaultCpu = ""
    var defaultMemory = ""
    var defaultRequestCpu = ""
    var defaultRequestMemory = ""
    var minCpu = ""
    var minMemory = ""
}


@RestController
@RequestMapping("/kube")
class KubeController(
        val configMapService: ConfigMapService,
        val threadPool: Executor,
        val watchService: WatchService,
        val watchAllService: WatchAllService,
        val watchNsService: WatchNsService
) : CoroutineScope by CoroutineScope(Dispatchers.Default) {
    @Value("\${self.kubeConfigPath}")
    lateinit var kubeConfigPath: String




    companion object {
        private val log: Logger = LoggerFactory.getLogger(KubeController::class.java)
        fun errorMsg(e: ApiException, result: HashMap<String, Any>) {
            result["success"] = false
            if (StringUtils.isEmpty(e.responseBody)) {
                result["msg"] = e.localizedMessage
            } else {
                try {
                    val mapper = ObjectMapper()
                    val actualObj = mapper.readTree(e.responseBody)
                    result["msg"] = actualObj["message"]
                } catch (e1: Exception) {
                    result["msg"] = e.localizedMessage
                }

            }
        }
    }


    private fun createNsifNotExist(ns: String) {
        if (ns == "null") return
        if (!NameSpace().nameExists(ns)) {
            val nsobj = V1NamespaceBuilder().withNewMetadata().withName(ns).endMetadata().build()
            ClientUtil.kubeApi().createNamespace(nsobj, "false", null, null)
        }
    }

    @GetMapping("/api")
    fun api(): String {
        val apiClient = ClientUtil.apiClient()
        val response = curl {
            client { apiClient.httpClient }
            request {
                url("${apiClient.basePath}/openapi/v2")
            }

        } as Response
        return response.body()?.string()!!
    }

    @GetMapping("/api/definitions")
    fun definitions(): String {
        val apiClient = ClientUtil.apiClient()
        val response = curl {
            client { apiClient.httpClient }
            request {
                url("${apiClient.basePath}/openapi/v2")
            }

        } as Response
        val str = response.body()?.string()!!
        val json = JSONObject(str)
        return json.getJSONObject("definitions").toString(0)
    }

    @GetMapping("/watchAll", produces = arrayOf(MediaType.TEXT_EVENT_STREAM_VALUE))
    fun watchAll(): Flux<String> {
        log.info("watchAll controller")
        return Flux.create<String>(watchAllService::addSink).doFinally {
            log.info("doFinally watchAll")
        }
    }

    @GetMapping("/watchNs/{ns}", produces = arrayOf(MediaType.TEXT_EVENT_STREAM_VALUE))
    fun watchNs(@PathVariable ns: String): Flux<String> {
        log.info("watchAll controller")
        return Flux.create<String>{ watchNsService.addSink(it,ns)}.doFinally {
            log.info("doFinally watchAll")
        }
    }

    @GetMapping("/watch/allResourcequota", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun watchlist(response: ServerHttpResponse): Flux<V1ResourceQuota> {
        return Flux.create<V1ResourceQuota> { watchService.addQuotaSink(it) }.doFinally {
            log.info("/watch/allResourcequota complete")

        }
    }

    @GetMapping("/namespace")
    fun nameSpaceList(): List<String> {
        return NameSpace().listname()
    }

    @GetMapping("/allnamespace")
    fun allnamespace(): List<String> = NameSpace().listname() + NameSpace().filterList

    @GetMapping("/checknsname/{ns}")
    fun checknsname(@PathVariable ns: String): Map<String, Any> = NameSpace().nameExistsStatus(ns)


    @DeleteMapping("/namespaceWithQuota/{ns}")
    fun deleteNs(@PathVariable ns: String): Map<String, Any> = delete("", "") { _, _ ->
        NameSpace().delete(ns)
    }

    @PostMapping("/namespaceWithQuota")
    fun createnamespaceWithQuota(@RequestBody body: NsWithQuota): Map<String, Any> {
        val ns = body.name
        if (ns in NameSpace().filterList)
            return mapOf("success" to false, "mag" to "${ns}为系统空间不能操作")

        fun exec() {
            createNsifNotExist(ns)
            val quotaMap = mapOf(
                    "cpu" to Quantity(body.quotaCpu),
                    "memory" to Quantity(body.quotaMemory),
                    "pods" to Quantity(body.quotaPods.toString())
            )
            val limitMap = mapOf(
                    "cpu" to Quantity(body.limitCpu),
                    "memory" to Quantity(body.limitMemory))
            val quota = ResourceQuota().rawList().items.find { it.metadata?.name == "quota-$ns" }
            val limit = LimitRange().rawList().items.find { it.metadata?.name == "limits-$ns" }

            if (quota == null) {
                ResourceQuota().buildQuota(ns, quotaMap)
            } else {
                quota.spec?.hard = quotaMap
                ResourceQuota().update(quota)
            }
            val defaultMap = body.defaultCpu?.let {
                mapOf(
                        "cpu" to Quantity(body.defaultCpu),
                        "memory" to Quantity(body.defaultMemory))
            }
            val defaultRequestMap = body.defaultRequestCpu?.let {
                mapOf(
                        "cpu" to Quantity(body.defaultRequestCpu),
                        "memory" to Quantity(body.defaultRequestMemory))
            }
            val minMap = body.minCpu?.let {
                mapOf(
                        "cpu" to Quantity(body.minCpu),
                        "memory" to Quantity(body.minMemory))
            }
            if (limit == null) {
                LimitRange().buildRange(ns, limitMap, minMap, defaultMap, defaultRequestMap)
            } else {
                limit.spec?.limits?.get(0)?.max = limitMap
                defaultMap?.let { limit.spec?.limits?.get(0)?.default = it }
                defaultRequestMap?.let { limit.spec?.limits?.get(0)?.defaultRequest = it }
                minMap?.let { limit.spec?.limits?.get(0)?.min = it }
                LimitRange().update(limit)
            }
        }
        if (NameSpace().nameExists(ns)) {
            return update(Any()) { exec() }
        } else {
            return create(::exec)
        }
    }

    @GetMapping("/namespaceWithQuota")
    fun namespaceWithQuota(@RequestParam name: String?): List<NsWithQuota> {
        val nsList = NameSpace().listname()
        val quotaMap = mapOf(
                "cpu" to Quantity("4000m"),
                "memory" to Quantity("4Gi"),
                "pods" to Quantity("10")
        )
        val limitMap = mapOf(
                "cpu" to Quantity("800m"),
                "memory" to Quantity("500Mi")
        )
        val quotaList = ResourceQuota().rawList()
        val limitrangeList = LimitRange().rawList()
        return nsList.filter {
            if (StringUtils.isEmpty(name)) {
                true
            } else {
                it.contains(name!!)
            }
        }.map { ns ->
            val quota = quotaList.items.find { it.metadata?.namespace == ns }
            //?: Resourcequota().buildQuota(ns, quotaMap)
            val limitRange = limitrangeList.items.find { it.metadata?.namespace == ns }
            //?: LimitRange().buildRange(ns, limitMap)
            NsWithQuota().apply {
                try {
                    this.name = ns
                    quotaCpu = quota?.spec?.hard?.get("cpu")?.toSuffixedString()!!
                    quotaMemory = quota?.spec?.hard?.get("memory")?.toSuffixedString()!!
                    quotaPods = quota?.spec?.hard?.get("pods")?.toSuffixedString()!!.toInt()
                    limitCpu = limitRange?.spec?.limits?.get(0)?.max?.get("cpu")?.toSuffixedString()!!
                    limitMemory = limitRange?.spec?.limits?.get(0)?.max?.get("memory")?.toSuffixedString()!!

                    defaultCpu = limitRange?.spec?.limits?.get(0)?.default?.get("cpu")?.toSuffixedString()!!
                    defaultMemory = limitRange?.spec?.limits?.get(0)?.default?.get("memory")?.toSuffixedString()!!

                    defaultRequestCpu = limitRange?.spec?.limits?.get(0)?.defaultRequest?.get("cpu")?.toSuffixedString()!!
                    defaultRequestMemory = limitRange?.spec?.limits?.get(0)?.defaultRequest?.get("memory")?.toSuffixedString()!!

                    minCpu = limitRange?.spec?.limits?.get(0)?.min?.get("cpu")?.toSuffixedString()!!
                    minMemory = limitRange?.spec?.limits?.get(0)?.min?.get("memory")?.toSuffixedString()!!
                } catch (e: java.lang.Exception) {
                    log.info("{},{}", quota, limitRange)
                }
            }
        }
    }


    @GetMapping("/node")
    fun nodes(): List<V1Node> {
        val kubeApi = ClientUtil.kubeApi()
        val result = kubeApi.listNode("", false,
                null, "", null, null, null, 0, false)
        return result.items
    }

    @GetMapping("/metrics/runingstatus/{ns}")
    fun runingstatus(@PathVariable ns: String): Map<String, Any?> {
        val kubeApi = ClientUtil.kubeApi()
        val podlist = kubeApi.listNamespacedPod(ns, null, null, null, null,
                null, null, null, 0, false).items
        val deployList = Deployment().rawList(ns)
        val pvcList = PersistentVolumeClaim().rawList(ns)
        val podstatus = podlist.groupBy { it.status?.phase == "Running" }
        val readyReplicas = if (deployList.size == 0) 0 else deployList.map {
            it.status?.readyReplicas ?: 0
        }.reduce { acc, i -> acc + i }
        val replicas = if (deployList.size == 0) 0 else deployList.map {
            it.status?.replicas ?: 0
        }.reduce { acc, i -> acc + i }
        val pvsStatus = pvcList.groupBy { it.status?.phase == "Bound" }
        return mapOf(
                "Pod" to mapOf(
                        "all" to podlist.size,
                        "ready" to podstatus[true]?.size
                ),
                "Deployment" to mapOf(
                        "all" to replicas,
                        "ready" to readyReplicas

                ),
                "PersistentVolumeClaim" to mapOf(
                        "all" to pvcList.size,
                        "ready" to pvsStatus[true]?.size
                )
        )
    }

    @GetMapping("/metrics")
    fun metrics(): List<HashMap<String, Any?>>? {
        val kubeApi = ClientUtil.kubeApi()
        val metlist = kubeApi.metricsNode()
        val podList = kubeApi.listPodForAllNamespaces(false, null, null, null,
                null, null, null, 0, false).items
        val nodelist = kubeApi.listNode(null, false, null, null, null,
                0, null, 0, false)
        //val a = nodelist.items?.map { it.status?.capacity?.get("cpu")?.number }
        //log.info("a:{}",a?.size)
        val allcpu = nodelist.items?.map { it.status?.capacity?.get("cpu")?.number }?.reduce { acc, i -> acc?.add(i) }
        val allPod = nodelist.items?.map { it.status?.capacity?.get("pods")?.number }?.reduce { acc, i -> acc?.add(i) }

        val b = nodelist.items?.map { it.status?.capacity?.get("memory")?.number }
        //log.info("b:{}",b?.size)
        val allmemory = nodelist.items?.map { it.status?.capacity?.get("memory")?.number }?.reduce { acc, i -> acc?.add(i) }

        //val c =    metlist.items?.map { it.usage?.get("memory")?.number }
        //log.info("c:{}",c?.size)
        val totalusememory = metlist.items?.map { it.usage?.get("memory")?.number }?.reduce { acc, i -> acc?.add(i) }

        //sval d = metlist.items?.map { it.usage?.get("cpu")?.number }
        //log.info("d:{}",d?.size)
        val totaluseCpu = metlist.items?.map { it.usage?.get("cpu")?.number }?.reduce { acc, i -> acc?.add(i) }

        return metlist.items?.map { metricsitem ->
            val map = HashMap<String, Any?>()
            val usage = metricsitem.usage
            val nodeItem = nodelist.items.find { it.metadata?.name == metricsitem.metadata?.name }
            map["nodeName"] = metricsitem.metadata?.name

            map["usage"] = HashMap<String, Any?>().apply {
                this["cpu"] = usage?.get("cpu")?.number
                this["memory"] = usage?.get("memory")?.number
                this["memoryunit"] = usage?.get("memory")
                this["pods"] = podList.filter { it.spec?.nodeName == metricsitem.metadata?.name }.size
            }
            map["totalusage"] = HashMap<String, Any?>().apply {
                this["pods"] = podList.size
                this["cpu"] = Quantity(totaluseCpu, Quantity.Format.DECIMAL_SI).number
                this["memory"] = Quantity(totalusememory, Quantity.Format.BINARY_SI).number
                this["memoryunit"] = Quantity(totalusememory, Quantity.Format.BINARY_SI)
            }
            map["all"] = HashMap<String, Any?>().apply {
                this["cpu"] = Quantity(allcpu, Quantity.Format.DECIMAL_SI).number
                this["memory"] = Quantity(allmemory, Quantity.Format.BINARY_SI).number
                this["memoryunit"] = Quantity(allmemory, Quantity.Format.BINARY_SI)
                this["pods"] = allPod
            }
            
            map["capacity"] = nodeItem?.status?.capacity
            map["allocatable"] = nodeItem?.status?.allocatable
            map
        }
    }

    @GetMapping("/ResourcequotaAllns")
    fun resourcequotaAllns(): List<Any> = ResourceQuota().listAll()


    @GetMapping("/namespace/{ns}/{kind}")
    fun list(response: ServerHttpResponse, @PathVariable kind: String, @PathVariable ns: String): List<Any> = when (kind) {
        "Ingress" -> Ingress().list(ns)
        "Service" -> Service().list(ns)
        "PersistentVolume" -> PersistentVolume().list()
        "PersistentVolumeClaim" -> PersistentVolumeClaim().list(ns)
        "Deployment" -> Deployment().list(ns)
        "ConfigMap" -> configMapService.list(ns)
        "Resourcequota" -> ResourceQuota().list(ns)
        "Event" -> Event().list(ns)
        else -> {
            response.setStatusCode(HttpStatus.NOT_FOUND)
            ArrayList<Nothing>()
        }
    }

    @GetMapping("/namespace/{ns}/{kind}/{name}")
    fun read(response: ServerHttpResponse, @PathVariable kind: String, @PathVariable ns: String, @PathVariable name: String): Any = when (kind) {
        "Ingress" -> Ingress().read(ns, name)
        "Service" -> Service().read(ns, name)
        "PersistentVolume" -> PersistentVolume().read(name)
        "PersistentVolumeClaim" -> PersistentVolumeClaim().read(ns, name)
        "Deployment" -> Deployment().read(ns, name)
        "ConfigMap" -> configMapService.read(ns, name)
        "Resourcequota" -> ResourceQuota().read(ns, name)
        else -> {
            response.setStatusCode(HttpStatus.NOT_FOUND)
            Any()
        }
    }


    @PutMapping("/PersistentVolume")
    fun updatePersistentVolume(@RequestBody body: V1PersistentVolume): Map<String, Any> = update(body) { PersistentVolume().update(it as V1PersistentVolume) }

    @PutMapping("/Ingress")
    fun updateIngress(@RequestBody body: ExtensionsV1beta1Ingress): Map<String, Any> = update(body) { Ingress().update(it as ExtensionsV1beta1Ingress) }

    @PutMapping("/Deployment")
    fun updateDeployment(@RequestBody body: V1Deployment): Map<String, Any> = update(body) { Deployment().update(it as V1Deployment) }

    @PutMapping("/Service")
    fun updateService(@RequestBody body: V1Service): Map<String, Any>? = update(body) { Service().update(it as V1Service) }

    @PutMapping("/PersistentVolumeClaim")
    fun updatePersistentVolumeClaim(@RequestBody body: V1PersistentVolumeClaim): Map<String, Any> = update(body) { PersistentVolumeClaim().update(it as V1PersistentVolumeClaim) }

    @PutMapping("/ConfigMap")
    fun updateConfigmap(@RequestBody body: V1ConfigMap): Map<String, Any> = update(body) { configMapService.update(it as V1ConfigMap) }

    @PutMapping("/ReplicaSet")
    fun updateV1beta1ReplicaSet(@RequestBody body: V1ReplicaSet): Map<String, Any> = update(body) { ReplicaSet().update(it as V1ReplicaSet) }


    @PutMapping("/DaemonSet")
    fun updateDaemonSet(@RequestBody body: V1DaemonSet): Map<String, Any> = update(body) { DaemonSet().update(it as V1DaemonSet) }

    @PutMapping("/ReplicationController")
    fun updateReplicationController(@RequestBody body: V1ReplicationController): Map<String, Any> = update(body) { ReplicationController().update(it as V1ReplicationController) }


    @PutMapping("/StatefulSet")
    fun updateStatefulSet(@RequestBody body: V1StatefulSet): Map<String, Any> = update(body) { StatefulSet().update(it as V1StatefulSet) }


    @PutMapping("/Job")
    fun updateJob(@RequestBody body: V1Job): Map<String, Any> = update(body) { Job().update(it as V1Job) }

    @PutMapping("/CronJob")
    fun updateCronJob(@RequestBody body: V1beta1CronJob): Map<String, Any> = update(body) { CronJob().update(it as V1beta1CronJob) }

    @PutMapping("/Secret")
    fun updateSecret(@RequestBody body: V1Secret): Map<String, Any> = update(body) { Secret().update(it as V1Secret) }

    @PutMapping("/ServiceAccount")
    fun updateServiceAccount(@RequestBody body: V1ServiceAccount): Map<String, Any> = update(body) { ServiceAccount().update(it as V1ServiceAccount) }

    @PutMapping("/HorizontalPodAutoscaler")
    fun updateHorizontalPodAutoscaler(@RequestBody body: V1HorizontalPodAutoscaler): Map<String, Any> = update(body) { HorizontalPodAutoscaler().update(it as V1HorizontalPodAutoscaler) }

    @PutMapping("/Role")
    fun updateRole(@RequestBody body: V1Role): Map<String, Any> = update(body) { Role().update(it as V1Role) }

    @PutMapping("/ClusterRole")
    fun updateClusterRole(@RequestBody body: V1ClusterRole): Map<String, Any> = update(body) { ClusterRole().update(it as V1ClusterRole) }

    @PutMapping("/RoleBinding")
    fun updateRoleBinding(@RequestBody body: V1RoleBinding): Map<String, Any> = update(body) { RoleBinding().update(it as V1RoleBinding) }

    @PutMapping("/ClusterRoleBinding")
    fun updateClusterRoleBinding(@RequestBody body: V1ClusterRoleBinding): Map<String, Any> = update(body) { ClusterRoleBinding().update(it as V1ClusterRoleBinding) }

    @PutMapping("/ResourceQuota")
    fun updateResourcequota(@RequestBody body: V1ResourceQuota): Map<String, Any> = update(body) { ResourceQuota().update(it as V1ResourceQuota) }

    @PutMapping("/LimitRange")
    fun updateLimitRange(@RequestBody body: V1LimitRange): Map<String, Any> = update(body) { LimitRange().update(it as V1LimitRange) }

    @DeleteMapping("/namespace/{ns}/{kind}/{name}")
    fun delete(@PathVariable kind: String, @PathVariable ns: String, @PathVariable name: String): Map<String, Any> = when (kind) {
        "Ingress" -> delete(ns, name, Ingress()::delete)
        "Service" -> delete(ns, name, Service()::delete)
        "PersistentVolume" -> delete("", "") { _, _ -> PersistentVolume().delete(name) }
        "PersistentVolumeClaim" -> delete(ns, name, PersistentVolumeClaim()::delete)
        "Deployment" -> delete(ns, name, Deployment()::delete)
        "ConfigMap" -> delete(ns, name, configMapService::delete)
        "ReplicaSet" -> delete(ns, name, ReplicaSet()::delete)
        "DaemonSet" -> delete(ns, name, DaemonSet()::delete)
        "ReplicationController" -> delete(ns, name, ReplicationController()::delete)
        "StatefulSet" -> delete(ns, name, StatefulSet()::delete)
        "Job" -> delete(ns, name, Job()::delete)
        "CronJob" -> delete(ns, name, CronJob()::delete)
        "Secret" -> delete(ns, name, Secret()::delete)
        "ServiceAccount" -> delete(ns, name, ServiceAccount()::delete)
        "V1HorizontalPodAutoscaler" -> delete(ns, name, HorizontalPodAutoscaler()::delete)
        "Role" -> delete(ns, name, Role()::delete)
        "RoleBinding" -> delete(ns, name, RoleBinding()::delete)
        "ClusterRole" -> delete(ns, name, ClusterRole()::delete)
        "ClusterRoleBinding" -> delete(ns, name, ClusterRoleBinding()::delete)
        "LimitRange" -> delete(ns, name, LimitRange()::delete)
        "ResourceQuota" -> delete(ns, name, ResourceQuota()::delete)
        else -> HashMap()
    }

    //@PostMapping("/namespace/{ns}/{kind}")
    fun post(@PathVariable kind: String, @PathVariable ns: String, @RequestBody body: Any): Map<String, Any> = when (kind) {
        "PersistentVolume" -> create { PersistentVolume().create(body as V1PersistentVolume) }
        else -> HashMap()
    }

    @PostMapping("/namespace/{ns}/PersistentVolume")
    fun createPersistentVolume(@PathVariable ns: String, @RequestBody body: V1PersistentVolume): Map<String, Any> = create { PersistentVolume().create(body) }

    @PostMapping("/namespace/{ns}/Ingress")
    fun createIngress(@PathVariable ns: String, @RequestBody body: ExtensionsV1beta1Ingress): Map<String, Any> = create { Ingress().create(ns, body) }

    @PostMapping("/namespace/{ns}/Deployment")
    fun createDeployment(@PathVariable ns: String, @RequestBody body: V1Deployment): Map<String, Any> = create { Deployment().create(ns, body) }

    @PostMapping("/namespace/{ns}/Service")
    fun createService(@PathVariable ns: String, @RequestBody body: V1Service): Map<String, Any> = create { Service().create(ns, body) }

    @PostMapping("/namespace/{ns}/PersistentVolumeClaim")
    fun createPersistentVolumeClaim(@PathVariable ns: String, @RequestBody body: V1PersistentVolumeClaim): Map<String, Any> = create { PersistentVolumeClaim().create(ns, body) }

    @PostMapping("/namespace/{ns}/ConfigMap")
    fun createConfigmap(@PathVariable ns: String, @RequestBody body: V1ConfigMap): Map<String, Any> = create { configMapService.create(ns, body) }

    //删除Configmap中的字段
    @DeleteMapping("/namespace/{ns}/daleteConfigMapData/{name}/{key}")
    fun deleteConfigMapData(@PathVariable ns: String, @PathVariable name: String, @PathVariable key: String): Map<String, Any> = delete("", "") { _, _ -> configMapService.deleteData(ns, name, key) }

    //为Configmap新增字段
    @PutMapping("/addConfigMapData")
    fun addConfigMapData(@RequestBody context: CmContext): Map<String, Any> = create { configMapService.update(context) }

    @PostMapping("/namespace/{ns}/ReplicaSet")
    fun createReplicaSet(@PathVariable ns: String, @RequestBody body: V1ReplicaSet): Map<String, Any> = create { ReplicaSet().create(ns, body) }

    @PostMapping("/namespace/{ns}/DaemonSet")
    fun createDaemonSet(@PathVariable ns: String, @RequestBody body: V1DaemonSet): Map<String, Any> = create { DaemonSet().create(ns, body) }

    @PostMapping("/namespace/{ns}/ReplicationController")
    fun createReplicationController(@PathVariable ns: String, @RequestBody body: V1ReplicationController): Map<String, Any> = create { ReplicationController().create(ns, body) }

    @PostMapping("/namespace/{ns}/StatefulSet")
    fun createStatefulSet(@PathVariable ns: String, @RequestBody body: V1StatefulSet): Map<String, Any> = create { StatefulSet().create(ns, body) }

    @PostMapping("/namespace/{ns}/Job")
    fun createJob(@PathVariable ns: String, @RequestBody body: V1Job): Map<String, Any> = create { Job().create(ns, body) }

    @PostMapping("/namespace/{ns}/CronJob")
    fun createCronJob(@PathVariable ns: String, @RequestBody body: V1beta1CronJob): Map<String, Any> = create { CronJob().create(ns, body) }

    @PostMapping("/namespace/{ns}/Secret")
    fun createSecret(@PathVariable ns: String, @RequestBody body: V1Secret): Map<String, Any> = create { Secret().create(ns, body) }

    @PostMapping("/namespace/{ns}/ServiceAccount")
    fun createServiceAccount(@PathVariable ns: String, @RequestBody body: V1ServiceAccount): Map<String, Any> = create { ServiceAccount().create(ns, body) }

    @PostMapping("/namespace/{ns}/HorizontalPodAutoscaler")
    fun createHorizontalPodAutoscaler(@PathVariable ns: String, @RequestBody body: V1HorizontalPodAutoscaler): Map<String, Any> = create { HorizontalPodAutoscaler().create(ns, body) }

    @PostMapping("/namespace/{ns}/ClusterRoleBinding")
    fun createClusterRoleBinding(@PathVariable ns: String, @RequestBody body: V1ClusterRoleBinding): Map<String, Any> = create { ClusterRoleBinding().create(ns, body) }

    @PostMapping("/namespace/{ns}/ClusterRole")
    fun createClusterRole(@PathVariable ns: String, @RequestBody body: V1ClusterRole): Map<String, Any> = create { ClusterRole().create(ns, body) }

    @PostMapping("/namespace/{ns}/Role")
    fun createRole(@PathVariable ns: String, @RequestBody body: V1Role): Map<String, Any> = create { Role().create(ns, body) }

    @PostMapping("/namespace/{ns}/RoleBinding")
    fun createRoleBinding(@PathVariable ns: String, @RequestBody body: V1RoleBinding): Map<String, Any> = create { RoleBinding().create(ns, body) }

    @PostMapping("/namespace/{ns}/ResourceQuota")
    fun createResourceQuota(@PathVariable ns: String, @RequestBody body: V1ResourceQuota): Map<String, Any> = create { ResourceQuota().create(ns, body) }

    @PostMapping("/namespace/{ns}/LimitRange")
    fun createLimitRange(@PathVariable ns: String, @RequestBody body: V1LimitRange): Map<String, Any> = create { LimitRange().create(ns, body) }

    private fun update(body: Any, updatefun: (Any) -> Unit): Map<String, Any> {
        val result = HashMap<String, Any>()
        try {
            updatefun(body)
            result["success"] = true
            result["msg"] = "更新成功"
        } catch (e: ApiException) {
            e.printStackTrace()
            errorMsg(e, result)
        }
        return result
    }

    private fun create(createfun: () -> Unit): Map<String, Any> {
        val result = HashMap<String, Any>()
        try {
            createfun()
            result["success"] = true
            result["msg"] = "新建成功"
        } catch (e: ApiException) {
            e.printStackTrace()
            errorMsg(e, result)
        }
        return result
    }

    private fun delete(ns: String, name: String, deletefun: (String, String) -> Unit): Map<String, Any> {
        val result = HashMap<String, Any>()
        try {
            deletefun(ns, name)
            result["success"] = true
            result["msg"] = "删除成功"
        } catch (e: ApiException) {
            e.printStackTrace()
            errorMsg(e, result)
        }
        return result
    }

    inner class Event {
        fun list(ns: String): List<V1Event> {
            val kubeApi = ClientUtil.kubeApi()
            return kubeApi.listNamespacedEvent(ns, null, null, null, null,
                    null, null, null, null, null).items
                    .sortedBy { it.metadata?.creationTimestamp }.reversed()
        }
    }

    inner class NameSpace {
        val filterList = arrayOf("default", "kube-public", "kube-system")
        fun list(): List<V1Namespace> {
            val kubeApi = ClientUtil.kubeApi()
            log.info("allnamespace,kubeApi,{}",kubeApi)
            val list = kubeApi.listNamespace("false", false, null, null, null,
                    null, null, null, null).items
                    .sortedBy { it.metadata?.creationTimestamp }.reversed()
                    .filter {
                        it.metadata?.name !in filterList && it.status?.phase != "Terminating"
                    }
            log.info("allnamespace,{}",list.size)
            return list
        }

        fun listname(): List<String> {
            return list().map { it.metadata!!.name!! }
        }

        fun nameExists(name: String): Boolean {
            val obj = list().find { it.metadata?.name == name }
            return obj != null
        }

        fun nameExistsStatus(name: String): Map<String, Any> {
            if (name in NameSpace().filterList) {
                return mapOf("success" to false, "msg" to "系统空间，不可操作")
            }
            val obj = list().find { it.metadata?.name == name }
            if (obj == null) {
                return mapOf("success" to true, "msg" to "名称可用")
            } else {
                if (obj.status?.phase == "Terminating") {
                    return mapOf("success" to false, "msg" to "${name}正在删除中，稍后再试")
                } else {
                    return mapOf("success" to false, "msg" to "名称重复")
                }
            }
        }

        fun delete(ns: String) {
            val kubeApi = ClientUtil.kubeApi()
            kubeApi.deleteNamespace(ns, null, null, null, null, null, null)
            try {
                //Thread.sleep(5000)
            } catch (e: java.lang.Exception) {

            }
        }
    }

    inner class ResourceQuota {
        fun buildMap(quota: V1ResourceQuota, podNum: Int) = HashMap<String, Any?>().apply {
            this["name"] = quota.metadata?.name
            this["ns"] = quota.metadata?.namespace
            this["labels"] = quota.metadata?.labels
            this["uid"] = quota.metadata?.uid
            this["podNum"] = podNum
            this["hard"] = quota.status?.hard
            this["used"] = quota.status?.used
        }

        fun buildQuota(ns: String, hard: Map<String, Quantity>): V1ResourceQuota {
            val kubeApi = ClientUtil.kubeApi()
            return V1ResourceQuotaBuilder()
                    .withNewMetadata().withName("quota-$ns").endMetadata()
                    .withNewSpec().addToHard(hard).endSpec().build().apply {
                        log.info("create new default quota in ns:{}", ns)
                        kubeApi.createNamespacedResourceQuota(ns, this, null, null, null)
                    }
        }

        fun listAll(): List<Any> {
            val kubeApi = ClientUtil.kubeApi()
            val podNum = kubeApi.listPodForAllNamespaces(false, null, null, null,
                    null, null, null, 0, false).items.size
            return kubeApi.listResourceQuotaForAllNamespaces(false, null, "",
                    "", null, "true", null, 0, false).items
                    .map { buildMap(it, podNum) }
        }

        fun list(ns: String): List<Any> {
            val kubeApi = ClientUtil.kubeApi()
            val podNum = kubeApi.listNamespacedPod(ns, null, null, null, null,
                    null, null, null, 0, false).items.size
            return kubeApi.listNamespacedResourceQuota(ns, null, null, "",
                    "", null, null, null, 0, false).items
                    .map { buildMap(it, podNum) }
        }

        fun rawList(): V1ResourceQuotaList =   ClientUtil.kubeApi().listResourceQuotaForAllNamespaces(null, null, "",
                "", null, null, null, 0, false)

        fun read(ns: String, name: String): V1ResourceQuota {
            val q = ClientUtil.kubeApi().readNamespacedResourceQuota(name, ns, null, true, null)
            return q
        }

        fun create(ns: String, quota: V1ResourceQuota) {
            ClientUtil.kubeApi().createNamespacedResourceQuota(ns, quota, null, null, null)
        }

        fun update(quota: V1ResourceQuota) {
            CoreV1Api(ClientUtil.updateClient()).replaceNamespacedResourceQuota(quota.metadata?.name, quota.metadata?.namespace, quota, null, null, null)
        }

        fun delete(ns: String, name: String) {
            ClientUtil.kubeApi().deleteNamespacedResourceQuota(name, ns, "false", null, null, null, null, null)
        }
    }

    inner class LimitRange {
        fun read(ns: String, name: String): V1LimitRange {
            val l = ClientUtil.kubeApi().readNamespacedLimitRange(name, ns, null, true, true)
            return l
        }

        fun rawList() = ClientUtil.kubeApi().listLimitRangeForAllNamespaces(null, null,
                null, null, null, null, null, 0, false)

        fun create(ns: String, limit: V1LimitRange) {
            ClientUtil.kubeApi().createNamespacedLimitRange(ns, limit, null, null, null)
        }

        fun update(limit: V1LimitRange) {
            log.info("sdsd:{}", limit)
            CoreV1Api(ClientUtil.updateClient()).replaceNamespacedLimitRange(limit.metadata?.name, limit.metadata?.namespace, limit, null, null, null)
        }

        fun delete(ns: String, name: String) {
            ClientUtil.kubeApi().deleteNamespacedLimitRange(name, ns, "false", null, null, null, null, null)
        }

        fun buildRange(ns: String, limit: Map<String, Quantity>,
                       min: Map<String, Quantity>? = mapOf("cpu" to Quantity("100m"), "memory" to Quantity("100Mi")),
                       default: Map<String, Quantity>? = mapOf("cpu" to Quantity("500m"), "memory" to Quantity("300Mi")),
                       defaultRequest: Map<String, Quantity>? = mapOf("cpu" to Quantity("400m"), "memory" to Quantity("200Mi"))
        ): V1LimitRange {

            return V1LimitRangeBuilder()
                    .withNewMetadata().withName("limits-$ns").endMetadata()
                    .withNewSpec().addNewLimit()
                    .addToMax(limit).addToDefault(default).addToMin(min).addToDefaultRequest(defaultRequest)
                    .withNewType("Container")
                    .endLimit().endSpec().build().apply {
                        log.info("create new default limitRange in ns:{}", ns)
                        ClientUtil.kubeApi().createNamespacedLimitRange(ns, this, null, null, null)
                    }
        }
    }

    inner class Service {
        fun update(service: V1Service) {
            CoreV1Api(ClientUtil.updateClient()).replaceNamespacedServiceWithHttpInfo(service.metadata?.name, service.metadata?.namespace, service,
                    "true", null, null)

        }

        fun list(ns: String): List<Any> {
            return ClientUtil.kubeApi().listNamespacedService(ns, "true",
                    null, null, null, null, null, null, 0, false)
                    .items
        }

        fun read(ns: String, name: String): V1Service {
            val a = ClientUtil.kubeApi().readNamespacedService(name, ns, "false", null, null)
            return a
        }

        fun delete(ns: String, name: String) {
            ClientUtil.kubeApi().deleteNamespacedServiceWithHttpInfo(name, ns, "false", null, null, null, null, null)
        }

        fun create(ns: String, service: V1Service) {
            ClientUtil.kubeApi().createNamespacedService(ns, service, "false", null, null)
        }
    }

    inner class Ingress {
        fun list(ns: String): List<Any> {
            return ExtensionsV1beta1Api(ClientUtil.apiClient()).listNamespacedIngress(ns, "false",
                    null, null, null, null, null, null, 0, false)
                    .items

        }

        fun read(ns: String, name: String): ExtensionsV1beta1Ingress {
            var a = ExtensionsV1beta1Api(ClientUtil.apiClient()).readNamespacedIngress(name, ns, "true", null, null)
            return a
        }

        fun update(ingress: ExtensionsV1beta1Ingress) {
            ExtensionsV1beta1Api(ClientUtil.updateClient())
                    .replaceNamespacedIngressWithHttpInfo(ingress.metadata?.name, ingress.metadata?.namespace, ingress,
                            "true", null, null)
        }

        fun delete(ns: String, name: String) {
            ExtensionsV1beta1Api(ClientUtil.apiClient())
                    .deleteNamespacedIngressWithHttpInfo(name, ns, "true", null, null, null, null, null)
        }

        fun create(ns: String, ingress: ExtensionsV1beta1Ingress) {
            ExtensionsV1beta1Api(ClientUtil.apiClient()).createNamespacedIngressWithHttpInfo(ns, ingress, "true", null, null)
        }
    }

    inner class PersistentVolume {
        fun list(): List<Any> {
            return ClientUtil.kubeApi().listPersistentVolume("true",
                    null, null, "", null, null, null, 0, false)
                    .items
        }

        fun read(name: String): V1PersistentVolume {
            val a = ClientUtil.kubeApi().readPersistentVolume(name, "ture", null, null)
            return a
        }

        fun update(persistentVolume: V1PersistentVolume) {
            CoreV1Api(ClientUtil.updateClient())
                    .replacePersistentVolumeWithHttpInfo(persistentVolume.metadata?.name, persistentVolume,
                            "true", null, null)
        }

        fun delete(name: String) {
            ClientUtil.kubeApi().deletePersistentVolumeWithHttpInfo(name, "true", null, null, null, null, null)
        }

        fun create(persistentVolume: V1PersistentVolume) {
            ClientUtil.kubeApi().createPersistentVolumeWithHttpInfo(persistentVolume, "true", null, null)
        }
    }

    inner class PersistentVolumeClaim {
        fun rawList(ns: String) = ClientUtil.kubeApi().listNamespacedPersistentVolumeClaim(ns, "true",
                null, null, null, null, null, null, 0, false)
                .items

        fun list(ns: String): List<Any> {
            return rawList(ns)
        }

        fun read(ns: String, name: String): V1PersistentVolumeClaim {
            val a = ClientUtil.kubeApi().readNamespacedPersistentVolumeClaim(name, ns, "true", null, null)
            return a
        }

        fun update(persistentVolumeClaim: V1PersistentVolumeClaim) {
            CoreV1Api(ClientUtil.updateClient())
                    .replaceNamespacedPersistentVolumeClaimWithHttpInfo(persistentVolumeClaim.metadata?.name,
                            persistentVolumeClaim.metadata?.namespace, persistentVolumeClaim,
                            "true", null, null)
        }

        fun delete(ns: String, name: String) {
            ClientUtil.kubeApi().deleteNamespacedPersistentVolumeClaimWithHttpInfo(name, ns, "true", null, null, null, null, null)
        }

        fun create(ns: String, persistentVolumeClaim: V1PersistentVolumeClaim) {
            ClientUtil.kubeApi().createNamespacedPersistentVolumeClaimWithHttpInfo(ns, persistentVolumeClaim, "true", null, null)
        }
    }

    inner class Deployment {

        fun rawList(ns: String) = AppsV1Api(ClientUtil.apiClient()).listNamespacedDeployment(ns, null,
                null, null, null, null, null, null, 0, false)
                .items

        fun list(ns: String): List<V1Deployment> {
            ///apis/apps/v1/namespaces/{namespace}/deployments
            return rawList(ns)
        }

        fun read(ns: String, name: String): V1Deployment {
            val a = AppsV1Api(ClientUtil.apiClient()).readNamespacedDeployment(name, ns, "true", null, null)
            return a
        }

        fun update(deployment: V1Deployment) {
            AppsV1Api(ClientUtil.updateClient())
                    .replaceNamespacedDeploymentWithHttpInfo(deployment.metadata?.name, deployment.metadata?.namespace, deployment,
                            "true", null, null)
        }

        fun delete(ns: String, name: String) {
            AppsV1Api(ClientUtil.apiClient())
                    .deleteNamespacedDeploymentWithHttpInfo(name, ns, "true", null, null, null, null, null)
        }

        fun create(ns: String, deployment: V1Deployment) {
            AppsV1Api(ClientUtil.apiClient()).createNamespacedDeploymentWithHttpInfo(ns, deployment, "true", null, null)
        }
    }

    inner class ReplicaSet {
        fun update(replicaSet: V1ReplicaSet) {
            AppsV1Api(ClientUtil.updateClient())
                    .replaceNamespacedReplicaSetWithHttpInfo(replicaSet.metadata?.name, replicaSet.metadata?.namespace, replicaSet, null, null, null)

        }

        fun delete(ns: String, name: String) {
            AppsV1Api(ClientUtil.apiClient())
                    .deleteNamespacedReplicaSet(name, ns, null, null, null, null, null, null)

        }

        fun create(ns: String, replicaSet: V1ReplicaSet) {
            AppsV1Api(ClientUtil.apiClient()).createNamespacedReplicaSetWithHttpInfo(ns, replicaSet, null, null, null)
        }
    }

    inner class DaemonSet {
        fun update(daemonSet: V1DaemonSet) {
            AppsV1Api(ClientUtil.updateClient())
                    .replaceNamespacedDaemonSetWithHttpInfo(daemonSet.metadata?.name, daemonSet.metadata?.namespace, daemonSet, null, null, null)

        }

        fun delete(ns: String, name: String) {
            AppsV1Api(ClientUtil.apiClient())
                    .deleteNamespacedDaemonSet(name, ns, null, null, null, null, null, null)

        }

        fun create(ns: String, daemonSet: V1DaemonSet) {
            AppsV1Api(ClientUtil.apiClient()).createNamespacedDaemonSet(ns, daemonSet, null, null, null)
        }

    }

    inner class ReplicationController {
        fun update(rc: V1ReplicationController) {
            CoreV1Api(ClientUtil.updateClient())
                    .replaceNamespacedReplicationControllerWithHttpInfo(rc.metadata?.name, rc.metadata?.namespace, rc, null, null, null)
        }

        fun delete(ns: String, name: String) {
            ClientUtil.kubeApi().deleteNamespacedReplicationControllerWithHttpInfo(name, ns, null, null, null, null, null, null)
        }

        fun create(ns: String, rc: V1ReplicationController) {
            ClientUtil.kubeApi().createNamespacedReplicationControllerWithHttpInfo(ns, rc, null, null, null)
        }
    }

    inner class StatefulSet {
        fun update(daemonSet: V1StatefulSet) {

            AppsV1Api(ClientUtil.updateClient())
                    .replaceNamespacedStatefulSetWithHttpInfo(daemonSet.metadata?.name, daemonSet.metadata?.namespace, daemonSet, null, null, null)

        }

        fun delete(ns: String, name: String) {
            AppsV1Api(ClientUtil.apiClient())
                    .deleteNamespacedStatefulSet(name, ns, null, null, null, null, null, null)

        }

        fun create(ns: String, daemonSet: V1StatefulSet) {
            AppsV1Api(ClientUtil.apiClient()).createNamespacedStatefulSet(ns, daemonSet, null, null, null)
        }
    }

    inner class Job {
        fun update(job: V1Job) {
            BatchV1Api(ClientUtil.updateClient())
                    .replaceNamespacedJob(job.metadata?.name, job.metadata?.namespace, job, null, null, null)

        }

        fun delete(ns: String, name: String) {
            BatchV1Api(ClientUtil.apiClient())
                    .deleteNamespacedJob(name, ns, null, null, null, null, null, null)

        }

        fun create(ns: String, job: V1Job) {
            BatchV1Api(ClientUtil.apiClient()).createNamespacedJob(ns, job, null, null, null)
        }
    }

    inner class CronJob {
        fun update(job: V1beta1CronJob) {
            BatchV1beta1Api(ClientUtil.updateClient())
                    .replaceNamespacedCronJob(job.metadata?.name, job.metadata?.namespace, job, null, null, null)

        }

        fun delete(ns: String, name: String) {
            BatchV1beta1Api(ClientUtil.apiClient())
                    .deleteNamespacedCronJob(name, ns, null, null, null, null, null, null)

        }

        fun create(ns: String, job: V1beta1CronJob) {
            BatchV1beta1Api(ClientUtil.apiClient()).createNamespacedCronJob(ns, job, null, null, null)
        }
    }

    inner class Secret {
        fun create(ns: String, sec: V1Secret) {
            ClientUtil.kubeApi().createNamespacedSecret(ns, sec, null, null, null)
        }

        fun update(rc: V1Secret) {
            CoreV1Api(ClientUtil.updateClient())
                    .replaceNamespacedSecret(rc.metadata?.name, rc.metadata?.namespace, rc, null, null, null)
        }

        fun delete(ns: String, name: String) {
            ClientUtil.kubeApi().deleteNamespacedSecret(name, ns, null, null, null, null, null, null)
        }

    }

    inner class ServiceAccount {
        fun create(ns: String, sec: V1ServiceAccount) {
            ClientUtil.kubeApi().createNamespacedServiceAccount(ns, sec, null, null, null)
        }

        fun update(rc: V1ServiceAccount) {
            CoreV1Api(ClientUtil.updateClient())
                    .replaceNamespacedServiceAccount(rc.metadata?.name, rc.metadata?.namespace, rc, null, null, null)
        }

        fun delete(ns: String, name: String) {
            ClientUtil.kubeApi().deleteNamespacedServiceAccount(name, ns, null, null, null, null, null, null)
        }
    }

    inner class HorizontalPodAutoscaler {
        fun update(job: V1HorizontalPodAutoscaler) {

            AutoscalingV1Api(ClientUtil.updateClient())
                    .replaceNamespacedHorizontalPodAutoscaler(job.metadata?.name, job.metadata?.namespace, job, null, null, null)

        }

        fun delete(ns: String, name: String) {
            AutoscalingV1Api(ClientUtil.apiClient())
                    .deleteNamespacedHorizontalPodAutoscaler(name, ns, null, null, null, null, null, null)

        }

        fun create(ns: String, job: V1HorizontalPodAutoscaler) {
            AutoscalingV1Api(ClientUtil.apiClient()).createNamespacedHorizontalPodAutoscaler(ns, job, null, null, null)
        }

    }

    inner class Role {
        fun update(job: V1Role) {
            RbacAuthorizationV1Api(ClientUtil.updateClient())
                    .replaceNamespacedRole(job.metadata?.name, job.metadata?.namespace, job, null, null, null)

        }

        fun delete(ns: String, name: String) {
            RbacAuthorizationV1Api(ClientUtil.apiClient())
                    .deleteNamespacedRole(name, ns, null, null, null, null, null, null)

        }

        fun create(ns: String, job: V1Role) {
            RbacAuthorizationV1Api(ClientUtil.apiClient()).createNamespacedRole(ns, job, null, null, null)
        }
    }

    inner class RoleBinding {
        fun update(job: V1RoleBinding) {
            RbacAuthorizationV1Api(ClientUtil.updateClient())
                    .replaceNamespacedRoleBinding(job.metadata?.name, job.metadata?.namespace, job, null, null, null)

        }

        fun delete(ns: String, name: String) {
            RbacAuthorizationV1Api(ClientUtil.apiClient())
                    .deleteNamespacedRoleBinding(name, ns, null, null, null, null, null, null)

        }

        fun create(ns: String, job: V1RoleBinding) {
            RbacAuthorizationV1Api(ClientUtil.apiClient()).createNamespacedRoleBinding(ns, job, null, null, null)
        }
    }

    inner class ClusterRole {
        fun update(job: V1ClusterRole) {
            RbacAuthorizationV1Api(ClientUtil.updateClient())
                    .replaceClusterRole(job.metadata?.name, job, null, null, null)

        }

        fun delete(ns: String, name: String) {
            RbacAuthorizationV1Api(ClientUtil.apiClient())
                    .deleteClusterRole(name, null, null, null, null, null, null)

        }

        fun create(ns: String, job: V1ClusterRole) {
            RbacAuthorizationV1Api(ClientUtil.apiClient()).createClusterRole(job, null, null, null)
        }
    }

    inner class ClusterRoleBinding {
        fun update(job: V1ClusterRoleBinding) {
            RbacAuthorizationV1Api(ClientUtil.updateClient())
                    .replaceClusterRoleBinding(job.metadata?.name, job, null, null, null)

        }

        fun delete(ns: String, name: String) {
            RbacAuthorizationV1Api(ClientUtil.apiClient())
                    .deleteClusterRoleBinding(name, null, null, null, null, null, null)

        }

        fun create(ns: String, job: V1ClusterRoleBinding) {
            RbacAuthorizationV1Api(ClientUtil.apiClient()).createClusterRoleBinding(job, null, null, null)
        }
    }

}

