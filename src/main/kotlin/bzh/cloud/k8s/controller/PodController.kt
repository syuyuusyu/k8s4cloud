package bzh.cloud.k8s.controller


import bzh.cloud.k8s.config.watchClient

import bzh.cloud.k8s.service.PodService

import com.google.gson.reflect.TypeToken

import io.kubernetes.client.custom.V1Patch

import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1NamespaceBuilder
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodBuilder
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import io.kubernetes.client.util.Watch
import kotlinx.coroutines.*
import org.apache.commons.lang.RandomStringUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.util.StringUtils
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import java.io.FileReader
import java.time.Duration
import java.util.concurrent.Executor


@RestController
@RequestMapping("/kube")
class PodController(
        val kubeApi: CoreV1Api,
        val podService: PodService,
        val apiClient: ApiClient,
        val threadPool: Executor
) : CoroutineScope by CoroutineScope(Dispatchers.Default) {

    @Value("\${self.kubeConfigPath}")
    lateinit var kubeConfigPath: String

    companion object {
        private val log: Logger = LoggerFactory.getLogger(PodController::class.java)
    }

    private fun createNsifNotExist(ns: String) {
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
        val (client, api) = watchClient()
        val session = RandomStringUtils.randomAlphanumeric(8)
        val watch = Watch.createWatch<V1Pod>(
                client,
                api.listNamespacedPodCall(ns, null, null, null, null, null, 5, null, null, java.lang.Boolean.TRUE, null),
                object : TypeToken<Watch.Response<V1Pod>>() {}.type)

        var job:Job?=null
        return Flux.create<V1Pod> { sink ->

            val p = V1PodBuilder().withNewMetadata().withName("heart beat").endMetadata().build()
            sink.next(p)

            job=launch {
                try {
                    watch.forEach {
                        log.info("watch pod:{}", it.`object`.metadata?.name)
                        sink.next(it.`object`)
                    }
                } catch (e: RuntimeException) {
                    log.info("watch pod RuntimeException session:{},ns:{}", session, ns)
                    e.printStackTrace()
                }

            }
            launch {
                repeat(1000) {
                    if (sink.isCancelled) {
                        sink.complete()
                        this.cancel()
                    }
                    delay(20 * 1000)
                    log.info("heart beat,{},ns:{}",sink.isCancelled,ns)
                    sink.next(p)
                }
            }
        }.doFinally{
            log.info("doFinally watch pod ns:{}",ns)
            launch {
                job?.cancelAndJoin()
                watch.close()
            }
        }
    }

    @GetMapping(path = ["/watch/log/{ns}/{pname}/{cname}"], produces = arrayOf(MediaType.TEXT_EVENT_STREAM_VALUE))
    fun log(@PathVariable ns: String, @PathVariable pname: String, @PathVariable cname: String): Flux<String> {

        val firstlog = kubeApi.readNamespacedPodLog(pname, ns, if (cname == "undefined") null else cname, false,
                Int.MAX_VALUE, null, false, Int.MAX_VALUE, 100, false)

        return Flux.interval(Duration.ofSeconds(2))
                .map {
                    log.info("/watch/log repeat")
                    val clog = kubeApi.readNamespacedPodLog(pname, ns, if (cname == "undefined") null else cname, false,
                            null, null, false, 2, 10, false)
                    if (it == 0L) firstlog else if (StringUtils.isEmpty(clog)) "" else clog
                }.doFinally {
                    log.info("log stream close!!")
                }
    }

    //@GetMapping(path = ["/watch/log/{ns}/{pname}/{cname}"], produces = arrayOf(MediaType.TEXT_EVENT_STREAM_VALUE))
    fun log2(@PathVariable ns: String, @PathVariable pname: String, @PathVariable cname: String): Flux<String> {
        val firstlog = kubeApi.readNamespacedPodLog(pname, ns, if (cname == "undefined") null else cname, false,
                Int.MAX_VALUE, null, false, Int.MAX_VALUE, 100, false)

        val (_, api) = watchClient()

        val call1 = api.readNamespacedPodLogCall(pname, ns, if (cname == "undefined") null else cname,
                true, null, null, false, 2, 10, false, null)


        val response = call1.execute()
        val input = response.body()?.byteStream()!!
        //var gono = true


        var job:Job? = null
        return Flux.create<String> { sink ->
            sink.next(if (StringUtils.isEmpty(firstlog)) "" else firstlog)
            job = launch {
                while (isActive) {

                    launch {
                        try {
                            log.info("watch log repeat")
                            delay(1000)
                            val count = input.available()
                            log.info("watch log {}", count)
                            val byteArray = ByteArray(count)
                            input.read(byteArray)
                            sink.next(String(byteArray))
                        } catch (e: Exception) {
                            log.info("watch log Exception")
                            e.printStackTrace()
                        }
                    }

                }
            }

        }.doFinally {
            log.info("/watch/log complete")
            launch {
                job?.cancelAndJoin()
                input.close()
                response.close()
            }
        }


    }

    @GetMapping("/namespace/{ns}/Pod/{podName}")
    fun podJson(@PathVariable ns: String, @PathVariable podName: String): V1Pod {
        val pod = kubeApi.readNamespacedPod(podName, ns, "true", false, false)
        pod.metadata?.creationTimestamp = null
        pod.status = null
        return pod;

    }

    @DeleteMapping("/namespace/{ns}/Pod/{podName}")
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

    @PutMapping("Pod")
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

    @PostMapping("/namespace/{ns}/Pod")
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