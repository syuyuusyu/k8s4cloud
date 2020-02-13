package bzh.cloud.k8s.controller


import bzh.cloud.k8s.config.CmContext
import bzh.cloud.k8s.config.UpdateClient
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
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.apis.ExtensionsV1beta1Api
import io.kubernetes.client.openapi.models.*


@RestController
@RequestMapping("/kube")
class KubeController(
        val kubeApi: CoreV1Api,
        val extensionApi: ExtensionsV1beta1Api,
        val configMapService: ConfigMapService
) {
    @Value("\${self.kubeConfigPath}")
    lateinit var kubeConfigPath: String

    companion object {
        private val log: Logger = LoggerFactory.getLogger(KubeController::class.java)
        fun errorMsg(e: ApiException, result:HashMap<String, Any>){
            result["success"] = false
            if(StringUtils.isEmpty(e.responseBody)){
                result["msg"] = e.localizedMessage
            }else{
                try {
                    val mapper = ObjectMapper()
                    val actualObj = mapper.readTree(e.responseBody)
                    result["msg"] = actualObj["message"]
                }catch (e1:Exception){
                    result["msg"] = e.localizedMessage
                }

            }
        }
    }

    @GetMapping("/{kind}/{ns}")
    fun list(@PathVariable kind: String, @PathVariable ns: String): List<Any> = when (kind) {
            "Ingress" -> Ingress().list(ns)
            "Service" -> Service().list(ns)
            "PersistentVolume" -> PersistentVolume().list(ns)
            "PersistentVolumeClaim" -> PersistentVolumeClaim().list(ns)
            "Deployment" -> Deployment().list(ns)
            "ConfigMap" -> configMapService.list(ns)
            else -> ArrayList<Nothing>()
        }


    @GetMapping("/{kind}/{ns}/{name}")
    fun read(@PathVariable kind: String, @PathVariable ns: String, @PathVariable name: String): Any = when (kind) {
            "Ingress" -> Ingress().read(ns, name)
            "Service" -> Service().read(ns, name)
            "PersistentVolume" -> PersistentVolume().read(name)
            "PersistentVolumeClaim" -> PersistentVolumeClaim().read(ns, name)
            "Deployment" -> Deployment().read(ns, name)
            "ConfigMap" -> configMapService.read(ns, name)
            else -> Any()
        }


    @PutMapping("/PersistentVolume")
    fun updatePersistentVolume(@RequestBody body: V1PersistentVolume): Map<String, Any>
            = update(body){ PersistentVolume().update(it as V1PersistentVolume ) }

    @PutMapping("/Ingress")
    fun updateIngress(@RequestBody body: ExtensionsV1beta1Ingress): Map<String, Any>
            = update(body){ Ingress().update(it as ExtensionsV1beta1Ingress ) }

    @PutMapping("/Deployment")
    fun updateDeployment(@RequestBody body: ExtensionsV1beta1Deployment): Map<String, Any>
            = update(body) { Deployment().update(it as ExtensionsV1beta1Deployment) }

    @PutMapping("/Service")
    fun updateService(@RequestBody body: V1Service): Map<String, Any>?
            = update(body) { Service().update(it as V1Service) }

    @PutMapping("/PersistentVolumeClaim")
    fun updatePersistentVolumeClaim(@RequestBody body: V1PersistentVolumeClaim): Map<String, Any>
            = update(body) { PersistentVolumeClaim().update(it as V1PersistentVolumeClaim) }

    @PutMapping("/ConfigMap")
    fun updateConfigmap(@RequestBody body: V1ConfigMap): Map<String, Any>
            = update(body) { configMapService.update(it as V1ConfigMap) }

    @DeleteMapping("/{kind}/{ns}/{name}")
    fun delete(@PathVariable kind:String, @PathVariable ns: String, @PathVariable name: String): Map<String, Any> = when(kind){
            "Ingress" -> delete(ns,name,Ingress()::delete)
            "Service" -> delete(ns,name,Service()::delete)
            "PersistentVolume" -> delete(ns,name,PersistentVolume()::delete)
            "PersistentVolumeClaim" -> delete(ns,name,PersistentVolumeClaim()::delete)
            "Deployment" ->  delete(ns,name,Deployment()::delete)
            "ConfigMap" -> delete(ns,name, configMapService::delete)
            else -> HashMap()
        }


    @PostMapping("/PersistentVolume/{ns}")
    fun createPersistentVolume(@PathVariable ns: String,@RequestBody body: V1PersistentVolume): Map<String, Any>
            = create{ PersistentVolume().create(ns,body) }
    @PostMapping("/Ingress/{ns}")
    fun createIngress(@PathVariable ns: String,@RequestBody body: ExtensionsV1beta1Ingress): Map<String, Any>
            = create{ Ingress().create(ns,body) }
    @PostMapping("/Deployment/{ns}")
    fun createDeployment(@PathVariable ns: String,@RequestBody body: ExtensionsV1beta1Deployment): Map<String, Any>
            = create{ Deployment().create(ns,body) }
    @PostMapping("/Service/{ns}")
    fun createService(@PathVariable ns: String,@RequestBody body: V1Service): Map<String, Any>?
            = create{ Service().create(ns,body) }
    @PostMapping("/PersistentVolumeClaim/{ns}")
    fun createPersistentVolumeClaim(@PathVariable ns: String,@RequestBody body: V1PersistentVolumeClaim): Map<String, Any>
            = create{ PersistentVolumeClaim().create(ns,body) }
    @PostMapping("/ConfigMap/{ns}")
    fun createConfigmap(@PathVariable ns: String,@RequestBody body: V1ConfigMap): Map<String, Any>
            = create{ configMapService.create(ns,body) }

    //删除Configmap中的字段
    @DeleteMapping("/daleteConfigMapData/{ns}/{name}/{key}")
    fun deleteConfigMapData(@PathVariable ns:String,@PathVariable name:String,@PathVariable key:String): Map<String, Any>
            = delete("",""){ _,_-> configMapService.deleteData(ns,name,key) }

    //为Configmap新增字段
    @PutMapping("/addConfigMapData")
    fun addConfigMapData(@RequestBody context: CmContext): Map<String, Any>
        = create { configMapService.update(context) }


    private fun update(body: Any, updatefun: (Any) -> Unit): Map<String, Any> {
        val result = HashMap<String, Any>()
        try {
            updatefun(body)
            result["success"] = true
            result["msg"] = "更新成功"
        } catch (e: ApiException) {
            e.printStackTrace()
            errorMsg(e,result)
        }
        return result
    }

    private fun create( createfun: () -> Unit): Map<String, Any> {
        val result = HashMap<String, Any>()
        try {
            createfun()
            result["success"] = true
            result["msg"] = "新建成功"
        } catch (e: ApiException) {
            e.printStackTrace()
            errorMsg(e,result)
        }
        return result
    }

    private fun delete(ns: String, name: String, deletefun: (String,String) -> Unit): Map<String, Any> {
        val result = HashMap<String, Any>()
        try {
            deletefun(ns,name)
            result["success"] = true
            result["msg"] = "删除成功"
        } catch (e: ApiException) {
            e.printStackTrace()
            errorMsg(e,result)
        }
        return result
    }



    inner class Service {
        fun update(service: V1Service) {
            UpdateClient().replaceNamespacedServiceWithHttpInfo(service.metadata?.name, service.metadata?.namespace, service,
                    "true", null, null)

        }
        fun list(ns: String): List<Any> {
            return kubeApi.listNamespacedService(ns, "true",
                    null, null, null, null, null, null, 0,false)
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
            var a = kubeApi.readNamespacedService(name, ns, "true", null, null)
            a.metadata?.creationTimestamp = null
            a.status = null
            return a
        }
        fun delete(ns: String, name: String) {
            kubeApi.deleteNamespacedServiceWithHttpInfo(name, ns, "true", null, null, null, null, null)
        }
        fun create(ns: String,service: V1Service){
            kubeApi.createNamespacedService(ns,service,"true",null,null)
        }
    }

    inner class Ingress {
        fun list(ns: String): List<Any> {
            return extensionApi.listNamespacedIngress(ns, "true",
                    null, null, null, null, null, null, 0,false)
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
                    .deleteNamespacedIngressWithHttpInfo(name,ns,"true",null,null,null,null,null)
        }
        fun create(ns:String,ingress: ExtensionsV1beta1Ingress){
            extensionApi.createNamespacedIngressWithHttpInfo(ns,ingress,"true",null,null)
        }
    }

    inner class PersistentVolume {
        fun list(ns: String): List<Any> {
            return kubeApi.listPersistentVolume("true",
                    null, null, "", null, null, null, 0,false)
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
            var a = kubeApi.readPersistentVolume(name, "ture", null, null)
            a.metadata?.creationTimestamp = null
            a.status = null
            return a
        }
        fun update(persistentVolume: V1PersistentVolume) {
            UpdateClient()
                    .replacePersistentVolumeWithHttpInfo(persistentVolume.metadata?.name, persistentVolume,
                            "true", null, null)
        }
        fun delete(ns: String, name: String) {
            kubeApi.deletePersistentVolumeWithHttpInfo(name,"true", null, null, null, null, null)
        }
        fun create(ns: String, persistentVolume: V1PersistentVolume){
            kubeApi.createPersistentVolumeWithHttpInfo(persistentVolume,"true",null,null)
        }
    }

    inner class PersistentVolumeClaim {
        fun list(ns: String): List<Any> {
            return kubeApi.listNamespacedPersistentVolumeClaim(ns, "true",
                    null, null, null, null, null, null, 0,false)
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
        fun read(ns: String, name: String): V1PersistentVolumeClaim {
            var a = kubeApi.readNamespacedPersistentVolumeClaim(name, ns, "true", null, null)
            a.metadata?.creationTimestamp = null
            a.status = null
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
        fun create(ns: String, persistentVolumeClaim: V1PersistentVolumeClaim){
            kubeApi.createNamespacedPersistentVolumeClaimWithHttpInfo(ns,persistentVolumeClaim,"true",null,null)
        }
    }

    inner class Deployment {
        fun list(ns: String): List<Any> {
            log.info("")

            return extensionApi.listNamespacedDeployment(ns, "true",
                    null, null, null, null, null, null, 0,false)
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
        fun read(ns: String, name: String): ExtensionsV1beta1Deployment {
            var a = extensionApi.readNamespacedDeployment(name, ns, "true", null, null)
            a.metadata?.creationTimestamp = null
            a.status = null

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
                    .deleteNamespacedDeploymentWithHttpInfo(name,ns,"true",null,null,null,null,null)
        }
        fun create(ns:String,deployment: ExtensionsV1beta1Deployment){
            extensionApi.createNamespacedDeploymentWithHttpInfo(ns,deployment,"true",null,null)
        }
    }

}

