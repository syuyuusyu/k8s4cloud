package bzh.cloud.k8s.config

import bzh.cloud.k8s.utils.SpringUtil
import io.kubernetes.client.ProtoClient
import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.apis.ExtensionsV1beta1Api
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import okhttp3.OkHttpClient
import org.openapitools.client.api.DefaultApi
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.support.BeanDefinitionDsl
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.server.WebSocketService
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.io.FileReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


@ConfigurationProperties(prefix = "self")
class KubeProperties (
        val kubeConfigPath:String,
        val ignorePath:List<String>,
        val needProxyUrl:List<String>,
        val jwtkey:String,
        val allowMethods:String,
        val allowHeads:String,
        val allowOrigin:String,
        val httpProxy:String,
        val registryUrl:String,
        val officalRegistryUrl:String,
        val tempFileDir:String,
        val enableProxy:Boolean
)



fun beans() = org.springframework.context.support.beans {


    bean<ApiClient>("apiClient") {
        val kubeConfigPath = ref<KubeProperties>().kubeConfigPath
        val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(kubeConfigPath))).build()
        Configuration.setDefaultApiClient(client)
        client.setDebugging(true)
        client
    }

    bean<ExtensionsV1beta1Api>("extensionApi") {
        val client = ref<ApiClient>()
        ExtensionsV1beta1Api(client)
    }

    bean<CoreV1Api>(name = "kubeApi") {
        val client = ref<ApiClient>()
        CoreV1Api(client)
    }

    bean<CoreV1Api>(name = "prototypeKubeApi", scope = BeanDefinitionDsl.Scope.PROTOTYPE) {
        val kubeConfigPath = ref<KubeProperties>().kubeConfigPath
        val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(kubeConfigPath))).build()
        Configuration.setDefaultApiClient(client)
        //client.setDebugging(true)
        println("new CoreV1Api")
        CoreV1Api(client)
    }



    bean<ProtoClient>("protoClient") {
        val client = ref<ApiClient>()
        ProtoClient(client)
    }

    bean<Executor>("threadPool"){
        Executors.newFixedThreadPool(100) { r ->
            val t = Thread(r)
            t.isDaemon = true
            t
        }
    }

    //------ websocket -------
    bean<WebSocketService>("webSocketService") {
        HandshakeWebSocketService(ReactorNettyRequestUpgradeStrategy())
    }
    bean<WebSocketHandlerAdapter>() {
        WebSocketHandlerAdapter(ref<WebSocketService>())
    }
    bean<HandlerMapping>("webSocketHandlerMapping") {
        val podTerminalWs = ref<WebSocketHandler>("podTerminalWs")
        val map = HashMap<String, WebSocketHandler>()
        map["/pod-terminal"] = podTerminalWs

        val handlerMapping = SimpleUrlHandlerMapping()
        handlerMapping.order = 1
        handlerMapping.urlMap = map
        handlerMapping
    }

    bean<Proxy>("httpProxy"){
        val httpProxy = ref<KubeProperties>().httpProxy
        val port = Regex(":(\\d+)$").find(httpProxy)!!.groupValues[1].toInt()
        val host= httpProxy.replace(":"+port,"")
        Proxy(Proxy.Type.HTTP,InetSocketAddress(host, port))
    }



    bean<DefaultApi>("dockerHubApi"){
        val proxy = ref<Proxy>()
        DefaultApi().apply {
            apiClient = org.openapitools.client.ApiClient().apply {
                basePath = ref<KubeProperties>().officalRegistryUrl
                val enableProxy = ref<KubeProperties>().enableProxy
                if(enableProxy){
                    httpClient = OkHttpClient.Builder().proxy(proxy).build()
                }
            }
        }
    }

    bean<DefaultApi>("localRegistryApi"){
        DefaultApi().apply {
            apiClient = org.openapitools.client.ApiClient().apply {
                basePath = ref<KubeProperties>().registryUrl
            }
        }
    }




}

fun newCoreV1Api(): CoreV1Api {
    //val path = ""
    val path = (SpringUtil.getBean("self-bzh.cloud.k8s.config.KubeProperties") as KubeProperties).kubeConfigPath
    val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(path))).build()
    //Configuration.setDefaultApiClient(client)
    client.setDebugging(true)
    println("new CoreV1Api")
    return CoreV1Api(client)
}

fun UpdateClient(): CoreV1Api {
    //val path =""
    val path = (SpringUtil.getBean("self-bzh.cloud.k8s.config.KubeProperties") as KubeProperties).kubeConfigPath
    val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(path)))
            .setOverridePatchFormat(V1Patch.PATCH_FORMAT_JSON_MERGE_PATCH).build()
    client.setDebugging(true)
    return CoreV1Api(client)
}

fun watchClient() :Pair<ApiClient,CoreV1Api>{
    val path = (SpringUtil.getBean("self-bzh.cloud.k8s.config.KubeProperties") as KubeProperties).kubeConfigPath
    val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(path))).build()
    val httpClient = client.getHttpClient().newBuilder().readTimeout(0, TimeUnit.SECONDS).build();
    client.setHttpClient(httpClient);
    //client.setDebugging(true)
    return Pair(client,CoreV1Api(client))
}

data class CmContext(
        val name:String,
        val ns:String,
        val key:String,
        val context:String
)

class OperateResult(){
    var success:Boolean=false
    var msg:String=""
}


