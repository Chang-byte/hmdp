package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)){
            //2. 如果不符合，返回错误信息
            return Result.fail("手机号格式不正确");
        }
        //3. 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4. 保存验证码到redis  设置有效期时间为 2min
        stringRedisTemplate.opsForValue()
                .set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

//        session.setAttribute("code", code);
        //5. 发送验证码
        log.debug("user: {} --> sendCode success: {}", phone, code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            //2. 如果不符合，返回错误信息
            return Result.fail("手机号格式不正确");
        }
        //2. 从redis中获取 校验验证码
//        String code = (String) session.getAttribute("code");
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (code == null || !code.equals(loginForm.getCode())){
            //3. 不一致，报错
            return Result.fail("验证码错误");
        }
        //4. 一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //5. 判断用户是否存在
        if (user == null){
            //6. 不存在，创建新用户并保存
            user = new User();
            user.setPhone(phone);
            user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            this.save(user);
//            return Result.ok();
        }
        //7. 保存用户信息到redis中。
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        // 7.1 随机生成 token，作为登录令牌  也可以使用JWT 生成token
        String token = UUID.randomUUID().toString(true);
        // 7.2.将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // beanToMap
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        /*
                            userDTO：   id   1L
                                        nickName  chang
                                        icon  XXX
                            beanToMap key(fieldName) ---> value(fieldValue)
                         */
                        .setIgnoreNullValue(true)
                        // 转换为String类型 类似于SpringMVC中的Converter转换器
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 7.3.存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 7.4.设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        System.out.println("token: " + token);
        System.out.println("UserDTO:" + userDTO.toString());
        // 将token返回给前端
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        // 将每个用户每个月的签到情况进行统计， 用户名 + 月份 作为key。
        // 1.获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4. offset:今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result getSignCount() {
        // 1.获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4. offset:今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.从Redis中查看其签到记录
        List<Long> result = stringRedisTemplate.opsForValue()
                .bitField(key, BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
                );
        System.out.println(result);
        if (result == null || result.isEmpty()){
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        System.out.println("num = " + num);
        if (num == null || num == 0){
            return Result.ok(0);
        }
        // 6.循环遍历
        // 计数器
        int count = 0;
        while (true){
            // 7.让这个数字与1做 与运算，得到数字的最后一个bit位。
            // 判断这个bit位是否为0
            if ((num & 1) == 0) {
                // 如果为0，说明未签到，结束
                break;
            }else {
                // 如果不为0，说明已签到，计数器 + 1
                count++;
            }
            //把数字右移一位，抛弃掉最后一个bit位，继续下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }


}
