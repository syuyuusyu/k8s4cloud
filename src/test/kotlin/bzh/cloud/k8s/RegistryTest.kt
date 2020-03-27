package bzh.cloud.k8s


import bzh.cloud.k8s.config.KubeProperties
import bzh.cloud.k8s.controller.KubeController
import bzh.cloud.k8s.controller.RegistryController
import bzh.cloud.k8s.expansion.*
import bzh.cloud.k8s.service.RegistryService
import bzh.cloud.k8s.utils.JsonUtil
import bzh.cloud.k8s.utils.SpringUtil
import bzh.cloud.k8s.utils.TAR
import io.kubernetes.client.custom.Quantity
import io.kubernetes.client.openapi.apis.CoreV1Api
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Test
import org.openapitools.client.ApiClient
import org.openapitools.client.api.DefaultApi
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.web.reactive.function.client.WebClient
import java.io.File
import java.util.LinkedHashMap
import com.fasterxml.jackson.core.type.TypeReference
import com.google.gson.reflect.TypeToken
import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.models.*
import io.kubernetes.client.util.ClientBuilder
import io.kubernetes.client.util.KubeConfig
import io.kubernetes.client.util.Watch
import org.openapitools.client.model.V2ManifestResult
import sha256
import java.io.FileReader
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit


@SpringBootTest
class RegistryTest {

    @Value("\${self.authRegistryUrl}")
    lateinit var authRegistryUrl: String


    @Autowired
    lateinit var dockerHubApi:DefaultApi


    @Autowired
    lateinit var localRegistryApi:DefaultApi

    @Autowired
    lateinit var kubeApi: CoreV1Api

    val image="library/redis"

    val v2Accept="application/vnd.docker.distribution.manifest.v2+json"

    companion object{
        private val log: Logger = LoggerFactory.getLogger(RegistryTest::class.java)
    }

    @Test
    fun deleteAllquotaAndRange(){
        val list = kubeApi.listNamespace("false",false,null,null,null,
                null,null,null,null).items.map { it.metadata!!.name!! }.filter { it !in arrayOf("default","kube-public","kube-system") }
        list.forEach {
            kubeApi.deleteNamespacedResourceQuota("quota-$it",it,null,null,null,null,null,null)
            kubeApi.deleteNamespacedLimitRange("range-$it",it,null,null,null,null,null,null)
        }
    }

    @Test
    fun getAuthor(){

        val authService = "registry.docker.io";
        val scope = "repository:library/$image:pull"
        val url = "$authRegistryUrl/token?service=$authService&scpe=$scope"
        println(url)
        WebClient.create(url).get().exchange().doOnSuccess {
            println("sdsd")
            val a = it.headers().contentType().get()
            println(a)

        }
        .doAfterSuccessOrError { t, u ->
            print(u)
        }
        .doFinally {
            println(1111)
        }
    }

    fun dosome(){
        val c =ApiClient()
        c.selectHeaderAccept(arrayOf("application/vnd.docker.distribution.manifest.v2+json") )
        c.addDefaultHeader("Accept","application/vnd.docker.distribution.manifest.v2+json")
    }


    fun manifest11(token:String){
        println("edededed")
        val a =WebClient.create("").get().header("","");
        WebClient.create("https://index.docker.io/v2/library/redis/manifests/latest").get()
                .header("Accept","application/vnd.docker.distribution.manifest.v2+json")
                .header("Authorization","Bearer $token")
                .exchange()
                .doAfterSuccessOrError { t, u ->
                    println("sdsd")
                    val a = t.headers().contentType().get()
                    println(a)
                }
                .doFinally {
                    println(1111111)
                }
    }

    @Test
    fun manifest(){
        val authService = "registry.docker.io";
        val scope = "repository:library/redis:pull"
        //val dockerAuth = dockerHubAuthApi.tokenGet(authService,scope)



//        val v2manifest=dockerHubApi.getManifests("library/redis","latest",token!!)
//        println(v2manifest)
        //manifest11(dockerAuth.token!!)




    }

