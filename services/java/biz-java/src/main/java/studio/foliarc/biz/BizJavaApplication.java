package studio.foliarc.biz;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import studio.foliarc.biz.auth.JwtProperties;

@SpringBootApplication
@MapperScan("studio.foliarc.**.mapper")
@EnableScheduling
@EnableConfigurationProperties(JwtProperties.class)
public class BizJavaApplication {

    public static void main(String[] args) {
        SpringApplication.run(BizJavaApplication.class, args);
    }
}
