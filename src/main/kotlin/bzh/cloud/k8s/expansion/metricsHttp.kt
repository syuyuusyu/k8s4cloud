package bzh.cloud.k8s.expansion

import com.google.gson.reflect.TypeToken
import io.kubernetes.client.openapi.Pair
import org.openapitools.client.model.NodeMetricsList
import java.util.*


fun io.kubernetes.client.openapi.apis.CoreV1Api.metricsNode(): NodeMetricsList {
    val localVarPostBody: Any? = null
    val localVarPath = "/apis/metrics.k8s.io/v1beta1/nodes"
    val localVarQueryParams = ArrayList<Pair?>()
    val localVarCollectionQueryParams = ArrayList<Pair?>()


    val localVarHeaderParams = HashMap<String, String>()
    val localVarCookieParams = HashMap<String, String>()
    val localVarFormParams = HashMap<String, Any?>()
    val localVarAccepts = arrayOf("*/*")
    val localVarAccept = apiClient.selectHeaderAccept(localVarAccepts)
    if (localVarAccept != null) {
        localVarHeaderParams["Accept"] = localVarAccept
    }

    val localVarContentTypes = arrayOfNulls<String>(0)
    val localVarContentType = apiClient.selectHeaderContentType(localVarContentTypes)
    localVarHeaderParams["Content-Type"] = localVarContentType
    val localVarAuthNames = arrayOf("BearerToken")
    val call = apiClient.buildCall(localVarPath, "GET", localVarQueryParams, localVarCollectionQueryParams, localVarPostBody, localVarHeaderParams, localVarCookieParams, localVarFormParams, localVarAuthNames, null);
    val localVarReturnType = object : TypeToken<NodeMetricsList?>() {}.type
    val apiResponse = apiClient.execute<NodeMetricsList>(call,localVarReturnType)
    return  apiResponse.data
}