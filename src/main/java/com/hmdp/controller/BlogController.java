package com.hmdp.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;
    @Resource
    private IUserService userService;

    /*
    基于推模式实现关注推送功能
        需求:
        1.修改新增探店笔记的业务，在保存blog到数据库的同时，推送到粉丝的收件箱。
        2.收件箱满足可以根据时间戳排序，必须用Redis的数据结构实现。
        3.查询收件箱数据时，可以实现分页查询。

        前面的，这种推送类似于聊天室。
        从你关注过后才开始主动推。老笔记通过拉模式或者查看博主主页实现
        Feed流的滚动分页， SortedSet 可以根据score的值来进行排序，可以使用时间戳，根据时间戳的大小来进行排序。
        Feed流中的数据会不断更新，所以数据的角标也在变化，因此不能采用传统的分页模式。
     */
    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
//        // 获取登录用户
//        UserDTO user = UserHolder.getUser();
//        blog.setUserId(user.getId());
//        // 保存探店博文
//        blogService.save(blog);
//        // 返回id
//        return Result.ok(blog.getId());
        return blogService.saveBlog(blog);
    }

    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        // 修改点赞数量
//        blogService.update()
//                .setSql("liked = liked + 1").eq("id", id).update();
        return blogService.likeBlog(id);
    }

    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryHotBlog(current);
    }


    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    /*
        点赞的集合是set，是无序的，如果我们想要一个前5名，展示最近的点赞的人。
        那就必须要使用有序的SortedSet，根据score值排序。
        score是可以自定医德，我们可以使用时间戳来进行排序。

        查看某条博客的最近的点赞的人。 id该博客的id。
     */
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id){
        return blogService.queryBlogLikes(id);
    }

    // 当在某一条博客的具体页面点击头像，会进入博主的详情页。
    // http://localhost:8080/api/blog/of/user?&id=1010&current=1
    // 这里是路径传参，要是有@RequestParam
    @GetMapping("/of/user")
    public Result queryBlogByUserId(
            @RequestParam("id") Long id,
            @RequestParam(value = "current",defaultValue = "1") Integer current
    ){
        //根据用户查询
        Page<Blog> page = blogService.query().eq("user_id", id)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        //获取当前页面的数据
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    // ZREVRANGEBYSCORE z1 (MAX)1000 (MIN Variable)0 WITHSCORES LIMIT 0(Variable) 3(固定)
    // 第一次Limit offset给 0 其余次Limit offset给 1

    /**
     * 1、每次查询完成后，我们要分析出查询出数据的最小时间戳，这个值会作为下一次查询的条件
     * 2、我们需要找到与上一次查询相同的查询个数作为偏移量，下次查询时，
     *      跳过这些查询过的数据，拿到我们需要的数据
     * @param max
     * @param offset
     * @return
     */
    @GetMapping("/of/follow")
    // RequestParam 表示接受url地址栏传参的注解，
    // 当方法上参数的名称和url地址栏不相同时，可以通过RequestParam 来进行指定
    public Result queryBlogOfFollow(
            @RequestParam("lastId") Long max, @RequestParam(value = "offset", defaultValue = "0") Integer offset){
        return blogService.queryBlogOfFollow(max, offset);
    }


}
