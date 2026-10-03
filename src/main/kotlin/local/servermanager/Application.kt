package local.servermanager

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(exclude=[org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration::class])
@EnableScheduling
class Application
fun main(args: Array<String>) {
    if(args.contains("--recover-owner")) {
        val dir=java.nio.file.Path.of(System.getenv("SERVER_MANAGER_DATA") ?: ".local-data").toAbsolutePath().resolve("security")
        AuthStore(dir).use { it.recoverOwner() }
        println("소유자 세션과 패스키를 폐기했습니다. 새 등록 코드는 $dir/setup-code.txt 에 저장했습니다 (30분 유효).")
        return
    }
    runApplication<Application>(*args)
}
