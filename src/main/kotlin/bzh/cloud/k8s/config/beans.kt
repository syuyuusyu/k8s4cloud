package bzh.cloud.k8s.config

import ind.syu.kubeIsp.utils.SpringUtil
import io.kubernetes.client.ProtoClient
import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.apis.ExtensionsV1beta1Api
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
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
import java.util.HashMap



@ConfigurationProperties(prefix = "self")
class KubeProperties (
        val kubeConfigPath:String,
        val ignorePath:List<String>,
        val jwtkey:String,
        val allowMethods:String,
        val allowHeads:String,
        val allowOrigin:String
)



fun beans() = org.springframework.context.support.beans {


    bean<ApiClient>("apiClient") {
        val kubeConfigPath = ref<KubeProperties>().kubeConfigPath
        val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(kubeConfigPath))).build()
        Configuration.setDefaultApiClient(client)
        client.setDebugging(true)
        client
    }
    bean<Scheduler>("jpaScheduler") {
        Schedulers.newParallel("jpa", 10)
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
        client.setDebugging(true)
        println("new CoreV1Api")
        CoreV1Api(client)
    }



    bean<ProtoClient>("protoClient") {
        val client = ref<ApiClient>()
        ProtoClient(client)
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



}

fun newCoreV1Api(): CoreV1Api {
    val path = ""
    //val path = (SpringUtil.getBean("bzh.cloud.k8s.config.KubeProperties") as KubeProperties).kubeConfigPath
    val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(path))).build()
    Configuration.setDefaultApiClient(client)
    client.setDebugging(true)
    println("new CoreV1Api")
    return CoreV1Api(client)
}

fun UpdateClient(): CoreV1Api {
    val path =""
    //val path = (SpringUtil.getBean("bzh.cloud.k8s.config.KubeProperties") as KubeProperties).kubeConfigPath
    val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(path)))
            .setOverridePatchFormat(V1Patch.PATCH_FORMAT_JSON_MERGE_PATCH).build()
    client.setDebugging(true)
    return CoreV1Api(client)
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