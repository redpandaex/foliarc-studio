package studio.foliarc.admin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import studio.foliarc.biz.auth.JwtProperties;

@SpringBootApplication(scanBasePackages = {"studio.foliarc.admin", "studio.foliarc.biz"})
@MapperScan("studio.foliarc.**.mapper")
@EnableConfigurationProperties(JwtProperties.class)
public class AdminJavaApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminJavaApplication.class, args);
    }
}
