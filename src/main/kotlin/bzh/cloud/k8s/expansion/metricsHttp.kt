package bzh.cloud.k8s.expansion

import bzh.cloud.k8s.utils.curl
import io.kubernetes.client.models.NodeMetricsList


fun io.kubernetes.client.openapi.apis.CoreV1Api.metricsNode(): NodeMetricsList {
    return  curl {
        client { apiClient.httpClient }
        request {
            url("${apiClient.basePath}/apis/metrics.k8s.io/v1beta1/nodes")
        }
        returnType(NodeMetricsList::class.java)
    } as NodeMetricsList
}

