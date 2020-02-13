package bzh.cloud.k8s.controller



import bzh.cloud.k8s.service.PodService
import io.kubernetes.client.*


import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import java.time.Duration

import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import org.springframework.beans.factory.annotation.Value
import java.io.FileReader

import io.kubernetes.client.custom.V1Patch

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1NamespaceBuilder
import io.kubernetes.client.openapi.models.V1Pod

import kotlin.collections.HashMap
import org.springframework.util.StringUtils



@RestController
@RequestMapping("/kube/Pod")
class PodController(
        val kubeApi: CoreV1Api,
        val podService: PodService,
        val apiClient: ApiClient
) {

    @Value("\${self.kubeConfigPath}")
    lateinit var kubeConfigPath: String

    companion object {
        private val log: Logger = LoggerFactory.getLogger(PodController::class.java)
    }

    private fun createNsifNotExist(ns:String){
        if(ns=="null") return
        val list = kubeApi.listNamespace("false",false,null,null,null,null,null,null,null)
        val obj = list.items.find { it.metadata?.name==ns }
        if(obj == null){
            val nsobj=V1NamespaceBuilder().withNewMetadata().withName(ns).endMetadata().build()
            kubeApi.createNamespace(nsobj,"false",null,null)
        }
    }

    @GetMapping("/{ns}")
    fun list(@PathVariable ns: String): Flux<Map<String, Any?>> {
        //val list = kubeApi.listPodForAllNamespaces(null, null, null, null, null, "true", null, null,null)
        this.createNsifNotExist(ns)
        var labelSelector = ""
        val list = podService.pods(arrayOf(ns).asList())
        return Flux.fromIterable(list)
    }


//    @GetMapping("/{ns}",produces = arrayOf(MediaType.TEXT_EVENT_STREAM_VALUE))
//    fun list(@PathVariable ns: String): Flux<Map<String, Any?>> {
//        val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(kubeConfigPath))).build()
//        val httpClient = client.httpClient.newBuilder().readTimeout(10, TimeUnit.SECONDS).build()
//        client.httpClient = httpClient
//        Configuration.setDefaultApiClient(client)
//
//        val api = CoreV1Api()
//
//        val watch = Watch.createWatch<V1Pod>(
//                client,
//                api.listNamespacedPodCall(ns, null, null, null, null, null, 5, null, null, java.lang.Boolean.TRUE, null),
//                object : TypeToken<Watch.Response<V1Pod>>() {
//
//                }.getType())
//        return Flux.create<Map<String, Any?>> { sink->
//            watch.forEach {
//                log.info("{}",it.`object`.metadata?.name )
//                var obj = it.`object`
//                val map = HashMap<String,Any?>()
//                map["name"] = obj.metadata?.name
//                map["ns"] = obj.metadata?.namespace
//                map["labels"] = obj.metadata?.labels
//                map["status"] = obj.status?.phase
//                obj.status?.containerStatuses?.forEach { state->
//                    state.state?.running
//                }
//                map["uid"] = obj.metadata?.uid
//                map["containers"] = obj.spec?.containers?.map { con->
//                    val c= HashMap<String,Any?>()
//                    c["name"] = con.name
//                    c["image"]= con.image
//                    c
//                }
//                sink.next(map)
//            }
//        }.doFinally {
//            watch.close()
//        }
//    }

    @GetMapping(path = ["/log/{ns}/{pname}/{cname}"], produces = arrayOf(MediaType.TEXT_EVENT_STREAM_VALUE))
    fun log(@PathVariable ns: String, @PathVariable pname: String, @PathVariable cname: String): Flux<String> {

        val firstlog = kubeApi.readNamespacedPodLog(pname, ns, if (cname == "undefined") null else cname, false,
                Int.MAX_VALUE, null, false, Int.MAX_VALUE, 100, false)

        return Flux.interval(Duration.ofSeconds(2))
                .map {
                    val clog = kubeApi.readNamespacedPodLog(pname, ns, if (cname == "undefined") null else cname, false,
                            null, null, false, 2, 10, false)
                    if (it == 0L) firstlog else if (StringUtils.isEmpty(clog)) "" else clog
                }.doFinally {
                    log.debug("log stream close!!")
                }
    }

//    @GetMapping(path = ["/log/{ns}/{pname}/{cname}"], produces = arrayOf(MediaType.TEXT_EVENT_STREAM_VALUE))
//    fun log2(@PathVariable ns: String, @PathVariable pname: String, @PathVariable cname: String): Flux<String> {
//        val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(kubeConfigPath))).build()
//        Configuration.setDefaultApiClient(client)
//        //val coreApi = CoreV1Api(apiClient)
//        val logs = PodLogs()
//        //val input = logs.streamNamespacedPodLog(ns,pname,if (cname == "undefined") null else cname);
//        val firstlog = kubeApi.readNamespacedPodLog(pname, ns, if (cname == "undefined") null else cname, false,
//                Int.MAX_VALUE, null, false, Int.MAX_VALUE, 999, false)
//        val time = Date().getTime() / 1000
//        log.debug("time {}",time)
//        val input = logs.streamNamespacedPodLog(ns,pname,if (cname == "undefined") null else cname,2,10,false)
//        var flag = true
//        var heartbeat = false
//        return Flux.interval(Duration.ofSeconds(2))
//                .map {
//                    if( it == 1L) flag = false
//                    if( it%20 == 0L) heartbeat=true else heartbeat=false
//                    val count = input.available()
//                    log.debug("count:{}",count)
//                    count
//                }.filter { flag || heartbeat || it>0 }
//                .map {
//                    if(flag){
//                        return@map firstlog
//                    }
//                    if( heartbeat && it==0 ){
//                        return@map ""
//                    }
//                    val byteArray = ByteArray(it)
//                    input.read(byteArray)
//                    log.debug("read log:{}",String(byteArray))
//                    String(byteArray)
//                }.doFinally {
//                    log.debug("close log stream")
//                    input.close()
//                }
//    }

        @GetMapping("/{ns}/{podName}")
        fun podJson(@PathVariable ns: String, @PathVariable podName: String): V1Pod {
            val pod = kubeApi.readNamespacedPod(podName, ns, "true", false, false)
            pod.metadata?.creationTimestamp = null
            pod.status = null
            return pod;

        }

        @DeleteMapping("/{ns}/{podName}")
        fun delete(@PathVariable ns: String, @PathVariable podName: String): Map<String, Any> {
            val result = HashMap<String, Any>()
            try {
                //kubeApi.deleteNamespacedPodWithHttpInfo(podName, ns, "false", null, null, null, null, null)
                kubeApi.deleteNamespacedPod(podName, ns, "false", null, null, null, null, null)
                result["success"] = true
                result["msg"] = "删除成功"
            } catch (e: ApiException) {
                e.printStackTrace()
                KubeController.errorMsg(e, result)
            } catch (ex: Exception) {
                ex.printStackTrace()
                result["success"] = false
            }
            return result;
        }

        @PutMapping()
        fun update(@RequestBody pod: V1Pod): Map<String, Any> {
            val result = HashMap<String, Any>()
            try {
                val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(kubeConfigPath)))
                        .setOverridePatchFormat(V1Patch.PATCH_FORMAT_JSON_MERGE_PATCH).build()
                client.setDebugging(true)
                CoreV1Api(client).replaceNamespacedPodWithHttpInfo(pod.metadata?.name, pod.metadata?.namespace, pod,
                        "true", null, null)
                result["success"] = true
                result["msg"] = "更新成功"
            } catch (e: ApiException) {
                e.printStackTrace()
                KubeController.errorMsg(e, result)
            }
            return result;
        }

        @PostMapping("/{ns}")
        fun createPod(@PathVariable ns: String, @RequestBody body: V1Pod): Map<String, Any> {
            val result = HashMap<String, Any>()
            try {
                val apiResponse = kubeApi.createNamespacedPodWithHttpInfo(ns, body, "true", null, null)
                result["success"] = true
                result["msg"] = "更新成功"
            } catch (e: ApiException) {
                e.printStackTrace()
                KubeController.errorMsg(e, result)
            }
            return result;
        }
    }