package bzh.cloud.k8s.expansion


import bzh.cloud.k8s.config.KubeProperties
import bzh.cloud.k8s.controller.RegistryController
import bzh.cloud.k8s.utils.JsonUtil
import bzh.cloud.k8s.utils.SpringUtil
import bzh.cloud.k8s.utils.TAR
import com.fasterxml.jackson.annotation.JsonAlias
import okhttp3.*
import okio.*
import org.openapitools.client.api.DefaultApi
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.FluxSink
import java.io.ByteArrayInputStream
import java.io.File
import java.net.Proxy
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingDeque
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class ProgressListener(val digest:String) {
    companion object{
        private val log: Logger = LoggerFactory.getLogger(ProgressListener::class.java)
    }
    var sessionProgress:SessionProgress? = null
    var sink : FluxSink<DownLoadDetail>? = null
    fun update(bytesRead: Long, contentLength: Long, done: Boolean){
        if(done){
            log.info("complete pull layer $digest {}",sessionProgress==null)
            sessionProgress?.afterPullComplete()
        }

        sink?.let {
            val det = DownLoadDetail().apply { completed=done;size=contentLength;this.digest=this@ProgressListener.digest;downloadSize=bytesRead }
            log.info("{}",det)
            it.next(det)
        }
    }
}

class SessionProgress(val session: String){
    companion object{
        private val log: Logger = LoggerFactory.getLogger(ProgressListener::class.java)
    }
    val map = Collections.synchronizedMap(HashMap<String,ProgressListener>())
    val status = LinkedBlockingDeque<Boolean>()
    fun initSink(sink : FluxSink<DownLoadDetail>){
        map.forEach { k, v ->
            v.sink=sink
            status.put(false)
        }
        this.sink = sink

    }
    var sink : FluxSink<DownLoadDetail>? = null


    fun put(digest:String,proceess:ProgressListener) = map.put(digest,proceess)
    fun afterPullComplete(){
        status.take()
        log.info("status.size {}",status.size)
        if(status.size == 0){
            log.info("all layper pull complete session:{}",session)
            sink?.let {
                it.next(DownLoadDetail().apply { this.session = this@SessionProgress.session;pullComplete=true })
                it.complete()
            }

        }

    }
}

class DownloadInfo(){
    var success:Boolean=false
    var msg:String=""
    var detailUrl:String=""
    var sessionId:String = ""
    val digests = ArrayList<String>()
}

class ManifestJson(){
    @JsonAlias("Config")
    var Config:String = ""
    @JsonAlias("RepoTags")
    val RepoTags = ArrayList<String>()
    @JsonAlias("Layers")
    val Layers = ArrayList<String>()
}

class DownLoadDetail(){
    var session = ""
    var pullComplete = false
    var digest = ""
    var size = 1L
    var downloadSize = 0L
    val percent
        get() = "${100*downloadSize/size}%"
    var completed = false
    override fun toString(): String {
        return "DownLoadDetail(session='$session', pullComplete=$pullComplete, " +
                "digest='$digest', size=$size, downloadSize=$downloadSize, completed=$completed),percent=$percent"
    }


}

class ProgressResponseBody(
        val responseBody:ResponseBody,
        val progressListener:ProgressListener
) :ResponseBody(){
    var bufferedSource:BufferedSource?=null

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
                if(duration == 0L){
                    println("start read layer!!")
                }
                duration++
                val bytesRead = super.read(sink, byteCount)
                totalBytesRead += if (bytesRead != -1L) bytesRead else 0
                if(duration%10L == 0L || bytesRead == -1L ){
                    progressListener.update(totalBytesRead, responseBody.contentLength(), bytesRead == -1L)
                }
                return bytesRead
            }
        }
    }
}


val DefaultApi.tempFileDir:String
        get() = (SpringUtil.getBean("self-bzh.cloud.k8s.config.KubeProperties") as KubeProperties).tempFileDir


fun DefaultApi.pullLayer(name:String, digest:String, session: String ="", authorization:String?, progressListener:ProgressListener?):Response{
    var client = OkHttpClient()
    if(progressListener != null){
        val clientBuild = OkHttpClient.Builder()
                .addNetworkInterceptor { chain: Interceptor.Chain ->
                    val originalResponse = chain.proceed(chain.request())
                    originalResponse.newBuilder()
                            .body(ProgressResponseBody(originalResponse.body()!!, progressListener))
                            .build()
                }
        if (apiClient.basePath.matches(Regex("docker.io"))){
            val proxy = SpringUtil.getBean("proxy") as Proxy
            clientBuild.proxy(proxy)
        }
        client  = clientBuild.build()

    }
    val path = "${apiClient.basePath}/$name/blobs/$digest"
    log.info("pullLayer {}",path)

    val request = Request.Builder().url(path)
            .get()
            .addHeader("Authorization","Bearer $authorization")
            .build()
    val call: Call = client.newCall(request)
    return call.execute()

}

