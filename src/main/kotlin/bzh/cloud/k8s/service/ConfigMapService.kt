package bzh.cloud.k8s.service

import bzh.cloud.k8s.config.ClientUtil
import bzh.cloud.k8s.config.CmContext
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1ConfigMap
import io.kubernetes.client.openapi.models.V1ConfigMapBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service


@Service
class ConfigMapService() {


    companion object {
        private val log: Logger = LoggerFactory.getLogger(ConfigMapService::class.java)
    }

    fun create(context: CmContext) {
        val cm = V1ConfigMapBuilder()
                .withNewMetadata().withName(context.name).withNamespace(context.ns).endMetadata()
                .withData(mapOf(context.key to context.context))
                .build()
        ClientUtil.kubeApi().createNamespacedConfigMap(context.ns,cm,"true",null,null)
    }

    fun update(context: CmContext) {
        val cm = ClientUtil.kubeApi().readNamespacedConfigMap(context.name,context.ns,null,null,null)
        cm.data?.set(context.key, context.context)
        update(cm)
    }
    fun read(ns: String, name: String): V1ConfigMap {
        val a = ClientUtil.kubeApi().readNamespacedConfigMap(name, ns, "true", null, null)
        a.metadata?.creationTimestamp = null
        return a
    }
    fun list(ns: String): List<Any> {
        return ClientUtil.kubeApi().listNamespacedConfigMap(ns, "true",
                null, null, null, null, null, null, 0,false)
                .items
                .map {
                    var map = HashMap<String, Any?>()
                    map["name"] = it.metadata?.name
                    map["ns"] = it.metadata?.namespace
                    map["labels"] = it.metadata?.labels
                    map["uid"] = it.metadata?.uid
                    map["data"] = it.data
                    map
                }
    }
    fun update(configMap: V1ConfigMap) {
        CoreV1Api(ClientUtil.updateClient()).replaceNamespacedConfigMapWithHttpInfo(configMap.metadata?.name,
                configMap.metadata?.namespace, configMap,
                "true", null, null)
    }
    fun delete(ns: String, name: String) {
        ClientUtil.kubeApi().deleteNamespacedConfigMapWithHttpInfo(name, ns, "true", null, null, null, null, null)
    }
    fun create(ns: String,configMap: V1ConfigMap) {
        ClientUtil.kubeApi().createNamespacedConfigMap(ns,configMap,"true",null,null)
    }
    fun deleteData(ns: String, name: String,key:String){
        val cm = read(ns,name)
        cm.data?.set(key,null)
        update(cm)
    }
}