package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    //点击关注的按钮，进行关注
    //id: 被关注的用户的id， isFollow true关注 / false取关
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long id,
                         @PathVariable("isFollow") Boolean isFollow){
        return followService.follow(id, isFollow);

    }

    // 判断当前用户是否关注了发表博客笔记的用户
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long id){
        return followService.isFollow(id);
    }


    /*
        共同关注功能：
            在某条博客点进去博主的首页，我们可以查看博主的详细信息以及博主最近发布的博客
            还可以查看二人的共同关注。
            利用Redis中的set，实现交集功能。
            那就需要将每个人的关注列表也作为一个Set集合存入到redis中。
                key：当前用户id，value:所有我关注的用户的id。
     */
    @GetMapping("/common/{id}")
    public Result getCommonFollows(@PathVariable("id") Long id){
        return followService.getCommonFollows(id);
    }


}
