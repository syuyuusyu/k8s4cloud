package bzh.cloud.k8s

import bzh.cloud.k8s.expansion.*
import com.fasterxml.jackson.core.type.TypeReference
import com.google.gson.reflect.TypeToken
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1ReplicationController
import io.kubernetes.client.openapi.models.V1ReplicationControllerList
import io.kubernetes.client.openapi.models.V1ResourceQuota
import io.kubernetes.client.util.Watch
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.openapitools.client.api.DefaultApi
import org.openapitools.client.model.Tags
import org.openapitools.client.model.V2ManifestResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

@SpringBootTest
class CurlTest {

    @Value("\${self.authRegistryUrl}")
    lateinit var authRegistryUrl: String


    @Autowired
    lateinit var dockerHubApi: DefaultApi


    @Autowired
    lateinit var localRegistryApi: DefaultApi

    @Autowired
    lateinit var kubeApi: CoreV1Api

    @Autowired
    lateinit var apiClient: ApiClient

    @Value("\${self.registryUrl}")
    lateinit var registryUrl: String

    companion object {
        private val log: Logger = LoggerFactory.getLogger(CurlTest::class.java)
    }


    @Test
    fun testLog() {
        val (client, api) = bzh.cloud.k8s.config.watchClient()
        val a = curl {
            client { client.httpClient }
            request {
                url("${client.basePath}/api/v1/namespaces/test2/pods/testpod/log")
                params {
                    "follow" to true
                    "previous" to false
                    "limitBytes" to null
                }
            }
            event {
                readStream { byte ->
                    log.info("{}", String(byte))
                }
            }
        } as CurlEvent
        //println(b)
        Thread.sleep(1000 * 10)
        a.close()
    }

    @Test
    fun test13() {
        println(apiClient.isDebugging)
        val (client, api) = bzh.cloud.k8s.config.watchClient()
        val a = curl {
            client { client.httpClient }
            request {
                url("${client.basePath}/api/v1/replicationcontrollers")
                params {
                    "watch" to "true"
                }
            }
            //returnType(V1PodList::class.java)
            event {
                onWacth { line ->
                    //println(line)
                    log.info("{}", line)
                }
            }
        } as CurlEvent
        println(a)
        println(a.javaClass)

        Thread.sleep(1000 * 100)
        a.close()
        Thread.sleep(1000*10)
    }

    @Test
    fun test12() {
        val client = OkHttpClient()


        //val body = RequestBody.create()

        val request = Request.Builder()
                .addHeader("Accept", "application/json")
                .url("https://20.18.6.16/admin/j_spring_security_check").get().build()
        val call: Call = client.newCall(request)
        var response: Response = call.execute()


        val json = response.body()?.string()
    }

    @Test
    fun testdownload() {
        val down = curl {
            request {
                url("http://127.0.0.1:7001/swift/download")
                method("post")
                body {
                    json {
                        "bytes" to 50356421
                        "content_type" to "application/zip"
                        "filename" to "宜良县检查记录（2019.3.12）.zip"
                        "hash" to "149c71b1a6c1d07293fd92684c9adc94"
                        "hierachy" to 2
                        "last_modified" to "2019-03-19T00:11:43.199130"
                        "name" to "宜良县检查记录（2019.3.12）/宜良县检查记录（2019.3.12）.zip"
                        "username" to "yjzjStorage"
                    }
                }
                head {
                    "Access-Token" to "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJwYXlsb2FkIjp7ImlkIjoxLCJuYW1lIjoi566h55CG5ZGYIiwidXNlcl9uYW1lIjoiYWRtaW4iLCJwaG9uZSI6IjEzNTc3MDUwMTczIiwiSURfbnVtYmVyIjoiMTExMTExMTExMTExMTExMTEyIiwiZW1haWwiOiIxMjM0NTZAcXEuY29tIn0sImlhdCI6MTU5MDU0MDk0NH0.XIA4yOm35dSNZdI8QYnLX6TlcPQCVL1uQ_lJBY4YDIM"
                }
            }
//            event {
//                onDownloadProgress { read, total, done ->
//                    log.info("read:{},total:{},done:{}",read,total,done)
//                }
//            }
        } as Response
        log.info("{}", down.body()?.contentLength())
    }

    @Test
    fun testdown2() {
        val down = curl {
            request {
                url("http://127.0.0.1:8002/registry/test")
            }
            event {
                onDownloadProgress { read, total, done ->
                    log.info("read:{},total:{},done:{}", read, total, done)
                }
            }
        } as Response
        log.info("{}", down.body()?.contentLength())
        var a =down.body()?.string()
    }

