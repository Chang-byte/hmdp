package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    // 这是使用的是构造器Bean注入
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    // 在执行controller方法(Handler)之前执行的 preHandle  放行 return true
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取请求头中的token 这里看得是前端，前端set到header里面的authorization字段里面了
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) { //因为这个拦截器是放在前面的，没有登录就没有token，所以检测到没有token，也要放行给后面登录
//            response.setContentType("application/json");
//            response.getWriter().println("未登录".toJson);
            return true;
        }
        // 2.基于TOKEN获取redis中的用户
        String key  = LOGIN_USER_KEY + token;
        // 在UserServiceImpl 里面的login方法中 将userDTO 使用了BeanToMap方法转换成一个Map存入的Redis
        // 获取Map
        // stringRedisTemplate.opsForHash().entries(key) 获取存入Map中的键值对
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        // 3.判断用户是否存在
        if (userMap.isEmpty()) { // 同上面，检测到没有登录，就要放行，方便后面的登录 一旦放行后，后面的逻辑就不走了
            return true;
        }
        // 5.将查询到的hash数据转为UserDTO 相互转化
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 6.存在，保存用户信息到 ThreadLocal，以后后面取出即可 拦截器1 底层使用了ThreadLocal
        UserHolder.saveUser(userDTO);
        // 7.刷新token有效期 重新设置30min  任何请求都要进行一个判断，判断是否登录，是的话，进行token有效期的刷新
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 8.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户 如果不删除会有内存泄露的风险， 代表所有的方法都执行完了，该线程就到了末尾，就不用了。
        UserHolder.removeUser();
    }
}
