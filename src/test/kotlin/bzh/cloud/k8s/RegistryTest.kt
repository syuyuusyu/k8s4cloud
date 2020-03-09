package bzh.cloud.k8s


import bzh.cloud.k8s.controller.RegistryController
import bzh.cloud.k8s.expansion.DownLoadDetail
import bzh.cloud.k8s.expansion.metricsNode
import bzh.cloud.k8s.expansion.pullLayer
import bzh.cloud.k8s.expansion.tempFileDir
import bzh.cloud.k8s.utils.TAR
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

@SpringBootTest
class RegistryTest {

    @Value("\${self.authRegistryUrl}")
    lateinit var authRegistryUrl: String

    @Autowired
    lateinit var dockerHubAuthApi:DefaultApi

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
        val dockerAuth = dockerHubAuthApi.tokenGet(authService,scope)
        val token = dockerAuth.token
        println(dockerAuth.token)


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
        val file = File("/tmp/registry/YTMSoynv")
        file.mkdirs()

        TAR.decompress("/tmp/registry/YTMSoynv.tar",file)
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






}