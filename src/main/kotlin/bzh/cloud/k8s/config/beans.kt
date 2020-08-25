package bzh.cloud.k8s.config

import bzh.cloud.k8s.utils.SpringUtil
import io.kubernetes.client.ProtoClient
import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.apis.ExtensionsV1beta1Api
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.newSingleThreadContext
import okhttp3.OkHttpClient
import org.openapitools.client.api.DefaultApi
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.support.BeanDefinitionDsl
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.server.WebSocketService
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy
import java.io.FileReader
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


@ConfigurationProperties(prefix = "self")
class KubeProperties(
        val kubeConfigPath: String,
        val ignorePath: List<String>,
        val needProxyUrl: List<String>,
        val jwtkey: String,
        val allowMethods: String,
        val allowHeads: String,
        val allowOrigin: String,
        val httpProxy: String,
        val registryUrl: String,
        val officalRegistryUrl: String,
        val tempFileDir: String,
        val enableProxy: Boolean
)


//class UpdateClient(client: ApiClient) : ApiClient() {}
//class WatchClient (client: ApiClient): ApiClient() {}
//@Component
class ClientUtil {
    companion object {
        private val path = (SpringUtil.getBean("self-bzh.cloud.k8s.config.KubeProperties") as KubeProperties).kubeConfigPath
        //private val path ="/Users/syu/.kube/config-sup"
        private var updateClient: ApiClient? = null
            get() {
                if (field == null) {
                    val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(path)))
                            .setOverridePatchFormat(V1Patch.PATCH_FORMAT_JSON_MERGE_PATCH).build()
                    client.setDebugging(true)
                    println("updateClient")
                    println(client)
                    field = client
                }
                return field
            }

        private var watchClient: ApiClient? = null
            get() {
                if (field == null) {

                    val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(path))).build()
                    val httpClient = client.getHttpClient().newBuilder().readTimeout(0, TimeUnit.SECONDS).build()
                    client.setHttpClient(httpClient)
                    println("watchClient")
                    println(client)
                    field = client
                }
                return field
            }

        private var apiClient: ApiClient? = null
            get() {
                if (field == null) {
                    val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(path))).build()
                    println("apiClient")
                    println(client)
                    field = client
                }
                return field
            }

        fun watchClient():ApiClient = watchClient!!
        fun updateClient():ApiClient = updateClient!!
        fun apiClient():ApiClient = apiClient!!
        fun kubeApi() = CoreV1Api(apiClient!!)
    }

}


fun beans() = org.springframework.context.support.beans {


    bean<Executor>("threadPool") {
        Executors.newFixedThreadPool(50) { r ->
            val t = Thread(r)
            t.isDaemon = true
            t
        }
    }

    bean<ExecutorCoroutineDispatcher>("atomicThread") {
        newSingleThreadContext("atomicThread")
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

    bean<Proxy>("httpProxy") {
        val httpProxy = ref<KubeProperties>().httpProxy
        val port = Regex(":(\\d+)$").find(httpProxy)!!.groupValues[1].toInt()
        val host = httpProxy.replace(":" + port, "")
        Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    }



    bean<DefaultApi>("dockerHubApi") {
        val proxy = ref<Proxy>()
        DefaultApi().apply {
            apiClient = org.openapitools.client.ApiClient().apply {
                basePath = ref<KubeProperties>().officalRegistryUrl
                val enableProxy = ref<KubeProperties>().enableProxy
                if (enableProxy) {
                    httpClient = OkHttpClient.Builder().proxy(proxy).build()
                }
            }
        }
    }

    bean<DefaultApi>("localRegistryApi") {
        DefaultApi().apply {
            apiClient = org.openapitools.client.ApiClient().apply {
                basePath = ref<KubeProperties>().registryUrl
                this.setDebugging(true)
            }
        }
    }


    bean<RouterFunction<ServerResponse>>() {
        RouterFunctions.resources("/k8s16api.json", ClassPathResource("static/k8s1.16.openapi.json"))
    }

    bean<RouterFunction<ServerResponse>>() {
        RouterFunctions.resources("/k8s17api.json", ClassPathResource("static/k8sv1.17.0.openapi.json"))
    }


}

//fun UpdateClient(): CoreV1Api {
//    //val path =""
//    val path = (SpringUtil.getBean("self-bzh.cloud.k8s.config.KubeProperties") as KubeProperties).kubeConfigPath
//    val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(path)))
//            .setOverridePatchFormat(V1Patch.PATCH_FORMAT_JSON_MERGE_PATCH).build()
//    client.setDebugging(true)
//    return CoreV1Api(client)
//}
//
//fun watchClient() :Pair<ApiClient,CoreV1Api>{
//    val path = (SpringUtil.getBean("self-bzh.cloud.k8s.config.KubeProperties") as KubeProperties).kubeConfigPath
//    val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(path))).build()
//    val httpClient = client.getHttpClient().newBuilder().readTimeout(0, TimeUnit.SECONDS).build();
//    client.setHttpClient(httpClient);
//    //client.setDebugging(true)
//    return Pair(client,CoreV1Api(client))
//}

data class CmContext(
        val name: String,
        val ns: String,
        val key: String,
        val context: String
)

class OperateResult() {
    var success: Boolean = false
    var msg: String = ""
}


