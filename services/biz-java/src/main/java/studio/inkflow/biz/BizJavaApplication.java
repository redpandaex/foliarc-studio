package studio.inkflow.biz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import studio.inkflow.biz.auth.JwtProperties;

@SpringBootApplication
@EnableConfigurationProperties(JwtProperties.class)
public class BizJavaApplication {

    public static void main(String[] args) {
        SpringApplication.run(BizJavaApplication.class, args);
    }
}