    @Test
    fun testdown3() {

        val client = OkHttpClient.Builder().addNetworkInterceptor { chain: Interceptor.Chain ->
            val originalResponse = chain.proceed(chain.request())
            originalResponse.newBuilder()
                    .body(ProgressResponse(originalResponse.body()!!,
                            { read, total, done ->
                                log.info("read:{},total:{},done:{}", read, total, done)
                            }))
                    .build()
        }.build()
        val request =  Request.Builder().url("http://127.0.0.1:8002/registry/test").build()
        val call = client.newCall(request)
        val response = call.execute()
        var str = response.body()?.byteStream()
        str?.read(ByteArray(1024))
        log.info("{}", response.body()?.contentLength())
    }

    @Test
    fun testdown4() {
        val listener = ProgressListener("sdsdsd")
        var client = OkHttpClient.Builder().readTimeout(44, TimeUnit.SECONDS).addNetworkInterceptor { chain: Interceptor.Chain ->
            val originalResponse = chain.proceed(chain.request())
            originalResponse.newBuilder()
                    .body(ProgressResponseBody(originalResponse.body()!!,listener))
                    .build()
        }.build()
        client = localRegistryApi.downLoadRrocessClient(listener)
        val request =  Request.Builder().url("http://127.0.0.1:8002/registry/test").get().build()
        val call = client.newCall(request)
        val response = call.execute()
        log.info("{}", response.body()?.contentLength())
        var str = response.body()?.string()

        Thread.sleep(1000*5)

    }

    @Test
    fun deleteregistryimg(){
        val name = "k8s4cloud"
        val tag = "v1.01"
        var deleteMani:V2ManifestResult?=null
        val tags = curl {
            request{
                url("$registryUrl/v2/$name/tags/list")
            }
            returnType(Tags::class.java)
        } as Tags

        tags.tags?.forEach { println(it) }
        val otherManifest = tags.tags!!.stream().parallel().map {
            val mani = curl {
                request {
                    url("$registryUrl/v2/$name/manifests/$it")
                    head {
                        "Accept" to "application/vnd.docker.distribution.manifest.v2+json"
                    }
                }
                returnType(V2ManifestResult::class.java)
            } as V2ManifestResult
            mani.apply { mediaType = it }
            if( it == tag) deleteMani = mani
            mani
        }.collect(Collectors.toList())
        val alldigest = HashSet<String>()
        val deletedigest = HashSet<String>()
        otherManifest.forEach { mani->
            println("dfdf "+mani.mediaType)
            alldigest.add(mani.config?.digest!!)
            mani.layers?.forEach { alldigest.add(it.digest!!) }
        }
        deletedigest.add(deleteMani!!.config?.digest!!)
        deleteMani?.layers?.forEach { deletedigest.add(it.digest!!) }
        println(alldigest)
        println(deletedigest)
        val finaldelete = deletedigest subtract otherManifest
        println(finaldelete)

    }

    @Test
    fun replicationcontrollers(){
        val list = curl{
            client { apiClient.httpClient }
            request{
                url("${apiClient.basePath}/api/v1/replicationcontrollers")
            }
            returnType(V1ReplicationControllerList::class.java)
        } as V1ReplicationControllerList
        println(list.items.size)
    }


    @Test
    fun inseruser(){
        val user = """
[
            { 'userName': 'ljszrzyhghj_liujisheng', 'name': '刘继生', 'phone': '13988889930', 'password': '123456', 'systemCode': 'S11' },
        ]
        """.trimIndent()

        val json = JSONArray(user)
        for (x in 0..json.length()-1){
            //println(json[x])
            val u = json[x] as JSONObject
            val reult = curl {
                request {
                    url("http://127.0.0.1:7001/userRegister/saveForSystem")
                    method("post")
                    body{u }
                }
            } as Response
            println(reult.code())
        }

    }

    @Test
    fun sdsd(){
        val (client, api) = bzh.cloud.k8s.config.watchClient()
        val watch = Watch.createWatch<V1ReplicationController>(
                client,
                api.listReplicationControllerForAllNamespacesCall(null,null,null,null,null,null,null,null,true,null),
                object : TypeToken<Watch.Response<V1ReplicationController>>() {}.type)
        watch.forEach {
            log.info("{}",it.`object`.metadata?.name)
        }
    }

    @Test
    fun clusterrolebindings(){
        val (client, api) = bzh.cloud.k8s.config.watchClient()
        val list = curl{
            client { client.httpClient }
            request{
                url("${client.basePath}/apis/rbac.authorization.k8s.io/v1/clusterroles")
                params {
                    "watch" to true
                }
            }
            event {
                onWacth { line->
                    log.info(line)
                }
            }

        } as CurlEvent

        Thread.sleep(1000*5)
    }

    @Test
    fun metrics(){
        val (client, api) = bzh.cloud.k8s.config.watchClient()
        val response = curl{
            client { client.httpClient }
            request{
                url("${client.basePath}/apis/metrics.k8s.io/v1beta1/nodes")

            }
        }as Response
        val str =  response.body()?.string()!!

        println(str)
    }


}



