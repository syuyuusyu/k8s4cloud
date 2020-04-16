package bzh.cloud.k8s.service

import bzh.cloud.k8s.controller.PodController
import com.google.gson.reflect.TypeToken
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodBuilder
import io.kubernetes.client.openapi.models.V1ResourceQuota
import io.kubernetes.client.openapi.models.V1ResourceQuotaBuilder
import io.kubernetes.client.util.Watch
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.FluxSink
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.HashSet


@Service
class WatchService(
        val threadPool: Executor
        //val atomicThread : ExecutorCoroutineDispatcher
) : CoroutineScope by CoroutineScope(Dispatchers.Default) {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(WatchService::class.java)
    }
    val heartbeatThread = newSingleThreadContext("heartbeatThread")

    //-----------pod-------------------
    private var podWatchRuning = AtomicBoolean().apply { set(false) }
    private val podDispatcherSink = Collections.synchronizedSet(HashSet<Pair<String, FluxSink<V1Pod>>>())
    private val cachePod = HashSet<V1Pod>()

    fun heartbeat() = launch(heartbeatThread) {
        val pod = V1PodBuilder().withNewMetadata().withName("heart beat").endMetadata().build()
        val quota = V1ResourceQuotaBuilder().withNewMetadata().withName("heart beat").endMetadata().build()
        while (isActive) {
            log.info("watch heartbeat,podDispatcherSink.size:{},quotaDispatcherSink.size:{}", podDispatcherSink.size,quotaDispatcherSink.size)
            delay(20 * 1000)

            //---pod
            val removepod = HashSet<Pair<String, FluxSink<V1Pod>>>()
            for ((ns, sink) in podDispatcherSink) {
                if (sink.isCancelled) {
                    removepod.add(Pair(ns, sink))
                } else {
                    sink.next(pod)
                }
            }
            podDispatcherSink.removeAll(removepod)
            if (podDispatcherSink.size == 0) {
                closepodWatch()
            }

            //--quota
            val removequota = HashSet<FluxSink<V1ResourceQuota>>()
            for(sink in quotaDispatcherSink){
                if(sink.isCancelled){
                    removequota.add(sink)
                }else{
                    sink.next(quota)
                }
            }
            quotaDispatcherSink.removeAll(removequota)
            if(quotaDispatcherSink.size==0){
                closequotaWatch()
            }

        }
    }

    fun addPodSink(ns: String, sink: FluxSink<V1Pod>) {
        if (!podWatchRuning.get()) {
            initpodWatch()
        }
        cachePod.filter { it.metadata?.namespace == ns }.forEach { sink.next(it) }
        podDispatcherSink.add(Pair(ns, sink))

    }

    var podWatch: Watch<V1Pod>? = null

    fun initpodWatch() {
        val (client, api) = bzh.cloud.k8s.config.watchClient()
        podWatch = Watch.createWatch<V1Pod>(
                client,
                api.listPodForAllNamespacesCall(null, null, null, null, null, null,
                        null, null, java.lang.Boolean.TRUE, null),
                object : TypeToken<Watch.Response<V1Pod>>() {}.type)
        podWatchRuning.set(true)

        doPodWatch()
    }

    fun closepodWatch() {
        log.info("close podWatch")
        podWatchRuning.set(false)
        podWatch?.close()
        cachePod.clear()
    }

    fun doPodWatch() {
        launch(threadPool.asCoroutineDispatcher()) {
            try {
                log.info("start watch pod")
                podWatch?.forEach {
                    log.info("watch pod:{}", it.`object`.metadata?.name)
                    addTocachePod(it.`object`)
                    podDispatcherSink.forEach { (ns, sink) ->
                        if (it.`object`.metadata?.namespace == ns) {
                            sink.next(it.`object`)
                        }
                    }
                }
            } catch (e: Exception) {
                log.info("watch pod RuntimeException")
                //e.printStackTrace()
            } finally {
                log.info("watch pod close")
            }
        }
    }

    fun addTocachePod(pod: V1Pod) {
        if (pod.metadata?.deletionTimestamp != null) {
            val deleted = cachePod.find { it.metadata?.uid == pod.metadata?.uid }
            deleted?.let { cachePod.remove(it) }
            return
        }
        val deleted = cachePod.find { it.metadata?.uid == pod.metadata?.uid }
        deleted?.let { cachePod.remove(it) }
        cachePod.add(pod)
    }

    //-------------quota-----------------

    private var quotaWatchRuning = AtomicBoolean().apply { set(false) }
    private val quotaDispatcherSink = Collections.synchronizedSet(HashSet<FluxSink<V1ResourceQuota>>())
    private val cacheQuota = HashSet<V1ResourceQuota>()


    fun addQuotaSink(sink: FluxSink<V1ResourceQuota>) {
        if (!quotaWatchRuning.get()) {
            initquotaWatch()
        }
        cacheQuota.forEach { sink.next(it) }
        quotaDispatcherSink.add(sink)

    }

    var quotaWatch: Watch<V1ResourceQuota>? = null

    fun initquotaWatch() {
        val (client, api) = bzh.cloud.k8s.config.watchClient()

        quotaWatch = Watch.createWatch<V1ResourceQuota>(
                client,
                api.listResourceQuotaForAllNamespacesCall(null, null, "",
                        "", null, null, null, 0, true, null),
                object : TypeToken<Watch.Response<V1ResourceQuota>>() {}.type)
        quotaWatchRuning.set(true)

        doQuotaWatch()
    }

    fun closequotaWatch() {
        log.info("close quotaWatch")
        quotaWatchRuning.set(false)
        quotaWatch?.close()
        cacheQuota.clear()
    }

    fun doQuotaWatch() {
        launch(threadPool.asCoroutineDispatcher()) {
            try {
                log.info("start watch quota")
                quotaWatch?.forEach {
                    log.info("watch quota:{}", it.`object`.metadata?.name)
                    addTocacheQuota(it.`object`)
                    quotaDispatcherSink.forEach { sink ->
                        sink.next(it.`object`)
                    }
                }
            } catch (e: Exception) {
                log.info("watch quota RuntimeException")
                //e.printStackTrace()
            } finally {
                log.info("watch quota close")
            }
        }
    }

    fun addTocacheQuota(quota: V1ResourceQuota) {
        if (quota.metadata?.deletionTimestamp != null) {
            val deleted = cacheQuota.find { it.metadata?.uid == quota.metadata?.uid }
            deleted?.let { cacheQuota.remove(it) }
            return
        }
        val deleted = cacheQuota.find { it.metadata?.uid == quota.metadata?.uid }
        deleted?.let { cacheQuota.remove(it) }
        cacheQuota.add(quota)
    }

}