package bzh.cloud.k8s.expansion

import bzh.cloud.k8s.config.KubeProperties
import bzh.cloud.k8s.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.internal.Util
import okio.*
import org.apache.commons.lang.RandomStringUtils
import org.openapitools.client.api.DefaultApi
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.FluxSink
import sha256
import java.io.*
import java.net.Proxy
import java.net.SocketTimeoutException
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import java.nio.file.NoSuchFileException
import java.util.concurrent.TimeUnit


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
                var bytesRead:Long=0
                try{
                     bytesRead = super.read(sink, byteCount)
                }catch (e:SocketTimeoutException){
                    throw e
                }
                totalBytesRead += if (bytesRead != -1L) bytesRead else 0
                if(duration%10L == 0L || bytesRead == -1L ){
                    progressListener.update(totalBytesRead, bytesRead,responseBody.contentLength(), bytesRead == -1L,"down")
                }
                return bytesRead
            }
        }
    }
}

class ProgressRequestBody(
        val file:File,
        val progressListener:ProgressListener
):RequestBody(){
    override fun contentType(): MediaType? {
        return MediaType.parse("application/octet-stream")
    }

    override fun contentLength(): Long {
        return try {
            file.length()
        } catch (e: IOException) {
            0
        }
    }

    override fun writeTo(sink: BufferedSink) {
        var source: Source? = null
        val filesize= contentLength()
        try {
            source = Okio.source(file)
            var total: Long = 0
            var read: Long
            while (source.read(sink.buffer(), 4096).also { read = it } != -1L) {
                total += read
                sink.flush()
                progressListener.update(total,4096,filesize,total >= filesize,"up")
            }
        } catch (e:SocketTimeoutException){
            throw e
        } finally {
            Util.closeQuietly(source)
        }
    }

}

class ProgressListener(val digest:String) {
    var  count=0
    companion object{
        private val log: Logger = LoggerFactory.getLogger(ProgressListener::class.java)
    }
    var sessionProgress:SessionProgress? = null
    //var sink : FluxSink<ProcessDetail>? = null
    fun update(bytesRead: Long, stepLength:Long,contentLength: Long, done: Boolean,action:String){
        count++
        if(count%20==0){
            val l = if(contentLength==0L) 1 else contentLength
            log.info("size {},action {},percent:{},sink?{},stepLength:{}",bytesRead,action,100*bytesRead/l,sessionProgress?.sink==null,stepLength)
            sessionProgress?.sink?.let {
                val det = ProcessDetail().apply {
                    size=contentLength
                    this.digest=this@ProgressListener.digest
                    processSize=bytesRead;
                    this.operation=this@ProgressListener.sessionProgress?.operation!!.name
                    this.action = action
                }
                it.next(det)
            }
        }

    }
}

class SessionProgress private constructor(val session: String) : CoroutineScope by CoroutineScope(Dispatchers.Default) {
    enum class Operation{UPLOAD,DOWNLOAD,MOUNT}
    lateinit var operation:Operation
    companion object{
        private val log: Logger = LoggerFactory.getLogger(ProgressListener::class.java)
        val atomicThread = SpringUtil.getBean("atomicThread") as ExecutorCoroutineDispatcher
        private val sessionMap = HashMap<String, SessionProgress>()
        fun getSessionProgress(session:String) = runBlocking{
            withContext(atomicThread){
                sessionMap.get(session) ?: SessionProgress(session).also {
                    log.info("create new process for session:{}",session)
                    sessionMap.put(session,it)
                }
            }
        }
    }
    private val map = HashMap<String,ProgressListener>()
    
    fun complete() = runBlocking { withContext(atomicThread){ sessionMap.remove(session) } }

    fun initSink(sink : FluxSink<ProcessDetail>){
        this.sink = sink
    }

    var sink : FluxSink<ProcessDetail>? = null