    @Test
    fun test2(){
        val str="name"
        val reg = Regex("(\\w+\\/)?(\\w+)")
        str.replace(reg,"")
        var a= reg.find(str)?.groups?.get(0)?.value
        var b = reg.find(str)?.groups?.get(1)?.value
        println(a)
        println(b)
        var ss = str.replace(reg,{it -> it.groupValues[2] })
        println(ss)
    }


    fun pull(token:String,digest:String){
        val client = OkHttpClient();
        val request = Request.Builder().url("https://index.docker.io/v2/library/redis/blobs/$digest")
                .addHeader("Authorization","Bearer $token")
                .build()
        val call: Call = client.newCall(request)
        val response: Response = call.execute()
        val len = response.body()?.contentLength()
        val filename = digest.replace("sha256:","")

        val file = File("/tmp/registry/redis/$filename.tar.gz")
        TAR.copyInputStreamToFile(response.body()?.byteStream(),file)


    }

    @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    @Test
    fun test(){
        val dir = "/tmp/registry/luj2Y6vq/";
        val file = File(dir)
        val repositoriesFile = File("$dir/repositories")
        val manifestFile = File("$dir/manifest.json")
        val jsonFileName =  file.list().find { it.endsWith(".json") && it!="manifest.json" }!!

        val jsonFile = File("$dir/$jsonFileName")
        var args = arrayOf(repositoriesFile,manifestFile,jsonFile)

        file.list()
                .filter {it != "repositories"  &&!it.endsWith(".json") }
                .map { File("$dir$it/layer.tar") }
                .forEach {args+=it}

        log.info("compress files:")
        args.iterator().forEach {
            log.info(" ->{}",it.absolutePath)
        }
        TAR.compressDockerImg("/tmp/registry/luj2Y6vq.tar", *args)
    }

    @Test
    fun test3(){
        var regx = Regex("""^https?:\/\/\d+\.\d+\.\d+\.\d+(\:\d+)?""")
        var str = "http://192.168.50.28:5000/v2"
        var a = regx.find(str)

        println(    a?.value)
    }

    @Test
    fun test4(){
        val metlist = kubeApi.metricsNode()
        val podNum = kubeApi.listNamespacedPod("cloud-ns",null,null,null,null,
                null,null,null,0,false)
        val nodelist = kubeApi.listNode(null,false,null,null,null,0,null,0,false)
        val result = metlist.items?.map { metricsitem->
            val map = HashMap<String, Any?>()
            val usage = metricsitem.usage
            val nodeItem = nodelist.items.find{it.metadata?.name == metricsitem.metadata?.name }
            map["usage"] = usage
            map["capacity"] = nodeItem?.status?.capacity
            map["allocatable"] = nodeItem?.status?.allocatable
            map
        }
    }

    @Test
    fun test5(){
        val clazz = this.javaClass.classLoader;

        val file = File("/tmp/registry/oMAxYU53/repositories")
        var data = readFromInputStream(file.inputStream())!!
        data = data.replace("\\s+".toRegex(),"")


        var b = data.matches("""\{"([^"]+)":\{"[^"]+":"([^"]+)?"}}""".toRegex())
        println(b)

        var (a)="""\{"([^"]+)":\{"[^"]+":"([^"]+)?"}}""".toRegex().matchEntire(data)!!.destructured
        println(a)

        //var (a) = regx.matchEntire(data!!)?.destructured!!

        //log.info("{},{},{}",a,"","")
    }

    @Test
    fun test6(){
        val a= V1ResourceQuotaBuilder()
                .withNewSpec().addToHard(
                mapOf(
                   "cpu" to Quantity("1000m"),
                   "memory" to Quantity("2Gi") ,
                   "pods" to Quantity("10")
                )
            ).endSpec().build()

        println(a)
        //kubeApi.createNamespacedResourceQuota("liyz-namespace",a,null,null,null)

    }