fun DefaultApi.createLayer(name:String,digest:String,session:String,authorization:String?){
    val progressListener =  ProgressListener(digest)
    synchronized(RegistryController.downloadListenerMap){
        val process = RegistryController.downloadListenerMap.get(session)?:SessionProgress(session)
        progressListener.sessionProgress = process
        process.put(digest,progressListener)
        RegistryController.downloadListenerMap.put(session,process)

        log.info("session:{} digest:{},mapsize:{}",session,digest,process.map.size)
    }
    val response = pullLayer(name,digest,session,authorization,progressListener)
    log.info("file size:{}",response.header("Content-Length"))

    val filename = digest.replace("sha256:","")

    val dir = File("$tempFileDir$session/$filename")
    if(!dir.exists()){
        dir.mkdirs()
    }
    val file = File("$tempFileDir$session/$filename/layer.tar")
    TAR.copyInputStreamToFile(response.body()?.byteStream(),file)
}



fun DefaultApi.createPullFile(name:String,tag:String,digest:String,session:String,authorization:String?,layerDigests:List<String>){
    log.info("createPullFile")
    //create configjosnFile
    log.info("pull configjosnFile")
    val response = pullLayer(name,digest,session,authorization,null)
    log.info("pull configjosnFile complete")
    val configjsonfilename = digest.replace("sha256:","")+".json"

    val dir = File("$tempFileDir$session/")
    if(!dir.exists()){
        dir.mkdirs()
    }
    val configjosnFile = File("$tempFileDir$session/$configjsonfilename")
    TAR.copyInputStreamToFile(response.body()?.byteStream(),configjosnFile)

    //create repositories
    val repositoriesFile = File("$tempFileDir$session/repositories")
    val dest = name.replace(Regex("(\\w+\\/)?(\\w+)"),{ it.groupValues[2] })
    val repositoriesStr = """
        {
        	"$dest": { "$tag": "" }
        }
    """.trimIndent()
    val repositoriesInput = ByteArrayInputStream(repositoriesStr.toByteArray())
    TAR.copyInputStreamToFile(repositoriesInput,repositoriesFile)


    //create manifest.json
    val manifestFile = File("$tempFileDir$session/manifest.json")
    val obj = ManifestJson().apply {
        Config = configjsonfilename
        RepoTags.add("$dest:$tag")
        layerDigests.map { it.replace("sha256:","")+"/layer.tar" }.forEach{Layers.add(it)}
    }
    val list = arrayOf(obj)
    val manifestStr = JsonUtil.beanToJson(list)
    val manifestInput = ByteArrayInputStream(manifestStr.toByteArray())
    TAR.copyInputStreamToFile(manifestInput,manifestFile)
    log.info("createPullFile complete")
}

fun DefaultApi.uploadFile(session: String):Boolean{
    val file = File("$tempFileDir/$session")
    file.mkdirs()
    TAR.decompress("$tempFileDir/$session.tar",file)
    return true
}

fun DefaultApi.compressDownFile(session: String):Boolean{
    val dir = "$tempFileDir/$session";
    val file = File(dir)
    if(!file.exists()){
        return false
    }
    val repositoriesFile = File("$dir/repositories")
    val manifestFile = File("$dir/manifest.json")
    val jsonFileName =  file.list().find { it.endsWith(".json") && it!="manifest.json" }!!
    val jsonFile = File("$dir/$jsonFileName")
    var args = arrayOf(repositoriesFile,manifestFile,jsonFile)

    file.list()
            .filter {it != "repositories"  && !it.endsWith(".json") }
            .map {File("$dir/$it/layer.tar") }
            .forEach {args += it}
    log.info("compress files:")
    args.iterator().forEach {
        log.info(" ->{}",it.absolutePath)
    }
    TAR.compressDockerImg("$tempFileDir$session.tar", *args)
    return true
}

fun DefaultApi.clearFile(session: String){
    val dir = File("$tempFileDir/$session")
    val tar = File("$tempFileDir/$session.tar")
    deleteFolder(dir)
    tar.delete()
}

fun deleteFolder(folder: File) {
    val files = folder.listFiles()
    if (files != null) { //some JVMs return null for empty dirs
        for (f in files) {
            if (f.isDirectory) {
                deleteFolder(f)
            } else {
                f.delete()
            }
        }
    }
    folder.delete()
}

