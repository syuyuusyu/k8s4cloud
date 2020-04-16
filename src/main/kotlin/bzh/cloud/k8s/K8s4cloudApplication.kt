package bzh.cloud.k8s

import bzh.cloud.k8s.config.beans
import bzh.cloud.k8s.service.WatchService
import bzh.cloud.k8s.utils.SpringUtil
import kotlinx.coroutines.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.support.GenericApplicationContext

@SpringBootApplication
class K8s4cloudApplication{
	@Autowired
	fun addBean(ctx: GenericApplicationContext) = beans().initialize(ctx)
}

fun main(args: Array<String>) {
	runApplication<K8s4cloudApplication>(*args)

	val watchService = SpringUtil.getBean("watchService") as WatchService
	watchService.heartbeat().start()
}


//fun main() = runBlocking {
//	repeat(100_000) { // 启动大量的协程
//		launch {
//			delay(1000L)
//			print(".")
//		}
//	}
//}