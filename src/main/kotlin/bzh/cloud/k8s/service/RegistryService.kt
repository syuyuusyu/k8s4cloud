package bzh.cloud.k8s.service

import bzh.cloud.k8s.config.KubeProperties
import bzh.cloud.k8s.expansion.*
import bzh.cloud.k8s.utils.JsonUtil
import bzh.cloud.k8s.utils.TAR
import com.fasterxml.jackson.core.type.TypeReference
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Response
import org.apache.commons.lang.RandomStringUtils
import org.openapitools.client.api.DefaultApi
import org.openapitools.client.model.Manifest
import org.openapitools.client.model.Tags
import org.openapitools.client.model.V2ManifestResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import sha256
import java.io.ByteArrayInputStream
import java.io.File
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.*
import java.util.concurrent.Executor
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import java.nio.file.NoSuchFileException

@Service
class RegistryService(
        val localRegistryApi: DefaultApi,
        val proxy: Proxy,
        val kubeProperties: KubeProperties,
        val threadPool:Executor
) {

    @Value("\${self.authRegistryUrl}")
    lateinit var authRegistryUrl: String
    @Value("\${self.tempFileDir}")
    lateinit var tempFileDir: String


    companion object {
        private val log: Logger = LoggerFactory.getLogger(RegistryService::class.java)
        val downloadListenerMap = Collections.synchronizedMap(HashMap<String, SessionProgress>())
    }

    fun createClient(url: String): DefaultApi {
        return DefaultApi().apply {
            apiClient = org.openapitools.client.ApiClient().apply {
                basePath = url
                if (url in kubeProperties.needProxyUrl && kubeProperties.enableProxy) {
                    log.info("proxy client!!! {}", proxy)
                    httpClient = OkHttpClient.Builder().proxy(proxy).build()
                }
            }
        }
    }

    private fun sessionDir(session: String) = "$tempFileDir/$session"
    private fun sessionFile(session: String) = "$tempFileDir/$session.tar"

    fun search(url: String, keyWord: String, page: Int, pageSize: Int): Mono<Map<String, Any>> {
        val apiClient = createClient(url)
        return Mono.just(apiClient.search(keyWord, page, pageSize))
    }

    fun tagList(url: String, name: String): Mono<Tags> {

        val apiClient = createClient(url)
        val result: Tags
        var token = ""
        val probe = apiClient.nameTagsListGetCall(name, null, null, null).execute()
        if (probe.code() == 401) {
            try {
                val authhead = probe.headers()["Www-Authenticate"]!!
                log.info(authhead)
                val (authurl, service, scope) = """Bearer realm="(.*)",service="(.*)",scope="(.*)"""".toRegex().matchEntire(authhead)!!.destructured
                val authClient = createClient(authurl)
                token = authClient.tokenGet(service, scope).token!!
                result = apiClient.nameTagsListGet(name, null, token)
            } catch (e: Exception) {
                e.printStackTrace()
                return Mono.empty()
            }
        } else {
            val type = object : TypeToken<Tags?>() {}.type
            result = apiClient.apiClient.handleResponse<Tags>(probe, type)
        }
        probe.close()
        return Mono.just(result)
    }

    fun mountImage(url: String, name: String, tag: String): Mono<DownloadInfo> {

        val session = RandomStringUtils.randomAlphanumeric(8)
        val process = SessionProgress(session, "mount")
        downloadListenerMap.put(session, process)
        fun uploadFile() {
            if(process.isok){
                compressDownFile(session)
                upload(session, name, tag)
            }
        }
        process.execComplete = ::uploadFile
        val uploud = startDownload(url, name, tag,"mount",session)
        return uploud
    }

    fun startDownload(url: String, name: String, tag: String, operation: String,session: String = RandomStringUtils.randomAlphanumeric(8)): Mono<DownloadInfo> {
        if(!downloadListenerMap.containsKey(session)){
            downloadListenerMap.put(session,SessionProgress(session,operation))
        }
        val apiClient = createClient(url)
        val result: V2ManifestResult
        var token = ""
        try {
            val probe = apiClient.getManifestsCall(name, tag, token, null).execute()
            log.info("probe.code :{}", probe.code())
            if (probe.code() == 401) {

                val authhead = probe.headers()["Www-Authenticate"]!!
                log.info(authhead)
                val mach = """Bearer realm="(.*)",service="(.*)",scope="(.*)"""".toRegex().matchEntire(authhead)
                if (mach == null) {
                    return Mono.just(DownloadInfo().apply {
                        success = false
                        msg = "请求地址${url}不支持通过Bearer Token验证,无法通过该地址下载镜像"
                    })
                }
                val (authurl, service, scope) = mach.destructured
                val authClient = createClient(authurl)
                token = authClient.tokenGet(service, scope).token!!
                val response = apiClient.getManifestsCall(name, tag, token, null).execute()
                if (response.code() == 401) {
                    return Mono.just(DownloadInfo().apply {
                        success = false
                        msg = "验证下载权限失败，name=$name,scope=$scope"
                    })
                }
                val type = object : TypeToken<V2ManifestResult?>() {}.type
                result = apiClient.apiClient.handleResponse<V2ManifestResult>(response, type)


            } else {
                val type = object : TypeToken<V2ManifestResult?>() {}.type
                result = apiClient.apiClient.handleResponse<V2ManifestResult>(probe, type)
            }
            probe.close()
        } catch (e1: SocketTimeoutException) {
            return Mono.just(DownloadInfo().apply {
                success = false
                msg = "下载镜像manifest超时"
            })
        } catch (e: Exception) {
            e.printStackTrace()
            return Mono.just(DownloadInfo().apply {
                success = false
                msg = "当前镜像${name},版本${tag}不支持以v2.manifest的形式传输，请选择较新的版本下载"
            })
        }

        return Mono.just(result).doOnSuccess { manifest ->
            log.info("doOnSuccess")
            threadPool.execute {
                try {
                    createPullFile(apiClient, name, tag, manifest.config!!.digest!!, session, token, manifest.layers!!.map { fest -> fest.digest!! })
                } catch (e: SocketTimeoutException) {
                    synchronized(downloadListenerMap) {
                        val process = downloadListenerMap.get(session)
                        process?.sink?.next(ProcessDetail().apply {
                            this.error = true
                            this.digest = manifest.config!!.digest!!
                            this.message = "下载${manifest.config!!.digest!!}超时"
                        })
                        process?.isok = false
                        process?.sink?.complete()
                    }
                    throw e
                }
                manifest.layers!!.stream().parallel().forEach {
                    createLayer(apiClient, name, it.digest!!, session, token,operation)
                }
            }
        }.map {
            var a = it.layers!!
            DownloadInfo().apply {
                sessionId = session
                //TODO
                detailUrl = "/registry/downloaddetail/$session"
                success = true
                msg = "start download"
                it.layers!!.forEach { m -> digests.add(m.digest!!) }
            }
        }
    }

    fun upload(session: String, name: String, tag: String) {
        var name = name
        var tag = tag
        val file = File(sessionDir(session))
        if (repositoriesExist(session)) {
            val par = uploadName(file)
            name = par.first
            tag = par.second
        }
        val dir = file.absolutePath

        val manifestFile = file.listFiles()?.find { it.name == "manifest.json" }!!
        val json = readFromInputStream(manifestFile.inputStream())!!
        log.info(json)
        val manifestJson = JsonUtil.jsonToBean(json, object : TypeReference<List<ManifestJson>>() {}).get(0)
        val fileMap:Map<String,String>
        try {
            fileMap = manifestJson.createFileMap(dir)
        }catch (e:NoSuchFileException){
            throw e
            return
        }

        log.info("layer size:{}", fileMap.size)
        val layerManifestlist = ArrayList<Manifest>()
        val uploadList = ArrayList<Pair<String, String>>()


        fun completeUpload() {
            log.info("uploadLayer complete")
            try {
                val lastFile = manifestJson.lastLayer(dir, layerManifestlist.map { it.digest!! })
                val lastFiledigets = "sha256:${sha256(lastFile.toPath())}"
                val (uploadUrl, _) = localRegistryApi.startUpload(name)
                localRegistryApi.uploadLayer("$uploadUrl&digest=$lastFiledigets", lastFile, null)

                val manifest = V2ManifestResult()
                manifest.config = Manifest().apply {
                    this.digest = lastFiledigets
                    this.size = lastFile.length()
                    this.mediaType = "application/vnd.docker.container.image.v1+json"
                }
                manifest.schemaVersion = 2
                manifest.mediaType = "application/vnd.docker.distribution.manifest.v2+json"
                manifest.layers = layerManifestlist

                val result = localRegistryApi.putManifestsWithHttpInfo(name, tag, manifest)
                log.info("putManifests statuscode:{}", result.statusCode)
                if (result.statusCode == 201) {
                    val process = downloadListenerMap.get(session)
                    process?.sink?.next(ProcessDetail().apply {
                        this.session = session
                        this.complete = true
                        this.message = "文件上传完成"
                    })
                    process?.isok = false
                    process?.sink?.complete()
                }
                downloadListenerMap.remove(session)
                clearFile(session)
            }catch (e:Exception){
                e.printStackTrace()
                val process = downloadListenerMap.get(session)
                process?.sink?.next(ProcessDetail().apply {
                    this.session = session
                    this.error = true
                    this.message = "上传文件清单出错"
                })
                process?.sink?.complete()
                process?.isok = false
            }

        }


        fileMap.forEach { digest, fileName ->
            val layerFile = File(fileName)
            uploadList.add(Pair(digest, fileName))
            layerManifestlist.add(Manifest().apply {
                this.digest = "sha256:$digest"
                this.size = layerFile.length()
                this.mediaType = "application/vnd.docker.image.rootfs.diff.tar.gzip"
            })
        }

        val needUplayers = uploadList.filter { (digest, _) -> !localRegistryApi.existingLayers(name, "sha256:$digest") }

        if (needUplayers.size == 0) {
            completeUpload()
        }
        needUplayers.stream().parallel().forEach { (digest, fileName) ->
            val layerFile = File(fileName)
            val progressListener = addListener("upload", session, digest, ::completeUpload)
            if(!progressListener.sessionProgress?.isok!!){
                return@forEach
            }
            val (uploadUrl, _) = localRegistryApi.startUpload(name)
            uploadUrl?.let {
                log.info("upload file:{}", fileName)
                try{
                    localRegistryApi.uploadLayer("$uploadUrl&digest=sha256:$digest", layerFile, progressListener)
                }catch (e:SocketTimeoutException){
                    synchronized(downloadListenerMap) {
                        val process = downloadListenerMap.get(session)
                        process?.sink?.next(ProcessDetail().apply {
                            this.error = true
                            this.digest = digest
                            this.message = "下载${digest}超时"
                        })
                        process?.isok = false
                        log.error("SocketTimeoutException process:{},sink:{}",process==null,process?.sink==null)
                        process?.sink?.complete()
                    }
                    throw e
                }

            }
        }
    }

    fun processdetail(session: String): Flux<ProcessDetail> {
        val process = downloadListenerMap.get(session)
        log.info("layer size:{}", process?.layerSize)
        process?.let {
            return Flux.create<ProcessDetail>(process::initSink)
        }
        return Flux.empty()
    }

    fun downloadimg(session: String, response: ServerHttpResponse): Mono<Resource> {
        val flag = compressDownFile(session)
        if (!flag) {
            response.statusCode = HttpStatus.NO_CONTENT
            return Mono.empty()
        }
        val a = FileSystemResource("${tempFileDir}$session.tar")
        return Mono.just(a)

    }

    fun delete(name: String, tag: String) {
        val manifest = localRegistryApi.getManifests(name, tag, null)
        localRegistryApi.deleteManifests(name, manifest.config?.digest)

        manifest.layers?.forEach {
            localRegistryApi.deleteManifests(name, it.digest)
        }
    }

    fun repositoriesExist(session: String): Boolean {
        val file = File(sessionDir(session))
        val repositories = file.listFiles().find { it.name == "repositories" }
        return repositories != null
    }

    private fun addListener(operation: String, session: String, digest: String, execFun: (() -> Unit)?): ProgressListener {
        val progressListener = ProgressListener(digest)
        synchronized(downloadListenerMap) {
            val process = downloadListenerMap.get(session) ?: SessionProgress(session, operation)
            process.sink?.let {
                progressListener.sessionProgress?.sink = it
            }
            process.addListener(digest, progressListener)
            if (execFun != null) process.execComplete = execFun
            downloadListenerMap.put(session, process)
            log.info("session:{} digest:{},mapsize:{}", session, digest, process.layerSize)
        }
        return progressListener
    }

    fun decompressUploadFile(session: String): File {
        val file = File(sessionDir(session))
        file.mkdirs()
        TAR.decompress(sessionFile(session), file)
        log.info("{}", file.list()?.size)
        return file
    }

    private fun uploadName(file: File): Pair<String, String> {
        val repositories = file.listFiles().find { it.name == "repositories" }
        var data = readFromInputStream(repositories!!.inputStream())!!
        data = data.replace("\\s+".toRegex(), "")
        val (name, tag) = """\{"([^"]+)":\{"([^"]+)":"([^"]+)?"}}""".toRegex().matchEntire(data)!!.destructured
        return Pair(name, tag)
    }

    private fun createLayer(client: DefaultApi, name: String, digest: String, session: String, authorization: String?,operation: String) {

        val progressListener = addListener(operation, session, digest, null)
        if(!progressListener.sessionProgress?.isok!!){
            return
        }
        val nameforLocal = if (name.contains("/")) name.split("/")[1] else name.split("/")[0]
        val exists = localRegistryApi.existingLayers(nameforLocal, digest)
        val response: Response
        try {
            if (exists) {
                log.info("layer:{},aready in local registry {},download from local", digest, localRegistryApi.apiClient.basePath)
                response = localRegistryApi.pullLayer(nameforLocal, digest, authorization, progressListener)
            } else {
                response = client.pullLayer(name, digest, authorization, progressListener)
            }
        } catch (e: SocketTimeoutException) {
            synchronized(downloadListenerMap) {
                val process = downloadListenerMap.get(session)
                process?.sink?.next(ProcessDetail().apply {
                    this.error = true
                    this.digest = digest
                    this.message = "下载${digest}超时"
                })
                process?.isok = false
                log.error("SocketTimeoutException process:{},sink:{}",process==null,process?.sink==null)
                process?.sink?.complete()
            }
            throw e
        }
        log.info("file size:{}", response.header("Content-Length"))

        val filename = digest.replace("sha256:", "")

        val dir = File("$tempFileDir$session/$filename")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File("$tempFileDir$session/$filename/layer.tar")
        TAR.copyInputStreamToFile(response.body()?.byteStream(), file)
        response.close()
    }

    private fun createPullFile(client: DefaultApi, name: String, tag: String, digest: String, session: String, authorization: String?, layerDigests: List<String>) {
        log.info("createPullFile")
        //create configjosnFile
        log.info("pull configjosnFile")
        val response = client.pullLayer(name, digest, authorization, null)
        log.info("pull configjosnFile complete")
        val configjsonfilename = digest.replace("sha256:", "") + ".json"

        val dir = File("$tempFileDir$session/")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val configjosnFile = File("$tempFileDir$session/$configjsonfilename")
        TAR.copyInputStreamToFile(response.body()?.byteStream(), configjosnFile)
        response.close()

        //create repositories
        val repositoriesFile = File("$tempFileDir$session/repositories")
        val dest = name.replace(Regex("(\\w+\\/)?(\\w+)"), { it.groupValues[2] })
        //${layerDigests.last().replace("sha256:","")}
        val repositoriesStr = """
            {
                "$dest": { "$tag": "" }
            }
        """.trimIndent()
        val repositoriesInput = ByteArrayInputStream(repositoriesStr.toByteArray())
        TAR.copyInputStreamToFile(repositoriesInput, repositoriesFile)


        //create manifest.json
        val manifestFile = File("$tempFileDir$session/manifest.json")
        val obj = ManifestJson().apply {
            config = configjsonfilename
            repoTags.add("$dest:$tag")
            layerDigests.map { it.replace("sha256:", "") + "/layer.tar" }.forEach { layers.add(it) }
        }
        val list = arrayOf(obj)
        val manifestStr = JsonUtil.beanToJson(list)
        val manifestInput = ByteArrayInputStream(manifestStr.toByteArray())
        TAR.copyInputStreamToFile(manifestInput, manifestFile)
        log.info("createPullFile complete")
    }

    private fun compressDownFile(session: String): Boolean {
        val dir = "$tempFileDir/$session";
        val file = File(dir)
        if (!file.exists()) {
            return false
        }
        val repositoriesFile = File("$dir/repositories")
        val manifestFile = File("$dir/manifest.json")
        val jsonFileName = file.list().find { it.endsWith(".json") && it != "manifest.json" }!!
        val jsonFile = File("$dir/$jsonFileName")
        var args = arrayOf(repositoriesFile, manifestFile, jsonFile)

        file.list()
                .filter { it != "repositories" && !it.endsWith(".json") }
                .map { File("$dir/$it/layer.tar") }
                .forEach { args += it }
        log.info("compress files:")
        args.iterator().forEach {
            log.info(" ->{}", it.absolutePath)
        }
        TAR.compressDockerImg("$tempFileDir$session.tar", *args)
        return true
    }

    fun clearFile(session: String) {
        val dir = File("$tempFileDir/$session")
        val tar = File("$tempFileDir/$session.tar")
        deleteFolder(dir)
        tar.delete()
    }

    private fun deleteFolder(folder: File) {
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

}