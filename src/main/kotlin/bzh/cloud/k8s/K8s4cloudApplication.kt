package bzh.cloud.k8s

import bzh.cloud.k8s.config.beans
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
}
