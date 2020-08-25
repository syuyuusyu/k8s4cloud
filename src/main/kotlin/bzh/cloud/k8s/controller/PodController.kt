package bzh.cloud.k8s.controller



import bzh.cloud.k8s.config.ClientUtil
import bzh.cloud.k8s.expansion.CurlEvent
import bzh.cloud.k8s.expansion.curl
import bzh.cloud.k8s.service.WatchService
import io.kubernetes.client.PodLogs
import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1NamespaceBuilder
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import kotlinx.coroutines.*
import okhttp3.Response
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.util.StringUtils
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux
import reactor.core.publisher.toFlux
import java.io.FileReader
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.stream.Collectors


@RestController
@RequestMapping("/kube")
class PodController(

        val watchService: WatchService
) : CoroutineScope by CoroutineScope(Dispatchers.Default) {

    @Value("\${self.kubeConfigPath}")
    lateinit var kubeConfigPath: String




    companion object {
        private val log: Logger = LoggerFactory.getLogger(PodController::class.java)
    }

    private fun createNsifNotExist(ns: String) {
        val kubeApi: CoreV1Api = ClientUtil.kubeApi()
        if (ns == "null") return
        val list = kubeApi.listNamespace("false", false, null, null, null, null, null, null, null)
        val obj = list.items.find { it.metadata?.name == ns }
        if (obj == null) {
            val nsobj = V1NamespaceBuilder().withNewMetadata().withName(ns).endMetadata().build()
            kubeApi.createNamespace(nsobj, "false", null, null)
        }
    }


    @GetMapping("/namespace/{ns}/Pod")
    fun list(@PathVariable ns: String): Flux<V1Pod> {
        val kubeApi: CoreV1Api = ClientUtil.kubeApi()
        //val list = kubeApi.listPodForAllNamespaces(null, null, null, null, null, "true", null, null,null)
        this.createNsifNotExist(ns)
        var labelSelector = ""
        val list1 = kubeApi.listNamespacedPod(ns, "true", null, null, null,
                null, null, null, 0, false).items
        //val list = podService.pods(arrayOf(ns).asList())
        return Flux.fromIterable(list1)
    }

    @GetMapping("/watch/namespace/{ns}/Pod", produces = arrayOf(MediaType.TEXT_EVENT_STREAM_VALUE))
    fun watchList(@PathVariable ns: String): Flux<V1Pod> {

        return Flux.create<V1Pod> { watchService.addPodSink(ns, it) }.doFinally {
            log.info("doFinally watch pod ns:{}", ns)
        }
    }

    //stable log
    //@GetMapping(path = ["/watch/log/{ns}/{pname}/{cname}"], produces = arrayOf(MediaType.TEXT_EVENT_STREAM_VALUE))
    fun log(@PathVariable ns: String, @PathVariable pname: String, @PathVariable cname: String): Flux<String> {
        val watchClient = ClientUtil.watchClient()
        return Flux.interval(Duration.ofSeconds(1))
                .map {
                    val response = curl {
                        client { watchClient.httpClient }
                        request {
                            url("${watchClient.basePath}/api/v1/namespaces/${ns}/pods/${pname}/log")
                            params {
                                "previous" to false
                                "limitBytes" to null
                                "sinceSeconds" to 1
                                "tailLines" to 10
                                if (cname != "undefined") {
                                    "container" to cname
                                }
                            }
                        }
                    } as Response
                    var str = response.body()?.string() ?: ""
                    val list = str.split("\n")
                    var a = list.joinToString("<br/>")
                    a
                }


    }

    @GetMapping(path = ["/watch/log/{ns}/{pname}/{cname}"], produces = arrayOf(MediaType.TEXT_EVENT_STREAM_VALUE))
    fun logcurl(@PathVariable ns: String, @PathVariable pname: String, @PathVariable cname: String): Flux<String> {
        val watchClient = ClientUtil.watchClient()
        return Flux.create { sink ->
            sink.next("");
            //sink.next(firstlog)
            var eve: CurlEvent? = null
            eve = curl {
                client { watchClient.httpClient }
                request {
                    url("${watchClient.basePath}/api/v1/namespaces/${ns}/pods/${pname}/log")
                    params {
                        "follow" to true
                        "previous" to false
                        "limitBytes" to null
                        "sinceSeconds" to 2
                        "tailLines" to 10
                        if (cname != "undefined") {
                            "container" to cname
                        }
                    }
                }
                event {
                    readStream { byte ->
                        if (sink.isCancelled) {
                            eve?.close()
                        }
                        //log.info("{}",String(byte))
                        val logstr = String(byte, StandardCharsets.UTF_8).replace(0.toChar().toString(), "")
                        log.info("{}", logstr.length)
                        //println("-1"+logstr+"2-")
                        sink.next(logstr)
                    }
                }
            } as CurlEvent

        }
    }

    @GetMapping("/namespace/{ns}/Pod/{podName}")
    fun podJson(@PathVariable ns: String, @PathVariable podName: String): V1Pod {
        val pod = ClientUtil.kubeApi().readNamespacedPod(podName, ns, "true", false, false)
        return pod;
    }

    @DeleteMapping("/namespace/{ns}/Pod/{podName}")
    fun delete(@PathVariable ns: String, @PathVariable podName: String): Map<String, Any> {
        val result = HashMap<String, Any>()
        try {
            //kubeApi.deleteNamespacedPodWithHttpInfo(podName, ns, "false", null, null, null, null, null)
            ClientUtil.kubeApi().deleteNamespacedPod(podName, ns, "false", null, null, null, null, null)
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

    @PutMapping("Pod")
    fun update(@RequestBody pod: V1Pod): Map<String, Any> {
        val result = HashMap<String, Any>()
        try {
            CoreV1Api(ClientUtil.updateClient()).replaceNamespacedPodWithHttpInfo(pod.metadata?.name, pod.metadata?.namespace, pod,
                    "true", null, null)
            result["success"] = true
            result["msg"] = "更新成功"
        } catch (e: ApiException) {
            e.printStackTrace()
            KubeController.errorMsg(e, result)
        }
        return result;
    }

    @PostMapping("/namespace/{ns}/Pod")
    fun createPod(@PathVariable ns: String, @RequestBody body: V1Pod): Map<String, Any> {
        val result = HashMap<String, Any>()
        try {
            val apiResponse = ClientUtil.kubeApi().createNamespacedPodWithHttpInfo(ns, body, "true", null, null)
            result["success"] = true
            result["msg"] = "更新成功"
        } catch (e: ApiException) {
            e.printStackTrace()
            KubeController.errorMsg(e, result)
        }
        return result;
    }
}