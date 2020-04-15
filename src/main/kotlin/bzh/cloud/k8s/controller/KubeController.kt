package bzh.cloud.k8s.controller


import bzh.cloud.k8s.config.CmContext

import bzh.cloud.k8s.config.UpdateClient
import bzh.cloud.k8s.config.watchClient
import bzh.cloud.k8s.expansion.metricsNode
import bzh.cloud.k8s.service.ConfigMapService
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
import com.google.gson.reflect.TypeToken
import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.apis.ExtensionsV1beta1Api
import io.kubernetes.client.openapi.models.*
import io.kubernetes.client.util.Watch
import kotlinx.coroutines.*
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponse
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.concurrent.Executor

class NsWithQuota{
    var name = ""
    var quotaCpu =""
    var quotaMemory=""
    var quotaPods = 0
    var limitCpu =""
    var limitMemory =""
    var defaultCpu = ""
    var defaultMemory =""
    var defaultRequestCpu =""
    var defaultRequestMemory =""
    var minCpu = ""
    var minMemory =""

}


@RestController
@RequestMapping("/kube")
class KubeController(
        val kubeApi: CoreV1Api,
        val extensionApi: ExtensionsV1beta1Api,
        val configMapService: ConfigMapService,
        val threadPool : Executor
): CoroutineScope by CoroutineScope(Dispatchers.Default) {
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
            kubeApi.createNamespace(nsobj, "false", null, null)
        }
    }

    @GetMapping("/watch/allResourcequota", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun watchlist(response: ServerHttpResponse):Flux<V1ResourceQuota>  {
        val (client,api) = watchClient()

        val watch = Watch.createWatch<V1ResourceQuota>(client,
                api.listResourceQuotaForAllNamespacesCall(null, null, "",
                        "", null, null, null, 0, true,null),
                object : TypeToken<Watch.Response<V1ResourceQuota>>() {}.type )
        //var job:Job?=null
        return Flux.create<V1ResourceQuota> { sink->
            val p = V1ResourceQuotaBuilder().withNewMetadata().withName("heart beat").endMetadata().build()
            sink.next(p)
            launch {

                try {
                    watch.forEach {
                        log.info("watch quota:{}",it.`object`.metadata?.name)
                        sink.next(it.`object`)
                    }
                } catch (e: RuntimeException) {
                    log.info("watch  quota RuntimeException")
                    //e.printStackTrace()
                }
            }
            launch {
                repeat(1000) {
                    if(sink.isCancelled){
                        this.cancel()
                    }
                    delay(20*1000)
                    //log.info("heart beat,{}",sink.isCancelled)
                    sink.next(p)
                }
            }
        }.doFinally{
            log.info("/watch/allResourcequota complete")
            watch.close()
        }
    }

    @GetMapping("/namespace")
    fun nameSpaceList(): List<String> = NameSpace().listname()

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
            val quota = Resourcequota().rawList().items.find { it.metadata?.name == "quota-$ns" }
            val limit = LimitRange().rawList().items.find { it.metadata?.name == "range-$ns" }

            if (quota == null) {
                Resourcequota().buildQuota(ns, quotaMap)
            } else {
                quota.spec?.hard = quotaMap
                Resourcequota().update(quota)
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
                LimitRange().buildRange(ns, limitMap,minMap,defaultMap,defaultRequestMap)
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
        val quotaList = Resourcequota().rawList()
        val limitrangeList = LimitRange().rawList()
        return nsList.filter {
            if (StringUtils.isEmpty(name)) {
                true
            } else {
                it.contains(name!!)
            }
        }.map { ns ->
            val quota = quotaList.items.find { it.metadata?.namespace == ns }
                    ?: Resourcequota().buildQuota(ns, quotaMap)
            val limitRange = limitrangeList.items.find { it.metadata?.namespace == ns }
                    ?: LimitRange().buildRange(ns, limitMap)
            NsWithQuota().apply {
                try {
                    this.name = ns
                    quotaCpu = quota.spec?.hard?.get("cpu")?.toSuffixedString()!!
                    quotaMemory = quota.spec?.hard?.get("memory")?.toSuffixedString()!!
                    quotaPods = quota.spec?.hard?.get("pods")?.toSuffixedString()!!.toInt()
                    limitCpu = limitRange.spec?.limits?.get(0)?.max?.get("cpu")?.toSuffixedString()!!
                    limitMemory = limitRange.spec?.limits?.get(0)?.max?.get("memory")?.toSuffixedString()!!

                    defaultCpu = limitRange.spec?.limits?.get(0)?.default?.get("cpu")?.toSuffixedString()!!
                    defaultMemory = limitRange.spec?.limits?.get(0)?.default?.get("memory")?.toSuffixedString()!!

                    defaultRequestCpu = limitRange.spec?.limits?.get(0)?.defaultRequest?.get("cpu")?.toSuffixedString()!!
                    defaultRequestMemory = limitRange.spec?.limits?.get(0)?.defaultRequest?.get("memory")?.toSuffixedString()!!

                    minCpu = limitRange.spec?.limits?.get(0)?.min?.get("cpu")?.toSuffixedString()!!
                    minMemory = limitRange.spec?.limits?.get(0)?.min?.get("memory")?.toSuffixedString()!!
                } catch (e: java.lang.Exception) {
                    log.info("{},{}", quota, limitRange)
                }
            }
        }
    }


    @GetMapping("/node")
    fun nodes(): List<V1Node> {
        val result = kubeApi.listNode("", false,
                null, "", null, null, null, 0, false)
        return result.items
    }

    @GetMapping("/metrics/runingstatus/{ns}")
    fun runingstatus(@PathVariable ns: String): Map<String, Any?> {
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
        val metlist = kubeApi.metricsNode()
        val podList = kubeApi.listPodForAllNamespaces(false, null, null, null,
                null, null, null, 0, false).items
        val nodelist = kubeApi.listNode(null, false, null, null, null,
                0, null, 0, false)
        val allcpu = nodelist.items?.map { it.status?.capacity?.get("cpu")?.number }?.reduce { acc, i -> acc?.add(i) }
        val allmemory = nodelist.items?.map { it.status?.capacity?.get("memory")?.number }?.reduce { acc, i -> acc?.add(i) }

        val totalusememory = metlist.items?.map { it.usage?.get("memory")?.number }?.reduce { acc, i -> acc?.add(i) }
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
                this["pods"] = nodeItem?.status?.allocatable?.get("pods")?.toSuffixedString()
            }


            map["capacity"] = nodeItem?.status?.capacity
            map["allocatable"] = nodeItem?.status?.allocatable
            map
        }
    }

    @GetMapping("/ResourcequotaAllns")
    fun resourcequotaAllns(): List<Any> = Resourcequota().listAll()





    @GetMapping("/namespace/{ns}/{kind}")
    fun list(response: ServerHttpResponse, @PathVariable kind: String, @PathVariable ns: String): List<Any> = when (kind) {
        "Ingress" -> Ingress().list(ns)
        "Service" -> Service().list(ns)
        "PersistentVolume" -> PersistentVolume().list()
        "PersistentVolumeClaim" -> PersistentVolumeClaim().list(ns)
        "Deployment" -> Deployment().list(ns)
        "ConfigMap" -> configMapService.list(ns)
        "Resourcequota" -> Resourcequota().list(ns)
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
        "Resourcequota" -> Resourcequota().read(ns, name)
        else -> {
            response.setStatusCode(HttpStatus.NOT_FOUND)
            Any()
        }
    }


    @PutMapping("/PersistentVolume")
    fun updatePersistentVolume(@RequestBody body: V1PersistentVolume): Map<String, Any> = update(body) { PersistentVolume().update(it as V1PersistentVolume) }

    @PutMapping("/Ingress/read")
    fun updateIngress(@RequestBody body: ExtensionsV1beta1Ingress): Map<String, Any> = update(body) { Ingress().update(it as ExtensionsV1beta1Ingress) }

    @PutMapping("/Deployment")
    fun updateDeployment(@RequestBody body: ExtensionsV1beta1Deployment): Map<String, Any> = update(body) { Deployment().update(it as ExtensionsV1beta1Deployment) }

    @PutMapping("/Service")
    fun updateService(@RequestBody body: V1Service): Map<String, Any>? = update(body) { Service().update(it as V1Service) }

    @PutMapping("/PersistentVolumeClaim")
    fun updatePersistentVolumeClaim(@RequestBody body: V1PersistentVolumeClaim): Map<String, Any> = update(body) { PersistentVolumeClaim().update(it as V1PersistentVolumeClaim) }

    @PutMapping("/ConfigMap")
    fun updateConfigmap(@RequestBody body: V1ConfigMap): Map<String, Any> = update(body) { configMapService.update(it as V1ConfigMap) }

    @DeleteMapping("/namespace/{ns}/{kind}/{name}")
    fun delete(@PathVariable kind: String, @PathVariable ns: String, @PathVariable name: String): Map<String, Any> = when (kind) {
        "Ingress" -> delete(ns, name, Ingress()::delete)
        "Service" -> delete(ns, name, Service()::delete)
        "PersistentVolume" -> delete("", "") { _, _ -> PersistentVolume().delete(name) }
        "PersistentVolumeClaim" -> delete(ns, name, PersistentVolumeClaim()::delete)
        "Deployment" -> delete(ns, name, Deployment()::delete)
        "ConfigMap" -> delete(ns, name, configMapService::delete)
        else -> HashMap()
    }


    @PostMapping("/namespace/{ns}/PersistentVolume")
    fun createPersistentVolume(@PathVariable ns: String, @RequestBody body: V1PersistentVolume): Map<String, Any> = create { PersistentVolume().create(body) }

    @PostMapping("/namespace/{ns}/Ingress")
    fun createIngress(@PathVariable ns: String, @RequestBody body: ExtensionsV1beta1Ingress): Map<String, Any> = create { Ingress().create(ns, body) }

    @PostMapping("/namespace/{ns}/Deployment")
    fun createDeployment(@PathVariable ns: String, @RequestBody body: ExtensionsV1beta1Deployment): Map<String, Any> = create { Deployment().create(ns, body) }

    @PostMapping("/namespace/{ns}/Service")
    fun createService(@PathVariable ns: String, @RequestBody body: V1Service): Map<String, Any>? = create { Service().create(ns, body) }

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
            return kubeApi.listNamespacedEvent(ns, null, null, null, null,
                    null, null, null, null, null).items
                    .sortedBy { it.metadata?.creationTimestamp }.reversed()
        }
    }

    inner class NameSpace {
        val filterList = arrayOf("default", "kube-public", "kube-system")
        fun list(): List<V1Namespace> {
            return kubeApi.listNamespace("false", false, null, null, null,
                    null, null, null, null).items
                    .sortedBy { it.metadata?.creationTimestamp }.reversed()
                    .filter {
                        it.metadata?.name !in filterList && it.status?.phase != "Terminating"
                    }
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
            kubeApi.deleteNamespace(ns, null, null, null, null, null, null)
            try {
                //Thread.sleep(5000)
            } catch (e: java.lang.Exception) {

            }
        }
    }

    inner class Resourcequota {
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
            return V1ResourceQuotaBuilder()
                    .withNewMetadata().withName("quota-$ns").endMetadata()
                    .withNewSpec().addToHard(hard).endSpec().build().apply {
                        log.info("create new default quota in ns:{}", ns)
                        kubeApi.createNamespacedResourceQuota(ns, this, null, null, null)
                    }
        }

        fun listAll(): List<Any> {
            val podNum = kubeApi.listPodForAllNamespaces(false, null, null, null,
                    null, null, null, 0, false).items.size
            return kubeApi.listResourceQuotaForAllNamespaces(false, null, "",
                    "", null, "true", null, 0, false).items
                    .map { buildMap(it, podNum) }
        }

        fun list(ns: String): List<Any> {
            val podNum = kubeApi.listNamespacedPod(ns, null, null, null, null,
                    null, null, null, 0, false).items.size
            return kubeApi.listNamespacedResourceQuota(ns, null, null, "",
                    "", null, null, null, 0, false).items
                    .map { buildMap(it, podNum) }
        }

        fun rawList(): V1ResourceQuotaList = kubeApi.listResourceQuotaForAllNamespaces(null, null, "",
                "", null, null, null, 0, false)

        fun read(ns: String, name: String): V1ResourceQuota {
            val q = kubeApi.readNamespacedResourceQuota(name, ns, null, true, null)
            return q
        }

        fun create(ns: String, quota: V1ResourceQuota) {
            kubeApi.createNamespacedResourceQuota(ns, quota, null, null, null)
        }

        fun update(quota: V1ResourceQuota) {
            UpdateClient().replaceNamespacedResourceQuota(quota.metadata?.name, quota.metadata?.namespace, quota, null, null, null)
        }
    }

    inner class LimitRange {
        fun read(ns: String, name: String): V1LimitRange {
            val l = kubeApi.readNamespacedLimitRange(name, ns, null, true, true)
            return l
        }

        fun rawList() = kubeApi.listLimitRangeForAllNamespaces(null, null,
                null, null, null, null, null, 0, false)

        fun create(ns: String, limit: V1LimitRange) {
            kubeApi.createNamespacedLimitRange(ns, limit, null, null, null)
        }

        fun update(limit: V1LimitRange) {
            log.info("sdsd:{}", limit)
            UpdateClient().replaceNamespacedLimitRange(limit.metadata?.name, limit.metadata?.namespace, limit, null, null, null)
        }

        fun buildRange(ns: String, limit: Map<String, Quantity>,
                       min: Map<String, Quantity>? = mapOf("cpu" to Quantity("100m"), "memory" to Quantity("100Mi")),
                       default: Map<String, Quantity>? = mapOf("cpu" to Quantity("500m"), "memory" to Quantity("300Mi")),
                       defaultRequest: Map<String, Quantity>? = mapOf("cpu" to Quantity("400m"), "memory" to Quantity("200Mi"))
        ): V1LimitRange {

            return V1LimitRangeBuilder()
                    .withNewMetadata().withName("range-$ns").endMetadata()
                    .withNewSpec().addNewLimit()
                    .addToMax(limit).addToDefault(default).addToMin(min).addToDefaultRequest(defaultRequest)
                    .withNewType("Container")
                    .endLimit().endSpec().build().apply {
                        log.info("create new default limitRange in ns:{}", ns)
                        kubeApi.createNamespacedLimitRange(ns, this, null, null, null)
                    }
        }
    }

    inner class Service {
        fun update(service: V1Service) {
            UpdateClient().replaceNamespacedServiceWithHttpInfo(service.metadata?.name, service.metadata?.namespace, service,
                    "true", null, null)

        }

        fun list(ns: String): List<Any> {
            return kubeApi.listNamespacedService(ns, "true",
                    null, null, null, null, null, null, 0, false)
                    .items
                    .map {
                        val map = HashMap<String, Any?>()
                        map["name"] = it.metadata?.name
                        map["ns"] = it.metadata?.namespace
                        map["labels"] = it.metadata?.labels
                        map["uid"] = it.metadata?.uid
                        map
                    }
        }

        fun read(ns: String, name: String): V1Service {
            val a = kubeApi.readNamespacedService(name, ns, "false", null, null)
            a.metadata?.creationTimestamp = null
            return a
        }

        fun delete(ns: String, name: String) {
            kubeApi.deleteNamespacedServiceWithHttpInfo(name, ns, "false", null, null, null, null, null)
        }

        fun create(ns: String, service: V1Service) {
            kubeApi.createNamespacedService(ns, service, "false", null, null)
        }
    }

    inner class Ingress {
        fun list(ns: String): List<Any> {
            return extensionApi.listNamespacedIngress(ns, "false",
                    null, null, null, null, null, null, 0, false)
                    .items
                    .map {
                        val map = HashMap<String, Any?>()
                        map["name"] = it.metadata?.name
                        map["ns"] = it.metadata?.namespace
                        map["labels"] = it.metadata?.labels
                        map["uid"] = it.metadata?.uid
                        map
                    }

        }

        fun read(ns: String, name: String): ExtensionsV1beta1Ingress {
            var a = extensionApi.readNamespacedIngress(name, ns, "true", null, null)
            a.metadata?.creationTimestamp = null
            a.status = null
            return a
        }

        fun update(ingress: ExtensionsV1beta1Ingress) {
            val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(kubeConfigPath)))
                    .setOverridePatchFormat(V1Patch.PATCH_FORMAT_JSON_MERGE_PATCH).build()
            client.setDebugging(true)
            ExtensionsV1beta1Api(client)
                    .replaceNamespacedIngressWithHttpInfo(ingress.metadata?.name, ingress.metadata?.namespace, ingress,
                            "true", null, null)
        }

        fun delete(ns: String, name: String) {
            extensionApi
                    .deleteNamespacedIngressWithHttpInfo(name, ns, "true", null, null, null, null, null)
        }

        fun create(ns: String, ingress: ExtensionsV1beta1Ingress) {
            extensionApi.createNamespacedIngressWithHttpInfo(ns, ingress, "true", null, null)
        }
    }

    inner class PersistentVolume {
        fun list(): List<Any> {
            return kubeApi.listPersistentVolume("true",
                    null, null, "", null, null, null, 0, false)
                    .items
                    .map {
                        var map = HashMap<String, Any?>()
                        map["name"] = it.metadata?.name
                        map["ns"] = it.metadata?.namespace
                        map["labels"] = it.metadata?.labels
                        map["uid"] = it.metadata?.uid
                        map
                    }
        }

        fun read(name: String): V1PersistentVolume {
            val a = kubeApi.readPersistentVolume(name, "ture", null, null)
            a.metadata?.creationTimestamp = null
            return a
        }

        fun update(persistentVolume: V1PersistentVolume) {
            UpdateClient()
                    .replacePersistentVolumeWithHttpInfo(persistentVolume.metadata?.name, persistentVolume,
                            "true", null, null)
        }

        fun delete(name: String) {
            kubeApi.deletePersistentVolumeWithHttpInfo(name, "true", null, null, null, null, null)
        }

        fun create(persistentVolume: V1PersistentVolume) {
            kubeApi.createPersistentVolumeWithHttpInfo(persistentVolume, "true", null, null)
        }
    }

    inner class PersistentVolumeClaim {
        fun rawList(ns: String) = kubeApi.listNamespacedPersistentVolumeClaim(ns, "true",
                null, null, null, null, null, null, 0, false)
                .items

        fun list(ns: String): List<Any> {
            return rawList(ns)
                    .map {
                        var map = HashMap<String, Any?>()
                        map["name"] = it.metadata?.name
                        map["ns"] = it.metadata?.namespace
                        map["labels"] = it.metadata?.labels
                        map["uid"] = it.metadata?.uid
                        map
                    }
        }

        fun read(ns: String, name: String): V1PersistentVolumeClaim {
            val a = kubeApi.readNamespacedPersistentVolumeClaim(name, ns, "true", null, null)
            a.metadata?.creationTimestamp = null
            a.status?.conditions?.forEach {
                it.lastTransitionTime = null
            }
            return a
        }

        fun update(persistentVolumeClaim: V1PersistentVolumeClaim) {
            UpdateClient()
                    .replaceNamespacedPersistentVolumeClaimWithHttpInfo(persistentVolumeClaim.metadata?.name,
                            persistentVolumeClaim.metadata?.namespace, persistentVolumeClaim,
                            "true", null, null)
        }

        fun delete(ns: String, name: String) {
            kubeApi.deleteNamespacedPersistentVolumeClaimWithHttpInfo(name, ns, "true", null, null, null, null, null)
        }

        fun create(ns: String, persistentVolumeClaim: V1PersistentVolumeClaim) {
            kubeApi.createNamespacedPersistentVolumeClaimWithHttpInfo(ns, persistentVolumeClaim, "true", null, null)
        }
    }

    inner class Deployment {
        fun rawList(ns: String) = extensionApi.listNamespacedDeployment(ns, "true",
                null, null, null, null, null, null, 0, false)
                .items

        fun list(ns: String): List<Any> {
            return rawList(ns)
                    .map {
                        var map = HashMap<String, Any?>()
                        map["name"] = it.metadata?.name
                        map["ns"] = it.metadata?.namespace
                        map["labels"] = it.metadata?.labels
                        map["uid"] = it.metadata?.uid
                        map
                    }
        }

        fun read(ns: String, name: String): ExtensionsV1beta1Deployment {
            val a = extensionApi.readNamespacedDeployment(name, ns, "true", null, null)
            a.metadata?.creationTimestamp = null
            a.status?.conditions?.forEach {
                it.lastTransitionTime = null
                it.lastUpdateTime = null
            }
            return a
        }

        fun update(deployment: ExtensionsV1beta1Deployment) {
            val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(kubeConfigPath)))
                    .setOverridePatchFormat(V1Patch.PATCH_FORMAT_JSON_MERGE_PATCH).build()
            client.setDebugging(true)
            ExtensionsV1beta1Api(client)
                    .replaceNamespacedDeploymentWithHttpInfo(deployment.metadata?.name, deployment.metadata?.namespace, deployment,
                            "true", null, null)
        }

        fun delete(ns: String, name: String) {
            extensionApi
                    .deleteNamespacedDeploymentWithHttpInfo(name, ns, "true", null, null, null, null, null)
        }

        fun create(ns: String, deployment: ExtensionsV1beta1Deployment) {
            extensionApi.createNamespacedDeploymentWithHttpInfo(ns, deployment, "true", null, null)
        }
    }

}

