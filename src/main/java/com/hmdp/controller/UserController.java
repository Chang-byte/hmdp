package com.hmdp.controller;


import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    /**
     * 发送手机验证码
     * 请求 URL: http://localhost:8080/api/user/code?phone=17732630360
     * 请求方法: POST
     * 状态代码: 200
     * 远程地址: 127.0.0.1:8080
     * 引用者策略: strict-origin-when-cross-origin
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        //发送短信验证码并保存验证码
        return userService.sendCode(phone, session);
//        return Result.fail("功能未完成");
    }

    @GetMapping("/hello")
    public String hello() {
        return "hello";
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     * 请求 URL: http://localhost:8080/api/user/login
     * 请求方法: POST
     * 状态代码: 200
     * 远程地址: 127.0.0.1:8080
     * 引用者策略: strict-origin-when-cross-origin
     * {phone: "17732630360", code: "810670"}
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        //实现登录功能
        return userService.login(loginForm, session);
//        return Result.fail("功能未完成");
    }

    /**
     * 登出功能
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(){
        //实现登出功能
//        return Result.fail("功能未完成");
        UserHolder.removeUser();
        return Result.ok();
    }

    @GetMapping("/me")
    public Result me(){
        //获取当前登录的用户并返回
        UserDTO user = UserHolder.getUser();

//        return Result.fail("功能未完成");
        log.info("User " + user.toString());
        return Result.ok(user);
    }

//    缓存都是空间换时间
    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    // 点击用户头像去查看当前用户的详细信息
    @GetMapping("/{id}")
    public Result user(@PathVariable("id") Long userId){
        // 查询详情
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.ok(userDTO);
    }

    // 签到,使用SETBIT
    @PostMapping("/sign")
    public Result sign(){
        return userService.sign();
    }

    // 实现下面接口，统计当前用户截止当前时间在本月的连续的签到的次数。
    // 连续签到的意思，就是从之前的某一天到今天一直签到吧，今天没签到，意味着连续签到被中断了。
    @GetMapping("/sign/count")
    public Result getSignCount(){
        return userService.getSignCount();
    }

}