    fun  newListener(digest:String) = runBlocking {
        withContext(atomicThread){
            val listener = ProgressListener (digest)
            listener.sessionProgress = this@SessionProgress
            map.put(digest,listener)
            listener
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
    @JsonProperty("Config")
    var config:String = ""
    @JsonProperty("RepoTags")
    val repoTags = ArrayList<String>()
    @JsonProperty("Layers")
    val layers = ArrayList<String>()
    @Throws(NoSuchFileException::class)
    fun createFileMap(dir:String):Map<String,String>{
        var dir = dir
        val map = HashMap<String,String>()
        if(!dir.endsWith("/")) {
             dir = dir+"/"
        }
        //val configKey = config.replace(".json","")
        //map[configKey] = dir+config
        layers.forEach {
            val key = sha256(dir+it)
            map[key] = dir+it
        }
        return map
    }
    fun lastLayer(dir:String,layerlist:List<String>):File{
        var dir = dir
        if(!dir.endsWith("/")) {
            dir = dir+"/"
        }
        val fileDir = File(dir)
        println(dir+"   ------")
        val f = fileDir.listFiles().find { it.name.endsWith(".json") && it.name != "manifest.json" }
        if(f!=null){
            return f
        }
        val layers = layerlist.reduceIndexed { i,acc, s -> if(i==1) """"$acc","$s"""" else acc+",\""+s+"\"" }
        val json = """
            {
              "architecture": "amd64",
              "config": {
              },
              "history": [
                {
                  "created_by": "Bash!"
                }
              ],
              "os": "linux",
              "rootfs": {
                "type": "layers",
                "diff_ids": [
                  $layers
                ]
              }
            }
        """.trimIndent()
        val tmpname =  RandomStringUtils.randomAlphanumeric(8)
        val file= File(dir+tmpname)
        val input= ByteArrayInputStream(json.toByteArray())
        TAR.copyInputStreamToFile(input,file)
        return file
    }
}

class ProcessDetail(){
    var action = ""
    var session = ""
    var operation = ""
    var complete = false
    var error = false
    var digest = ""
    var message = ""
    var size = 1L
    var processSize = 0L
    val percent
        get() = "${100*processSize/size}%"

    override fun toString(): String {
        return "ProcessDetail(action='$action', session='$session', operation='$operation', " +
                "complete=$complete, error=$error, digest='$digest', message='$message', size=$size, processSize=$processSize),percent=${percent}"
    }


}


fun DefaultApi.downLoadRrocessClient(progressListener:ProgressListener?=null):OkHttpClient{
    val kubeProperties = SpringUtil.getBean("self-bzh.cloud.k8s.config.KubeProperties") as KubeProperties
    val clientBuild = OkHttpClient.Builder()
    clientBuild.readTimeout(44, TimeUnit.SECONDS)
    progressListener?.let {
        clientBuild.addNetworkInterceptor { chain: Interceptor.Chain ->
            val originalResponse = chain.proceed(chain.request())
            originalResponse.newBuilder()
                    .body(ProgressResponseBody(originalResponse.body()!!, progressListener))
                    .build()
        }
    }
    if (apiClient.basePath in kubeProperties.needProxyUrl && kubeProperties.enableProxy) {
        val proxy = SpringUtil.getBean("httpProxy") as Proxy
        clientBuild.proxy(proxy)
    }
    return  clientBuild.build()
}


fun DefaultApi.pullLayer(name:String, digest:String, authorization:String?, progressListener:ProgressListener?):Response{
    val client = downLoadRrocessClient(progressListener)
    val path = "${apiClient.basePath}/v2/$name/blobs/$digest"
    log.info("pullLayer {}",path)

//    val request = Request.Builder().url(path)
//            .get()
//            .addHeader("Authorization","Bearer $authorization")
//            .build()
//    val call: Call = client.newCall(request)
//    return call.execute()
    return curl {
        client { client }
        request {
            url(path)
            head {
                authorization?.let {
                    "Authorization" to "Bearer $it"
                }
            }
        }
    } as Response

}


//--------- upload ------------
//fun DefaultApi.startUpload(name:String):kotlin.Pair<String?,String?>{
//    val localVarPath = "/v2/$name/blobs/uploads/"
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
//    val localVarAuthNames = arrayOf<String>()
//    val call = apiClient.buildCall(localVarPath, "POST", localVarQueryParams, localVarCollectionQueryParams, null, localVarHeaderParams,
//            localVarCookieParams, localVarFormParams, localVarAuthNames, null);
//    val reponse = apiClient.execute<Void>(call)
//    val list = reponse.headers.get("Location")
//    val uuid = reponse.headers.get("Docker-Upload-Uuid")
//    return kotlin.Pair(list?.get(0),uuid?.get(0))
//}
fun DefaultApi.startUpload(name:String):kotlin.Pair<String?,String?>{
    val reponse = curl {
        client { this@startUpload.apiClient.httpClient }
        request {
            method("post")
            body { json { "a" to "nothing" } }
            url(this@startUpload.apiClient.basePath + "/v2/$name/blobs/uploads/")
        }
    } as Response

    val location = reponse.headers().get("Location")
    val uuid = reponse.headers().get("Docker-Upload-Uuid")
    return kotlin.Pair(location,uuid)
}

fun DefaultApi.existingLayers(name:String,digest:String):Boolean{

    val client = OkHttpClient()
    val path = "${apiClient.basePath}/v2/$name/blobs/$digest"

    val request = Request.Builder().url(path).head().build()
    val call: Call = client.newCall(request)
    val response = call.execute()
    var flag = false
    if (response.code()==200){
        flag = true
    }
    log.info("existingLayers,response code:{},degest:{}",response.code(),digest)
    response.close()
    return flag
}


fun DefaultApi.uploadLayer(url:String,uploadFile:File,listener: ProgressListener?){
    log.info("uploadUrl:{}",url)
    val client = OkHttpClient()

    log.info("body.length:{}",uploadFile.length())
    val body:RequestBody
    if(listener!=null){
        body= ProgressRequestBody(uploadFile,listener)
    }else{
        val input: InputStream = FileInputStream(uploadFile)
        val buf = ByteArray(input.available())
        while (input.read(buf) !== -1);
        body = RequestBody.create(MediaType.get("application/octet-stream"),buf)
    }
    val request: Request = Request.Builder()
            .addHeader("Content-Length",uploadFile.length().toString())
            .url(url).put(body).build()
    val call: Call = client.newCall(request)
    val response = call.execute()
    log.info("uploadLayer code:{}",response.code())
    if(response.code()!=201){
        log.info("uploadLayer response:{}",response.body()?.string())
    }
    response.close()
}

fun DefaultApi.search(keyWord:String,page:Int,pageSize:Int):Map<String,Any>{
    val client = OkHttpClient()
    val path = apiClient.basePath
    log.info("${path}/v1/search?q=$keyWord&n=$pageSize&page=$page")
    val response:Response

    val request = Request.Builder()
            .addHeader("Accept","application/json")
            .url("${path}/v1/search?q=$keyWord&n=$pageSize&page=$page").get().build()
    val call: Call = client.newCall(request)
    response = call.execute()
    val type = object : TypeReference<Map<String,Any>>(){}
    if(response.code() == 404){
        val category = this.catalogGet(10000)
        category.repositories = category.repositories?.filter { it.contains(keyWord) }
        val json = JsonUtil.beanToJson(category)
        return JsonUtil.jsonToBean(json, type)
    }

    val json = response.body()?.string()
    return JsonUtil.jsonToBean(json, type)
}
//curl -X DELETE "http://<registry-host>/v1/repositories/<image-name>/"
//curl -X DELETE "http://<registry-host>/v1/repositories/<image-name>/tags/<tag-name>"

@Throws(IOException::class)
fun readFromInputStream(inputStream: InputStream): String? {
    val resultStringBuilder = StringBuilder()

    BufferedReader(InputStreamReader(inputStream)).use { br ->
        var line: String?
        while (br.readLine().also { line = it } != null) {
            resultStringBuilder.append(line).append("\n")
        }
    }
    return resultStringBuilder.toString()
}

