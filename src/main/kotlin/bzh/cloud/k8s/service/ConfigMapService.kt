package bzh.cloud.k8s.service

import bzh.cloud.k8s.config.CmContext
import bzh.cloud.k8s.config.UpdateClient
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1ConfigMap
import io.kubernetes.client.openapi.models.V1ConfigMapBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service


@Service
class ConfigMapService(
        val kubeApi: CoreV1Api
) {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(ConfigMapService::class.java)
    }

    fun create(context: CmContext) {
        var cm = V1ConfigMapBuilder()
                .withNewMetadata().withName(context.name).withNamespace(context.ns).endMetadata()
                .withData(mapOf(context.key to context.context))
                .build()
        kubeApi.createNamespacedConfigMap(context.ns,cm,"true",null,null)
    }

    fun update(context: CmContext) {
        var cm = kubeApi.readNamespacedConfigMap(context.name,context.ns,null,null,null)
        cm.data?.set(context.key, context.context)
        update(cm)
    }
    fun read(ns: String, name: String): V1ConfigMap {
        var a = kubeApi.readNamespacedConfigMap(name, ns, "true", null, null)
        a.metadata?.creationTimestamp = null
        return a
    }
    fun list(ns: String): List<Any> {
        return kubeApi.listNamespacedConfigMap(ns, "true",
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
        UpdateClient().replaceNamespacedConfigMapWithHttpInfo(configMap.metadata?.name,
                configMap.metadata?.namespace, configMap,
                "true", null, null)
    }
    fun delete(ns: String, name: String) {
        kubeApi.deleteNamespacedConfigMapWithHttpInfo(name, ns, "true", null, null, null, null, null)
    }
    fun create(ns: String,configMap: V1ConfigMap) {
        kubeApi.createNamespacedConfigMap(ns,configMap,"true",null,null)
    }
    fun deleteData(ns: String, name: String,key:String){
        var cm = read(ns,name)
        cm.data?.set(key,null)
        update(cm)
    }
}