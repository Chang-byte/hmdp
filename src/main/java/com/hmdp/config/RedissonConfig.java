package com.hmdp.config;


import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 配置Redisson客户端
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        //配置
        Config config = new Config();
        // 使用单点 主机地址 redis客户端密码
        config.useSingleServer().setAddress("redis://192.168.56.129:6379")
                .setPassword("root");
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
}
