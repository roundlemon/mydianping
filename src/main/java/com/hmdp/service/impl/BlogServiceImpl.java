package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jodd.util.StringUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            getBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryById(Long id) {
        Blog blog = getById(id);
        getBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        //2.查询是否点过赞了
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            //3.不存在添加
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            if (success) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            if (success) {
                //从set集合移除
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Integer id) {
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = top5.stream().map(s -> {
            Long value = Long.valueOf(s);
            return value;
        }).collect(Collectors.toList());

        String idsStr = StrUtil.join(",", ids);

        List<UserDTO> userDTOs = userService.query().in("id", ids).last("order by field(id," + idsStr + ")").
                list().stream().map(user ->
                BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        return Result.ok(userDTOs);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        save(blog);
        // 条件构造器
        LambdaQueryWrapper<Follow> queryWrapper = new LambdaQueryWrapper<>();
        // 从follow表最中，查找当前用户的粉丝  select * from follow where follow_user_id = user_id
        queryWrapper.eq(Follow::getFollowUserId, user.getId());
        //获取当前用户的粉丝
        List<Follow> follows = followService.list(queryWrapper);
        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            String key = FEED_KEY + userId;
            //推送数据
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long id = UserHolder.getUser().getId();
        //2.查询该用户收件箱
        String key = FEED_KEY + id;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().
                reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        //4.解析数据
        List<Long> ids = new ArrayList<>(typedTuples.size());
        Long minTime = 0l;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
        //4.1.获取id
            ids.add(Long.valueOf(typedTuple.getValue()));
        //4.2.获取score
            if (minTime == typedTuple.getScore().longValue()) {
                ++os;
            } else {
                minTime = typedTuple.getScore().longValue();
                os = 1;
            }
        }
        //5.根据id查询blog
        String idsStr = StrUtil.join(",",ids);
        List<Blog> blogs = query().in("id", ids).last("order by field(id," + idsStr + ")").list();
        for (Blog blog : blogs) {
        //5.1.查询发布该blog的用户信息
            getBlogUser(blog);
        //5.2.查询当前用户是否点过赞
            isBlogLiked(blog);
        }
        //6.封装结果并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }

    private void getBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
