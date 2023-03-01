package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    /**
     * 根据id查询blog
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog
        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("博客不存在");
        }
        //2.查询blog关联的用户 要关联用户关注和点赞
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null){
            // 用户未登录,无需查询是否点赞，防止报空指针的异常。
            return;
        }
        Long userId = user.getId();
        // 2.判断当前登录用户是否点过赞。
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        // 3.给博客笔记进行赋值。只要score != null, 那就是点赞了。
        blog.setIsLike(score != null);

    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            // 根据blog查询对应绑定的用户
            queryBlogUser(blog);
            // 根据blog查询当前用户是否给这条笔记点过赞。
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     *
     * @param id 笔记id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        // 1.获取当前的登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前登录用户是否已经点赞, Redis的set集合
        // 笔记的id作为key，把给这条笔记点赞的userId作为value。在这里使用ZSet是为了后面用户在点关注的时候，直接就可以根据关注的时间来进行排序地展示。
        // 所有后面会有一个queryBlogLikes
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 使用zset进行存储。 根据查询出来的score来判断用户是否存在！
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null){
            // 3.如果未点赞，可以点赞
            // 3.1数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2保存用户到Redis的zset集合, 使用当前时间的时间戳来进行查询。 zadd key value score
            if (isSuccess){
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }else {
            // 4.如果已点赞，取消点赞
            // 4.1 数据库点赞数 - 1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2 把用户从Redis的set集合中移除
            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 1.查询top5的点赞用户 zrange key 0 4
        Set<String> top5Id = stringRedisTemplate.opsForZSet().range(key, 0, 4); // end - start + 1
        if (top5Id == null || top5Id.isEmpty()){
//            return Result.fail("灭有用户给您点赞！");
            // 没有用户点赞，传入空集合即可。
            return Result.ok(Collections.emptyList());
        }

        // 2.解析出其中的用户id从String类型转换成为Long map(item ->{ return Long.valueOf(item)}).collect(Collectors.toList());
        List<Long> ids = top5Id.stream().map(Long::valueOf).collect(Collectors.toList());
        // 3.根据用户id查询对应的用户，这里为什么要转换成为DTO类型，只展示出少量有用的信息。
        // listByIds 是使用的in 来进行查询，会根据主键自增展示结果，与我们想要的根据点赞的时间戳来进行排序展示有出入。
        // 所以我们要进行mp的改造 .userService.query().in("id", ids) ==== userService.listByIds()
        // 不能手动写死SQL的id， 所以要进行拼接
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> userDTOS = userService.query().in("id", ids)
                .last("ORDER BY FIELD(id,)"+idStr+ ")")
                .list()
                .stream()
                .map(user ->
                        BeanUtil.copyProperties(user, UserDTO.class)
                ).collect(Collectors.toList());
        // 4.进行返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess){
            return Result.fail("新增笔记失败");
        }
        // 3.查询笔记作者的所有粉丝，从tb_follow表中进行查询
        // select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query()
                .eq("follow_user_id", user.getId()).list();
        // 4.推送笔记id给所有粉丝，我们这里有一个粉丝关注的表。
        for (Follow follow : follows) {
            // 4.1获取粉丝id
            Long userId = follow.getUserId();
            // 4.2推送,适合千万粉丝以下的，将新的信息发送到每个关注者的邮箱里面去。这里我们使用redis中的SortedSet，在Java中对应的是ZSet
            String key = RedisConstants.FEED_KEY + userId; //粉丝的id
            // 注意这里是将每个博客的id推送到邮箱里面，节省资源 实际上的博客的内容还要根据博客的id去查询。
            stringRedisTemplate.opsForZSet()
                    .add(key,blog.getId().toString(),System.currentTimeMillis());

        }
        // 返回id
        return Result.ok(blog.getId());

    }

    // 进行滚动查询当前用户关注的博主的最新的更新。
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前用户，
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        // 2.获取到key，拿到收件箱
        String key = RedisConstants.FEED_KEY + userId;
        // ZREVRANGEBYSCORE key Max Min LIMIT offset count
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4.解析数据:blogId、minTime(时间戳)、offset(跟我上次查询的最小值一样的元素的个数。)
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1; // offset:最少为 1
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            // 4.1获取id
            String idStr = tuple.getValue();
            ids.add(Long.valueOf(idStr));
            // 4.2获取分数(时间戳)
            // 每一次都进行更新。
            long time = tuple.getScore().longValue();
            if(time == minTime){ //一样，offset + 1
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }
        // 5.根据id查询blog
        String idStr = StrUtil.join(",",ids);
        List<Blog> blogs = query().in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            // 5.1查询blog有关的用户
            queryBlogUser(blog);
            // 5.2查询blog是否被点赞
            isBlogLiked(blog);
        }
        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        // 根据userId查询到具体内容。
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}