    @Test
    fun test7(){
        val limitMap = mapOf(
                "cpu" to Quantity("100m"),
                "memory" to Quantity("200Mi")
        )
        //var a = buildRange(limitMap)
        //kubeApi.createNamespacedLimitRange("v2ray",a,null,null,null)
    }

    fun buildRange(ns:String, limit:Map<String,Quantity>): V1LimitRange {
        return V1LimitRangeBuilder()
                .withNewMetadata().withName("range-$ns").endMetadata()
                .withNewSpec().addNewLimit().addToMax(limit).withNewType("Pod")
                .endLimit().endSpec().build()
    }

    @Test
    fun test8(){
        println(dockerHubApi.apiClient.basePath)

        val probe = dockerHubApi.getManifestsCall("library/redis","latest","",null).execute()

        println(probe.code())
        val list = probe.headers()["Www-Authenticate"]
        println(list)


        var (url,service,scope)="""Bearer realm="(.*)",service="(.*)",scope="(.*)"""".toRegex().matchEntire(list!!)!!.destructured
        println(url)
    }

    @Test
    fun test9(){

        val json2 = """{"Config":"710ee9182f7e9b0863a80134b5515c5b9d59298a36d1990a868c5b4b9cfbab11.json","repoTags":["k8s4cloud:vtest"],"layers":["b7e513f1782880dddf7b47963f82673b3dbd5c2eeb337d0c96e1ab6d9f3b76bd/layer.tar","ec100ca159973d775f33cf7d19e41c080850cc9d6738f9af7d609615fd65c6d2/layer.tar","128940b8e53a17540542432ae92a8a9721d632c5edce7bc58ec860e84bd6310d/layer.tar","36e86e1a4e95b00f0c68d59ab1bd7664a79fba1cbd69c8e53616d062ec80d74a/layer.tar"]}"""
        val b = JsonUtil.jsonToBean(json2,ManifestJson::class.java)
        println(b)
        val a = object :TypeReference<List<ManifestJson>>(){}


    }

    @Test
    fun test10(){
        val a = sha256("/Users/syu/temp/config.json")
        println(a)
    }

    @Test
    fun test11(){
        val a = listOf("asasas","rfrfrfrf","sdfdfg","oipinklknlj")
        val layers = a.reduceIndexed { i,acc, s -> if(i==1) """"$acc","$s"""" else acc+",\""+s+"\"" }
        println(layers)
    }

    @Test
    fun watch(){
        val path = (SpringUtil.getBean("self-bzh.cloud.k8s.config.KubeProperties") as KubeProperties).kubeConfigPath
        val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(path))).build()
        val httpClient = client.getHttpClient().newBuilder().readTimeout(0, TimeUnit.SECONDS).build();
        client.setHttpClient(httpClient);
        Configuration.setDefaultApiClient(client);
        val api = CoreV1Api()

        val watch = Watch.createWatch<V1Pod>(client,
                api.listNamespacedPodCall("isp-ns",null,null,null,null,null,
                5,null,null,true,null),
                object : TypeToken<Watch.Response<V1Pod>>() {}.type )
        watch.forEach {
            log.info(it.`object`.metadata?.name)
        }



    }

    @Test
    fun watch2(){
        val path = (SpringUtil.getBean("self-bzh.cloud.k8s.config.KubeProperties") as KubeProperties).kubeConfigPath
        val client = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(FileReader(path))).build()
        val httpClient = client.getHttpClient().newBuilder().readTimeout(0, TimeUnit.SECONDS).build();
        client.setHttpClient(httpClient);
        Configuration.setDefaultApiClient(client);
        val api = CoreV1Api()

        val watch = Watch.createWatch<V1ResourceQuota>(client,
                api.listResourceQuotaForAllNamespacesCall(null, null, "",
                        "", null, null, null, 0, true,null),
                object : TypeToken<Watch.Response<V1ResourceQuota>>() {}.type )
        watch.forEach {
            log.info(it.`object`.metadata?.name)
        }



    }






}