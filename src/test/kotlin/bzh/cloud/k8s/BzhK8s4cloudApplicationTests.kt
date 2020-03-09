package bzh.cloud.k8s

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class BzhK8s4cloudApplicationTests {

	@Test
	fun contextLoads() {
		var a= Regex(":(\\d+)$")
		var b =a.find("httpe://121212:4545")
		b!!.groupValues[1]
	}

}
