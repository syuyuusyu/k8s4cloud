package bzh.cloud.k8s.expansion

import bzh.cloud.k8s.config.KubeProperties
import bzh.cloud.k8s.utils.SpringUtil
import com.google.gson.reflect.TypeToken
import io.kubernetes.client.models.NodeMetricsList
import io.kubernetes.client.openapi.Pair
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.*
import java.io.FileReader
import java.net.SocketTimeoutException


class LogResponseBody(
        val responseBody: ResponseBody
) : ResponseBody(){
    var bufferedSource: BufferedSource?=null

    override fun contentLength(): Long = responseBody.contentLength()

    override fun contentType(): MediaType? = responseBody.contentType()

    override fun source(): BufferedSource {
        if (bufferedSource == null) {
            bufferedSource = source(responseBody.source())?.let { Okio.buffer(it) };
        }
        return bufferedSource!!;
    }

    private fun source(source: Source): Source? {
        return object : ForwardingSource(source) {
            var totalBytesRead = 0L
            var duration = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                duration++
                var bytesRead:Long=0
                try{
                    bytesRead = super.read(sink, byteCount)
                }catch (e: SocketTimeoutException){
                    throw e
                }
                totalBytesRead += if (bytesRead != -1L) bytesRead else 0
                return bytesRead
            }
        }
    }
}

//fun io.kubernetes.client.openapi.apis.CoreV1Api.metricsNode(): NodeMetricsList {
//    val localVarPostBody: Any? = null
//    val localVarPath = "/apis/metrics.k8s.io/v1beta1/nodes"
//    val localVarQueryParams = ArrayList<Pair?>()
//    val localVarCollectionQueryParams = ArrayList<Pair?>()
//
//
//    val localVarHeaderParams = HashMap<String, String>()
//    val localVarCookieParams = HashMap<String, String>()
//    val localVarFormParams = HashMap<String, Any?>()
//    val localVarAccepts = arrayOf("*/*")
//    val localVarAccept = apiClient.selectHeaderAccept(localVarAccepts)
//    if (localVarAccept != null) {
//        localVarHeaderParams["Accept"] = localVarAccept
//    }
//
//    val localVarContentTypes = arrayOfNulls<String>(0)
//    val localVarContentType = apiClient.selectHeaderContentType(localVarContentTypes)
//    localVarHeaderParams["Content-Type"] = localVarContentType
//    val localVarAuthNames = arrayOf("BearerToken")
//    val call = apiClient.buildCall(localVarPath, "GET", localVarQueryParams, localVarCollectionQueryParams, localVarPostBody,
//            localVarHeaderParams, localVarCookieParams, localVarFormParams, localVarAuthNames, null);
//    val localVarReturnType = object : TypeToken<NodeMetricsList?>() {}.type
//    val apiResponse = apiClient.execute<NodeMetricsList>(call,localVarReturnType)
//    return  apiResponse.data
//}

fun io.kubernetes.client.openapi.apis.CoreV1Api.metricsNode(): NodeMetricsList {
    return  curl {
        client { apiClient.httpClient }
        request {
            url("${apiClient.basePath}/apis/metrics.k8s.io/v1beta1/nodes")
        }
        returnType(NodeMetricsList::class.java)
    } as NodeMetricsList
}

fun io.kubernetes.client.openapi.apis.CoreV1Api.watchLog( name:String,  namespace:String,  container:String?) {

    val path = (SpringUtil.getBean("self-bzh.cloud.k8s.config.KubeProperties") as KubeProperties).kubeConfigPath
    val execapi = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(path))).build()

    val clientBuild = OkHttpClient.Builder()

    clientBuild.addNetworkInterceptor { chain: Interceptor.Chain ->
        val originalResponse = chain.proceed(chain.request())
        originalResponse.newBuilder()
                .body(LogResponseBody(originalResponse.body()!!))
                .build()
    }
    val client = clientBuild.build()
    execapi.setHttpClient(client)
    execapi.setDebugging(true)

    val call = this.readNamespacedPodLogCall( name,  namespace,  container,  true, null ,  "false",
            false,  null,  null,  null,  null)
    execapi.execute<String>(call)

}