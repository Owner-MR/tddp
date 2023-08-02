package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

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
@Slf4j
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Autowired
    private IBlogService blogService;
    @Autowired
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    
    @Autowired
    private IFollowService followService;

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
            setUserNameWithIcon(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("笔记不存在");
        }
        //查询blog有关用户
        setUserNameWithIcon(blog);
        //查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null){
            //用户未登录 无需查询
            return;
        }
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Long userId = UserHolder.getUser().getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //未点赞 score = null
        //已经点赞了
        blog.setIsLike(score != null);
    }

    @Override
    public void likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id.toString();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null){
            //没点过赞
            // 修改点赞数量 update tb_blog set liked = liked + 1 where id = ?
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess){
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }else {
            //已点过赞
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        //拿到前5个用户id
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 根据id查询用户信息, 这里只需要部分信息，转换为userDTO类型
        //select * from tb_user where id in (5, 1) order by field(id, 5, 1)
        List<UserDTO> userDTOS = userService.query().in("id", ids)
                .last("ORDER BY FIELD (id,"+ idStr +")").list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {

        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean saved = blogService.save(blog);
        if (saved){
            //feed推送到粉丝收件箱中
            List<Follow> followUserIds = followService.query().eq("follow_user_id", user.getId()).list();
            for (Follow followUserId : followUserIds) {
                Long userId = followUserId.getUserId();
                String key = RedisConstants.FEED_KEY + userId;
                stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
            }
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //查询收件箱
        String key = RedisConstants.FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> scores = stringRedisTemplate.opsForZSet().
                reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        if (scores == null || scores.isEmpty()){
            return Result.ok();
        }
        //解析数据 userId, minTime, offset
        List<Long> ids = new ArrayList<>(scores.size());
        long minTime = 0;
        int os = 1;
        StringBuilder stringBuilder = new StringBuilder();
        //遍历所有关注的用户和分数
        for (ZSetOperations.TypedTuple<String> score : scores) {
            ids.add(Long.valueOf(score.getValue()));
            stringBuilder.append(score.getValue() + ",");
            long time = score.getScore().longValue();
            if (time == minTime) {
                os++;
            }
            else {
                minTime = time;
                os = 1;
            }
        }
        StringBuilder idStr = stringBuilder.deleteCharAt(stringBuilder.length() - 1);
        //ids集合有序，而mysql中in查询无序，需要指定变量顺序
        List<Blog> blogs = query().in("id", ids)
                .last("ORDER BY FIELD (id," + idStr + ")").list();
        for (Blog blog : blogs) {
            //查询blog有关用户
            setUserNameWithIcon(blog);
            //查询blog是否被点赞
            isBlogLiked(blog);
        }
        //封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }

    private void setUserNameWithIcon(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
